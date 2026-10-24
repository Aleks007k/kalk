package com.pocketcalc.calculator.media

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.OsConstants
import com.pocketcalc.calculator.vault.ImportMeta
import com.pocketcalc.calculator.vault.ImportResult
import com.pocketcalc.calculator.vault.SizeMismatchException
import com.pocketcalc.calculator.vault.VaultRepository
import com.pocketcalc.calculator.vault.VaultSession
import com.pocketcalc.calculator.vault.VerificationFailedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

/** Почему файл не удалось добавить. */
enum class ImportProblem {
    /** Файл не открылся: перемещён, удалён или недоступен. */
    OPEN,
    /** Файл менялся, пока его читали (прочитано не столько, сколько в нём есть). */
    CHANGED,
    /** Не хватает места в памяти телефона. */
    NO_SPACE,
    /** Проверка зашифрованной копии не прошла. */
    VERIFY,
    /** Другая ошибка чтения или записи. */
    OTHER,
}

/** Файл, который не удалось добавить: имя, причина и короткий код ошибки (для разбора). */
data class ImportFailure(val name: String, val problem: ImportProblem, val code: String)

/** Итог добавления файлов в тайник. */
data class ImportSummary(
    /** Новых файлов спрятано. */
    val added: Int,
    /** Уже были в тайнике (не добавлены повторно, но оригинал можно удалять). */
    val duplicates: Int,
    /** Не удалось добавить — оригиналы не трогаем. */
    val failures: List<ImportFailure>,
    /** Оригиналы, которые теперь безопасно удалить (файл точно есть в тайнике). */
    val safeToDelete: List<Uri>,
) {
    val failed: Int get() = failures.size
}

/** Места меньше, чем нужно для зашифрованной копии с запасом. */
class NotEnoughSpaceException : IOException("не хватает места")

/** Добавление файлов в тайник: по одному, с проверкой каждого. */
object Importer {

    /** Миниатюры крупные, чтобы и в две колонки сетка была чёткой. */
    private const val THUMB_SIZE_PX = 512

    /** Сколько места оставлять свободным сверх размера файла: телефону оно тоже нужно. */
    private const val SPACE_RESERVE = 64L * 1024 * 1024

    /**
     * Фото и видео из галереи — в общую сетку или в папку тайника [folderId].
     * [onProgress] вызывается в главном потоке.
     */
    suspend fun importMedia(
        context: Context,
        repository: VaultRepository,
        session: VaultSession,
        items: List<MediaItem>,
        folderId: String? = null,
        onProgress: (done: Int, total: Int) -> Unit,
    ): ImportSummary = importAll(session, items.size, { items[it].name }, onProgress) { index ->
        val item = items[index]
        val result = MediaGallery.openOriginal(context, item.uri).use { file ->
            // Проверяем по настоящему размеру файла; размер из базы галереи — только запасной вариант.
            val size = file.size ?: item.size.takeIf { it > 0 }
            ensureSpace(context, size)
            val thumb = MediaGallery.systemThumbnail(context, item, THUMB_SIZE_PX)?.let(MediaGallery::toJpeg)
            val meta = ImportMeta(
                name = item.name,
                mime = item.mime,
                size = size,
                takenAt = item.takenAt,
                durationMs = item.durationMs,
                origFolder = item.relativePath,
            )
            repository.importFile(session, file.input, meta, thumb, folderId = folderId)
        }
        result to item.uri
    }

    /** Документы из системного окна выбора. [onProgress] вызывается в главном потоке. */
    suspend fun importDocuments(
        context: Context,
        repository: VaultRepository,
        session: VaultSession,
        uris: List<Uri>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): ImportSummary {
        val docs = arrayOfNulls<DocumentInfo>(uris.size)
        return importAll(session, uris.size, { docs[it]?.name ?: uris[it].lastPathSegment.orEmpty() }, onProgress) { index ->
            val doc = MediaGallery.describeDocument(context, uris[index])
            docs[index] = doc
            val result = MediaGallery.openDocument(context, doc.uri).use { file ->
                val size = file.size ?: doc.size.takeIf { it > 0 }
                ensureSpace(context, size)
                val thumb = MediaGallery.documentThumbnail(context, doc, THUMB_SIZE_PX)?.let(MediaGallery::toJpeg)
                val meta = ImportMeta(name = doc.name, mime = doc.mime, size = size)
                repository.importFile(session, file.input, meta, thumb)
            }
            result to doc.uri
        }
    }

    /** Хватит ли места на зашифрованную копию файла размером [size] (с запасом). */
    private fun ensureSpace(context: Context, size: Long?) {
        if (size == null) return
        val free = context.filesDir.usableSpace
        if (free < size + SPACE_RESERVE) throw NotEnoughSpaceException()
    }

    /**
     * Удаляет исходные документы (после того как они надёжно в тайнике).
     * Возвращает, сколько удалить не удалось (источник может не позволять удаление).
     */
    suspend fun deleteDocuments(context: Context, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var failed = 0
        for (uri in uris) {
            val ok = try {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            } catch (e: Exception) {
                false
            }
            if (!ok) failed++
        }
        failed
    }

    /**
     * Общий цикл: обрабатывает файлы по одному в фоне. Сбой одного файла не
     * останавливает остальные; если тайник закрыли — цикл останавливается.
     */
    private suspend fun importAll(
        session: VaultSession,
        total: Int,
        nameOf: (Int) -> String,
        onProgress: (Int, Int) -> Unit,
        one: (Int) -> Pair<ImportResult, Uri>,
    ): ImportSummary = withContext(Dispatchers.IO) {
        var added = 0
        var duplicates = 0
        val failures = ArrayList<ImportFailure>()
        val safe = ArrayList<Uri>()
        for (i in 0 until total) {
            ensureActive()
            if (!session.isOpen) break
            try {
                val (result, uri) = one(i)
                when (result) {
                    is ImportResult.Added -> added++
                    is ImportResult.AlreadyThere -> duplicates++
                }
                safe += uri
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Тайник закрыли посреди работы — дальше не продолжаем.
                if (!session.isOpen) break
                failures += ImportFailure(nameOf(i), problemOf(e), codeOf(e))
            }
            withContext(Dispatchers.Main) { onProgress(i + 1, total) }
        }
        ImportSummary(added, duplicates, failures, safe)
    }

    /** Понятная причина неудачи. */
    private fun problemOf(e: Exception): ImportProblem = when {
        e is NotEnoughSpaceException || causes(e).any { isNoSpace(it) } -> ImportProblem.NO_SPACE
        e is SizeMismatchException -> ImportProblem.CHANGED
        e is VerificationFailedException -> ImportProblem.VERIFY
        e is FileNotFoundException || e is SecurityException -> ImportProblem.OPEN
        else -> ImportProblem.OTHER
    }

    private fun isNoSpace(t: Throwable): Boolean =
        (t is ErrnoException && t.errno == OsConstants.ENOSPC) ||
            t.message?.contains("ENOSPC") == true ||
            t.message?.contains("No space left", ignoreCase = true) == true

    private fun causes(e: Throwable): Sequence<Throwable> = generateSequence(e) { it.cause }.take(8)

    /** Короткий код ошибки, например «IOException» или «EIO», чтобы по нему найти причину. */
    private fun codeOf(e: Exception): String {
        val errno = causes(e).filterIsInstance<ErrnoException>().firstOrNull()
        if (errno != null) return OsConstants.errnoName(errno.errno) ?: "errno ${errno.errno}"
        return causes(e).last().javaClass.simpleName
    }
}
