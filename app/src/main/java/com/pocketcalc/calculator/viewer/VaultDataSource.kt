package com.pocketcalc.calculator.viewer

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * Источник данных для видеоплеера: читает зашифрованный файл тайника и
 * расшифровывает на лету только нужные куски. На диск ничего не пишется,
 * перемотка работает в любое место.
 *
 * [size] — точный размер файла из оглавления тайника (канал расшифровки
 * узнаёт свой размер только после первого чтения).
 */
@OptIn(UnstableApi::class)
class VaultDataSource(
    private val size: Long,
    private val openChannel: () -> SeekableByteChannel,
) : BaseDataSource(/* isNetwork = */ false) {

    private var channel: SeekableByteChannel? = null
    private var uri: Uri? = null
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        if (dataSpec.position > size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        val ch = try {
            openChannel()
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        channel = ch
        try {
            ch.position(dataSpec.position)
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        val available = size - dataSpec.position
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            available
        } else {
            minOf(dataSpec.length, available)
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val ch = channel ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        val read = try {
            readSome(ch, ByteBuffer.wrap(buffer, offset, toRead))
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        if (read < 0) {
            // Файл оборвался раньше, чем записано в оглавлении.
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            channel?.close()
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        } finally {
            channel = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    /** Читает хотя бы один байт (или −1 в конце). Канал файла не возвращает 0, но подстрахуемся. */
    private fun readSome(ch: SeekableByteChannel, target: ByteBuffer): Int {
        repeat(MAX_EMPTY_READS) {
            val n = ch.read(target)
            if (n != 0) return n
        }
        throw IOException("расшифровка не отдаёт данные")
    }

    private companion object {
        const val MAX_EMPTY_READS = 100
    }
}
