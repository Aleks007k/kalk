package com.pocketcalc.calculator.vault

import com.pocketcalc.calculator.crypto.FileCrypto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.security.DigestInputStream
import java.security.MessageDigest
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

    /** Сохранённый зашифрованный файл: идентификатор, исходный размер и отпечаток. */
    data class StoredBlob(val id: String, val size: Long, val sha256: String)

    /**
     * Шифрует [input] в новый blob.
     *
     * Пока данные читаются, считается их отпечаток SHA-256. Затем файл
     * расшифровывается обратно, и отпечаток и размер сверяются с исходными:
     * файл принимается, только если они совпали. Если задан [expectedSize]
     * и прочитано другое количество байт — операция тоже отменяется.
     */
    fun storeBlob(masterKey: ByteArray, input: InputStream, expectedSize: Long? = null): StoredBlob {
        val blobId = AtomicFiles.randomHex(16)
        val ad = blobId.toByteArray()
        val tmp = File(tmpDir, "blob-${AtomicFiles.randomHex(16)}")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val counting = CountingInputStream(DigestInputStream(input, digest))
            FileOutputStream(tmp).use { fos ->
                FileCrypto.encrypt(counting, fos, masterKey, ad)
                AtomicFiles.syncQuietly(fos)
            }
            val readSize = counting.count
            val readSha = digest.digest()
            if (expectedSize != null && readSize != expectedSize) {
                error("проверка не прошла: прочитано $readSize байт вместо $expectedSize")
            }
            val (decryptedSize, decryptedSha) = digestDecrypted(tmp, masterKey, ad)
            if (decryptedSize != readSize || !MessageDigest.isEqual(decryptedSha, readSha)) {
                error("проверка не прошла: расшифрованное не совпало с исходным")
            }
            AtomicFiles.moveReplace(tmp, File(blobsDir, blobId))
            return StoredBlob(blobId, readSize, readSha.toHex())
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /** То же, что [storeBlob], но возвращает только идентификатор. */
    fun addBlob(masterKey: ByteArray, input: InputStream, expectedSize: Long? = null): String =
        storeBlob(masterKey, input, expectedSize).id

    /** Поток с расшифрованным содержимым blob (для просмотра). Закрывает его вызывающий. */
    fun openBlob(masterKey: ByteArray, blobId: String): InputStream {
        val fis = FileInputStream(File(blobsDir, blobId))
        return FileCrypto.decryptingStream(fis, masterKey, blobId.toByteArray())
    }

    /**
     * Расшифровка blob с произвольным доступом (видео с перемоткой).
     * Закрывает канал вызывающий.
     */
    fun openBlobSeekable(masterKey: ByteArray, blobId: String): SeekableByteChannel {
        val channel = FileInputStream(File(blobsDir, blobId)).channel
        return try {
            FileCrypto.seekableDecryptingChannel(channel, masterKey, blobId.toByteArray())
        } catch (e: Exception) {
            channel.close()
            throw e
        }
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

    /** Расшифровывает blob целиком, считая размер и отпечаток (без записи на диск). */
    private fun digestDecrypted(file: File, masterKey: ByteArray, ad: ByteArray): Pair<Long, ByteArray> {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        FileInputStream(file).use { fis ->
            FileCrypto.decryptingStream(fis, masterKey, ad).use { dec ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = dec.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    total += n
                }
            }
        }
        return total to digest.digest()
    }

    /** Считает, сколько байт прочитано из потока. */
    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) count++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) count += n
            return n
        }

        override fun skip(n: Long): Long {
            val skipped = super.skip(n)
            count += skipped
            return skipped
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
