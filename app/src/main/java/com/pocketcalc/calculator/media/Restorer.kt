package com.pocketcalc.calculator.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.pocketcalc.calculator.vault.EntryKind
import com.pocketcalc.calculator.vault.Sha256
import com.pocketcalc.calculator.vault.VaultEntry
import com.pocketcalc.calculator.vault.VaultRepository
import com.pocketcalc.calculator.vault.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Возврат файла из тайника в обычную память телефона: фото и видео — в
 * Галерею (в ту папку, откуда их взяли), документы — в «Загрузки».
 *
 * Порядок как при добавлении, только в обратную сторону: сначала файл
 * записывается скрытым от других приложений, затем перечитывается и
 * сверяется по отпечатку, и только потом становится видимым, а из тайника
 * удаляется. Сбой на любом шаге — недописанный файл стирается, в тайнике
 * всё остаётся как было.
 */
object Restorer {

    /** Чем закончился возврат. */
    data class Result(
        /** Папка, куда лёг файл, например "DCIM/Camera". */
        val folder: String,
        /** Убран ли файл из тайника (если нет — копия осталась и там). */
        val removedFromVault: Boolean,
    )

    private data class Target(val collection: Uri, val folder: String)

    /**
     * Работает на Android 10 и новее: там приложение может положить файл в
     * Галерею без разрешений. Вызывать не в главном потоке не нужно — функция
     * сама уходит в фон.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun restore(
        context: Context,
        repository: VaultRepository,
        session: VaultSession,
        entry: VaultEntry,
    ): Result = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var lastError: Exception? = null
        for (target in targets(entry)) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, entry.name)
                put(MediaStore.MediaColumns.MIME_TYPE, entry.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, target.folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            // Папка может не подойти (например, чужая папка приложения) —
            // тогда пробуем следующую.
            val uri: Uri? = try {
                resolver.insert(target.collection, values)
            } catch (e: IllegalArgumentException) {
                lastError = e
                null
            } catch (e: SecurityException) {
                lastError = e
                null
            }
            if (uri == null) continue

            try {
                val written = resolver.openOutputStream(uri, "w")?.use { out ->
                    repository.exportContent(session, entry, out)
                } ?: throw IOException("не удалось открыть файл для записи")
                val onDisk = resolver.openInputStream(uri)?.use { Sha256.of(it) }
                    ?: throw IOException("не удалось перечитать записанный файл")
                if (onDisk != written) throw IOException("записанный файл не совпал с оригиналом")
                val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                if (resolver.update(uri, published, null, null) != 1) {
                    throw IOException("не удалось показать файл в Галерее")
                }
            } catch (e: Exception) {
                try {
                    resolver.delete(uri, null, null)
                } catch (cleanup: Exception) {
                    // недописанный скрытый файл система удалит сама через несколько дней
                }
                throw e
            }

            val removed = try {
                repository.deleteEntry(session, entry.id)
            } catch (e: Exception) {
                false
            }
            return@withContext Result(target.folder.trimEnd('/'), removed)
        }
        throw IOException("не нашлось папки для возврата", lastError)
    }

    /** Куда пробовать вернуть файл — по порядку, до первого успеха. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun targets(entry: VaultEntry): List<Target> {
        val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
        val downloads = MediaStore.Downloads.getContentUri(volume)
        val media = when (entry.kind) {
            EntryKind.PHOTO -> MediaStore.Images.Media.getContentUri(volume)
            EntryKind.VIDEO -> MediaStore.Video.Media.getContentUri(volume)
            else -> null
        }
        val list = ArrayList<Target>()
        val original = normalizeFolder(entry.origFolder)
        if (original != null) {
            when {
                original.startsWith("Download/", ignoreCase = true) -> list += Target(downloads, original)
                media != null -> list += Target(media, original)
            }
        }
        when (entry.kind) {
            EntryKind.PHOTO -> list += Target(media!!, "Pictures/")
            EntryKind.VIDEO -> list += Target(media!!, "Movies/")
            else -> Unit
        }
        // «Загрузки» принимают файлы любого типа — последний запасной вариант.
        list += Target(downloads, "Download/")
        return list.distinct()
    }

    /** "DCIM/Camera" → "DCIM/Camera/"; пусто или подозрительно → null. */
    private fun normalizeFolder(folder: String): String? {
        val f = folder.trim().trimStart('/')
        if (f.isEmpty() || f.contains("..")) return null
        return if (f.endsWith("/")) f else "$f/"
    }
}
