package com.pocketcalc.calculator.crypto

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKey
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.util.SecretBytes
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.SeekableByteChannel

/**
 * Потоковое шифрование файлов на Google Tink (режим AES256-GCM-HKDF, куски по 1 МБ).
 *
 * Файл шифруется и расшифровывается кусками, поэтому даже видео на несколько
 * гигабайт не загружается в память целиком. Каждый кусок защищён меткой
 * целостности: при повреждении или подмене расшифровка выбрасывает ошибку,
 * а не отдаёт испорченные данные.
 *
 * `associatedData` привязывает содержимое к конкретной записи (например, к её
 * идентификатору): кусок, скопированный из другого файла, не расшифруется.
 */
object FileCrypto {

    private const val SEGMENT_SIZE = 1024 * 1024 // 1 МБ

    init {
        StreamingAeadConfig.register()
    }

    private fun primitive(key32: ByteArray): StreamingAead {
        require(key32.size == 32) { "нужен 32-байтовый ключ" }
        val params = AesGcmHkdfStreamingParameters.builder()
            .setKeySizeBytes(32)
            .setDerivedAesGcmKeySizeBytes(32)
            .setHkdfHashType(AesGcmHkdfStreamingParameters.HashType.SHA256)
            .setCiphertextSegmentSizeBytes(SEGMENT_SIZE)
            .build()
        val key = AesGcmHkdfStreamingKey.create(
            params,
            SecretBytes.copyFrom(key32, InsecureSecretKeyAccess.get()),
        )
        val handle = KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.importKey(key).withRandomId().makePrimary())
            .build()
        return handle.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
    }

    /** Шифрует всё из [plaintext] в [ciphertext]. Потоки закрывает вызывающий. */
    fun encrypt(
        plaintext: InputStream,
        ciphertext: OutputStream,
        key32: ByteArray,
        associatedData: ByteArray,
    ) {
        val sa = primitive(key32)
        sa.newEncryptingStream(ciphertext, associatedData).use { encrypting ->
            plaintext.copyTo(encrypting, bufferSize = 64 * 1024)
        }
    }

    /** Расшифровывает всё из [ciphertext] в [plaintext]. Потоки закрывает вызывающий. */
    fun decrypt(
        ciphertext: InputStream,
        plaintext: OutputStream,
        key32: ByteArray,
        associatedData: ByteArray,
    ) {
        val sa = primitive(key32)
        sa.newDecryptingStream(ciphertext, associatedData).use { decrypting ->
            decrypting.copyTo(plaintext, bufferSize = 64 * 1024)
        }
    }

    /**
     * Поток, из которого читаются расшифрованные байты по мере чтения.
     * Нужен для просмотра (фото/видео/PDF) без расшифровки всего файла на диск.
     * Закрывает [ciphertext] вызывающий (через закрытие возвращённого потока).
     */
    fun decryptingStream(ciphertext: InputStream, key32: ByteArray, associatedData: ByteArray): InputStream =
        primitive(key32).newDecryptingStream(ciphertext, associatedData)

    /**
     * Расшифровка с произвольным доступом: можно читать с любого места, не
     * расшифровывая всё до него (нужно видео для перемотки). Расшифровываются
     * и проверяются только нужные куски. Закрытие возвращённого канала
     * закрывает и [ciphertext].
     */
    fun seekableDecryptingChannel(
        ciphertext: SeekableByteChannel,
        key32: ByteArray,
        associatedData: ByteArray,
    ): SeekableByteChannel = primitive(key32).newSeekableDecryptingChannel(ciphertext, associatedData)

    /** Поток, в который пишутся данные для шифрования на лету. */
    fun encryptingStream(ciphertext: OutputStream, key32: ByteArray, associatedData: ByteArray): OutputStream =
        primitive(key32).newEncryptingStream(ciphertext, associatedData)
}
