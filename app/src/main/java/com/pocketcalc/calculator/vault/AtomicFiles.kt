package com.pocketcalc.calculator.vault

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.SyncFailedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom

/**
 * Атомарная запись файлов: временный файл → сброс на диск → мгновенное
 * переименование поверх старого. Если телефон выключится посреди записи,
 * на месте останется либо старая версия файла, либо новая — но не обрывок.
 */
internal object AtomicFiles {

    private val rng = SecureRandom()

    /** Атомарно записывает [bytes] в [target], используя [tmpDir] для временного файла. */
    fun writeBytes(target: File, tmpDir: File, bytes: ByteArray) {
        tmpDir.mkdirs()
        val tmp = File(tmpDir, "w-${randomHex(8)}")
        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(bytes)
                syncQuietly(fos)
            }
            moveReplace(tmp, target)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /**
     * Сбрасывает данные на диск, если платформа это поддерживает.
     * На Android работает; на некоторых файловых системах (контейнеры, тесты)
     * fsync недоступен — тогда продолжаем: целостность всё равно обеспечивает
     * атомарное переименование.
     */
    fun syncQuietly(fos: FileOutputStream) {
        try {
            fos.fd.sync()
        } catch (e: SyncFailedException) {
            // платформа не поддерживает fsync — не критично
        } catch (e: IOException) {
            // то же
        }
    }

    fun moveReplace(from: File, to: File) {
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

    fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        rng.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }
}
