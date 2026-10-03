package com.pocketcalc.calculator.vault

import com.pocketcalc.calculator.crypto.FileCrypto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom

/**
 * Хранилище одного тайника на диске.
 *
 * Раскладка внутри [baseDir]:
 * ```
 *   index.bin        — зашифрованное оглавление
 *   blobs/<id>       — зашифрованные файлы (имена случайные)
 *   tmp/             — незавершённые операции (чистится при запуске)
 * ```
 *
 * Все операции записи атомарны: сначала во временный файл, затем мгновенное
 * переименование. Если телефон выключится посреди записи, старые данные целы,
 * а мусор из `tmp/` удаляется при следующем запуске.
 */
class VaultStorage(private val baseDir: File) {

    private val blobsDir = File(baseDir, "blobs")
    private val tmpDir = File(baseDir, "tmp")
    private val indexFile = File(baseDir, "index.bin")
    private val rng = SecureRandom()

    private val adIndex = "index".toByteArray()

    init {
        blobsDir.mkdirs()
        tmpDir.mkdirs()
    }

    // --- Оглавление -------------------------------------------------------

    /** Атомарно сохраняет оглавление, зашифровав его мастер-ключом. */
    fun saveIndex(masterKey: ByteArray, index: VaultIndex) {
        val plain = VaultIndex.serialize(index)
        val tmp = File(tmpDir, "index-${randomId()}")
        FileOutputStream(tmp).use { fos ->
            FileCrypto.encrypt(ByteArrayInputStream(plain), fos, masterKey, adIndex)
            syncQuietly(fos)
        }
        moveReplace(tmp, indexFile)
    }

    /**
     * Загружает оглавление. Если файла нет — пустое оглавление.
     * Если расшифровать не удалось (неверный ключ/повреждение) — null.
     */
    fun loadIndex(masterKey: ByteArray): VaultIndex? {
        if (!indexFile.exists()) return VaultIndex.empty()
        return try {
            val bos = ByteArrayOutputStream()
            FileInputStream(indexFile).use { FileCrypto.decrypt(it, bos, masterKey, adIndex) }
            VaultIndex.deserialize(bos.toByteArray())
        } catch (e: Exception) {
            null
        }
    }

    // --- Файлы (blobs) ----------------------------------------------------

    /**
     * Шифрует [input] в новый blob и возвращает его идентификатор.
     * Перед тем как принять файл, он расшифровывается обратно для проверки;
     * если задан [expectedSize] и он не совпал — операция отменяется.
     */
    fun addBlob(masterKey: ByteArray, input: InputStream, expectedSize: Long? = null): String {
        val blobId = randomId()
        val ad = blobId.toByteArray()
        val tmp = File(tmpDir, "blob-${randomId()}")
        try {
            FileOutputStream(tmp).use { fos ->
                FileCrypto.encrypt(input, fos, masterKey, ad)
                syncQuietly(fos)
            }
            val decryptedSize = countDecrypted(tmp, masterKey, ad)
            if (expectedSize != null && decryptedSize != expectedSize) {
                error("проверка не прошла: размер $decryptedSize вместо $expectedSize")
            }
            moveReplace(tmp, File(blobsDir, blobId))
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        return blobId
    }

    /** Поток с расшифрованным содержимым blob (для просмотра). Закрывает его вызывающий. */
    fun openBlob(masterKey: ByteArray, blobId: String): InputStream {
        val fis = FileInputStream(File(blobsDir, blobId))
        return FileCrypto.decryptingStream(fis, masterKey, blobId.toByteArray())
    }

    /** Удаляет blob. */
    fun deleteBlob(blobId: String): Boolean = File(blobsDir, blobId).delete()

    fun blobExists(blobId: String): Boolean = File(blobsDir, blobId).exists()

    // --- Обслуживание -----------------------------------------------------

    /** Удаляет незавершённые временные файлы. Вызывать при запуске приложения. */
    fun cleanupTmp() {
        tmpDir.listFiles()?.forEach { it.delete() }
    }

    // --- Внутреннее -------------------------------------------------------

    private fun countDecrypted(file: File, masterKey: ByteArray, ad: ByteArray): Long {
        var total = 0L
        FileInputStream(file).use { fis ->
            FileCrypto.decryptingStream(fis, masterKey, ad).use { dec ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = dec.read(buf)
                    if (n < 0) break
                    total += n
                }
            }
        }
        return total
    }

    /**
     * Сбрасывает данные на диск, если платформа это поддерживает.
     * На Android работает; на некоторых файловых системах (контейнеры, тесты)
     * fsync недоступен — тогда просто продолжаем: надёжность всё равно
     * обеспечивает атомарное переименование ниже.
     */
    private fun syncQuietly(fos: FileOutputStream) {
        try {
            fos.fd.sync()
        } catch (e: java.io.SyncFailedException) {
            // платформа не поддерживает fsync — не критично
        } catch (e: java.io.IOException) {
            // то же
        }
    }

    private fun moveReplace(from: File, to: File) {
        try {
            Files.move(
                from.toPath(), to.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun randomId(): String {
        val b = ByteArray(16)
        rng.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }
}
