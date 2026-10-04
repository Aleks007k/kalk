package com.pocketcalc.calculator.media

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.pocketcalc.calculator.vault.ImportMeta
import com.pocketcalc.calculator.vault.ImportResult
import com.pocketcalc.calculator.vault.VaultRepository
import com.pocketcalc.calculator.vault.VaultSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

/** Итог добавления файлов в тайник. */
data class ImportSummary(
    /** Новых файлов спрятано. */
    val added: Int,
    /** Уже были в тайнике (не добавлены повторно, но оригинал можно удалять). */
    val duplicates: Int,
    /** Не удалось добавить — оригиналы не трогаем. */
    val failed: Int,
    /** Оригиналы, которые теперь безопасно удалить (файл точно есть в тайнике). */
    val safeToDelete: List<Uri>,
)

/** Добавление файлов в тайник: по одному, с проверкой каждого. */
object Importer {

    /** Миниатюры крупные, чтобы и в две колонки сетка была чёткой. */
    private const val THUMB_SIZE_PX = 512

    /** Фото и видео из галереи. [onProgress] вызывается в главном потоке. */
    suspend fun importMedia(
        context: Context,
        repository: VaultRepository,
        session: VaultSession,
        items: List<MediaItem>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): ImportSummary = importAll(session, items.size, onProgress) { index ->
        val item = items[index]
        val thumb = MediaGallery.systemThumbnail(context, item, THUMB_SIZE_PX)?.let(MediaGallery::toJpeg)
        val meta = ImportMeta(
            name = item.name,
            mime = item.mime,
            size = item.size.takeIf { it > 0 },
            takenAt = item.takenAt,
            durationMs = item.durationMs,
            origFolder = item.relativePath,
        )
        val result = MediaGallery.openOriginal(context, item.uri)?.use { input ->
            repository.importFile(session, input, meta, thumb)
        } ?: throw IOException("не удалось открыть файл")
        result to item.uri
    }

    /** Документы из системного окна выбора. [onProgress] вызывается в главном потоке. */
    suspend fun importDocuments(
        context: Context,
        repository: VaultRepository,
        session: VaultSession,
        uris: List<Uri>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): ImportSummary = importAll(session, uris.size, onProgress) { index ->
        val doc = MediaGallery.describeDocument(context, uris[index])
        val thumb = MediaGallery.documentThumbnail(context, doc, THUMB_SIZE_PX)?.let(MediaGallery::toJpeg)
        val meta = ImportMeta(
            name = doc.name,
            mime = doc.mime,
            size = doc.size.takeIf { it > 0 },
        )
        val result = context.contentResolver.openInputStream(doc.uri)?.use { input ->
            repository.importFile(session, input, meta, thumb)
        } ?: throw IOException("не удалось открыть файл")
        result to doc.uri
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
        onProgress: (Int, Int) -> Unit,
        one: (Int) -> Pair<ImportResult, Uri>,
    ): ImportSummary = withContext(Dispatchers.IO) {
        var added = 0
        var duplicates = 0
        var failed = 0
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
                failed++
            }
            withContext(Dispatchers.Main) { onProgress(i + 1, total) }
        }
        ImportSummary(added, duplicates, failed, safe)
    }
}
