package com.pocketcalc.calculator.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/**
 * PDF из тайника для показа по страницам.
 *
 * Системному «рисовальщику» PDF нужен файл, а не байты в памяти. На
 * Android 11+ этот «файл» создаётся прямо в оперативной памяти (memfd) —
 * на диск ничего не попадает. На Android 8–10 расшифрованный PDF на
 * мгновение записывается во внутреннюю папку приложения и сразу стирается:
 * открытый файл продолжает читаться, а имени на диске уже нет. Остатки
 * после сбоя стирает [cleanup] при запуске приложения.
 */
class VaultPdf private constructor(private val renderer: PdfRenderer) : Closeable {

    /** Рисовальщик PDF нельзя трогать из двух потоков сразу. */
    private val lock = Any()
    private var closed = false

    val pageCount: Int = renderer.pageCount

    /** Рисует страницу [index] шириной [widthPx] на белом фоне. */
    fun render(index: Int, widthPx: Int): Bitmap = synchronized(lock) {
        check(!closed) { "документ закрыт" }
        val page = renderer.openPage(index)
        try {
            val scale = widthPx.toFloat() / page.width
            val height = (page.height * scale).roundToInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        } finally {
            page.close()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (!closed) {
                closed = true
                renderer.close()
            }
        }
    }

    companion object {

        /** Открывает PDF. Если он защищён паролем — SecurityException. */
        fun open(bytes: ByteArray, tmpDir: File): VaultPdf {
            val descriptor = openDescriptor(bytes, tmpDir)
            return try {
                VaultPdf(PdfRenderer(descriptor))
            } catch (e: Exception) {
                descriptor.close()
                throw e
            }
        }

        /** Стирает временные файлы, оставшиеся после сбоя. Вызывать при запуске. */
        fun cleanup(tmpDir: File) {
            tmpDir.listFiles()?.forEach { it.delete() }
        }

        private fun openDescriptor(bytes: ByteArray, tmpDir: File): ParcelFileDescriptor {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    return inMemory(bytes)
                } catch (e: Exception) {
                    // ниже — запасной путь через временный файл
                }
            }
            return throughUnlinkedFile(bytes, tmpDir)
        }

        @RequiresApi(Build.VERSION_CODES.R)
        private fun inMemory(bytes: ByteArray): ParcelFileDescriptor {
            val fd = Os.memfd_create("doc", 0)
            try {
                var offset = 0
                while (offset < bytes.size) {
                    val written = Os.write(fd, bytes, offset, bytes.size - offset)
                    if (written <= 0) throw IOException("не удалось записать PDF в память")
                    offset += written
                }
                Os.lseek(fd, 0, OsConstants.SEEK_SET)
                return ParcelFileDescriptor.dup(fd)
            } finally {
                Os.close(fd)
            }
        }

        private fun throughUnlinkedFile(bytes: ByteArray, tmpDir: File): ParcelFileDescriptor {
            tmpDir.mkdirs()
            val file = File(tmpDir, "d" + System.nanoTime())
            try {
                file.writeBytes(bytes)
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } finally {
                file.delete()
            }
        }
    }
}
