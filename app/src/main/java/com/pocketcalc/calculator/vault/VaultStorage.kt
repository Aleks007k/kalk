package com.pocketcalc.calculator.vault

import com.pocketcalc.calculator.crypto.FileCrypto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Хранилище тайников на диске. Общее для обоих тайников (настоящего и фальшивого).
 *
 * Раскладка внутри [baseDir]:
 * ```
 *   index_<имя>.bin  — зашифрованное оглавление; <имя> выводится из мастер-ключа,
 *                      поэтому без ключа не понять, чьё это оглавление
 *   blobs/<id>       — зашифрованные файлы обоих тайников (имена случайные)
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

    private val adIndex = "index".toByteArray()
    private val indexNameLabel = "vault-index-name".toByteArray()

    init {
        blobsDir.mkdirs()
        tmpDir.mkdirs()
    }

    // --- Оглавление -------------------------------------------------------

    /** Атомарно сохраняет оглавление, зашифровав его мастер-ключом. */
    fun saveIndex(masterKey: ByteArray, index: VaultIndex) {
        val plain = VaultIndex.serialize(index)
        val tmp = File(tmpDir, "index-${AtomicFiles.randomHex(16)}")
        try {
            FileOutputStream(tmp).use { fos ->
                FileCrypto.encrypt(ByteArrayInputStream(plain), fos, masterKey, adIndex)
                AtomicFiles.syncQuietly(fos)
            }
            AtomicFiles.moveReplace(tmp, indexFile(masterKey))
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /**
     * Загружает оглавление тайника с этим мастер-ключом.
     * Если файла нет — пустое оглавление (тайник ещё пуст).
     * Если файл есть, но расшифровать не удалось (повреждение) — null.
     */
    fun loadIndex(masterKey: ByteArray): VaultIndex? {
        val file = indexFile(masterKey)
        if (!file.exists()) return VaultIndex.empty()
        return try {
            val bos = ByteArrayOutputStream()
            FileInputStream(file).use { FileCrypto.decrypt(it, bos, masterKey, adIndex) }
            VaultIndex.deserialize(bos.toByteArray())
        } catch (e: Exception) {
            null
        }
    }

    /** Имя файла оглавления: HMAC от мастер-ключа. Без ключа имя ничего не говорит. */
    private fun indexFile(masterKey: ByteArray): File {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(masterKey, "HmacSHA256"))
        val tag = mac.doFinal(indexNameLabel)
        val name = tag.copyOf(16).joinToString("") { "%02x".format(it) }
        return File(baseDir, "index_$name.bin")
    }

    // --- Файлы (blobs) ----------------------------------------------------

    /**
     * Шифрует [input] в новый blob и возвращает его идентификатор.
     * Перед тем как принять файл, он расшифровывается обратно для проверки;
     * если задан [expectedSize] и он не совпал — операция отменяется.
     */
    fun addBlob(masterKey: ByteArray, input: InputStream, expectedSize: Long? = null): String {
        val blobId = AtomicFiles.randomHex(16)
        val ad = blobId.toByteArray()
        val tmp = File(tmpDir, "blob-${AtomicFiles.randomHex(16)}")
        try {
            FileOutputStream(tmp).use { fos ->
                FileCrypto.encrypt(input, fos, masterKey, ad)
                AtomicFiles.syncQuietly(fos)
            }
            val decryptedSize = countDecrypted(tmp, masterKey, ad)
            if (expectedSize != null && decryptedSize != expectedSize) {
                error("проверка не прошла: размер $decryptedSize вместо $expectedSize")
            }
            AtomicFiles.moveReplace(tmp, File(blobsDir, blobId))
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

    /** Есть ли в хранилище хоть один зашифрованный файл. */
    fun hasBlobs(): Boolean = blobsDir.listFiles()?.isNotEmpty() == true

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
}
