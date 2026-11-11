package com.pocketcalc.calculator.media

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.BaseColumns
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FileNotFoundException
import java.io.InputStream

/** Фото или видео из памяти телефона (галерея). */
data class MediaItem(
    val uri: Uri,
    val id: Long,
    val name: String,
    val mime: String,
    val size: Long,
    val takenAt: Long,
    val durationMs: Long,
    val isVideo: Boolean,
    /** Папка в памяти телефона, например "DCIM/Camera/" (Android 10+), иначе пусто. */
    val relativePath: String = "",
)

/**
 * Открытый для чтения файл и его настоящий размер, узнанный по самому
 * открытому файлу (null — система размер не сообщила).
 */
class OpenedFile(val input: InputStream, val size: Long?) : Closeable {
    override fun close() = input.close()
}

/** Что узнали о файле с картинкой: настоящий тип, кадр для миниатюры, длительность. */
class VideoProbe(val mime: String, val frame: Bitmap?, val durationMs: Long)

/** Документ, выбранный в системном окне выбора файлов. */
data class DocumentInfo(
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long,
)

/** Доступ к фото и видео телефона и к миниатюрам. */
object MediaGallery {

    private const val COLUMN_DATE_TAKEN = "datetaken"
    private const val COLUMN_DURATION = "duration"

    /**
     * Разрешения, которые нужно запросить для доступа к фото и видео.
     * ACCESS_MEDIA_LOCATION просим вместе с остальными (одно окно): без него
     * Android отдаёт фото и видео с вырезанной геометкой.
     */
    fun permissionsToRequest(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Есть ли хоть какой-то доступ к фото и видео (полный или к выбранным). */
    fun hasAccess(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            granted(context, Manifest.permission.READ_MEDIA_IMAGES) ||
                granted(context, Manifest.permission.READ_MEDIA_VIDEO) ||
                isPartialAccess(context)
        else -> granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Доступ только к выбранным фото (Android 14+, «Выбрать фото и видео»). */
    fun isPartialAccess(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !granted(context, Manifest.permission.READ_MEDIA_IMAGES) &&
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Все доступные фото и видео, новые сверху. Вызывать не в главном потоке. */
    fun load(context: Context): List<MediaItem> {
        val images = query(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false)
        val videos = query(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true)
        return (images + videos).sortedByDescending { it.takenAt }
    }

    private fun query(context: Context, collection: Uri, isVideo: Boolean): List<MediaItem> {
        val projection = mutableListOf(
            BaseColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            COLUMN_DATE_TAKEN,
        )
        if (isVideo) projection += COLUMN_DURATION
        val withPath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        if (withPath) projection += MediaStore.MediaColumns.RELATIVE_PATH
        val result = ArrayList<MediaItem>()
        try {
            context.contentResolver.query(collection, projection.toTypedArray(), null, null, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(BaseColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val addedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val takenCol = c.getColumnIndex(COLUMN_DATE_TAKEN)
                val durCol = if (isVideo) c.getColumnIndex(COLUMN_DURATION) else -1
                val pathCol = if (withPath) c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val added = c.getLong(addedCol) * 1000
                    val taken = if (takenCol >= 0 && !c.isNull(takenCol)) c.getLong(takenCol) else 0L
                    result += MediaItem(
                        uri = ContentUris.withAppendedId(collection, id),
                        id = id,
                        name = c.getString(nameCol) ?: "file_$id",
                        mime = c.getString(mimeCol) ?: if (isVideo) "video/mp4" else "image/jpeg",
                        size = if (c.isNull(sizeCol)) 0L else c.getLong(sizeCol),
                        takenAt = if (taken > 0) taken else added,
                        durationMs = if (durCol >= 0 && !c.isNull(durCol)) c.getLong(durCol) else 0L,
                        isVideo = isVideo,
                        relativePath = if (pathCol >= 0 && !c.isNull(pathCol)) c.getString(pathCol) else "",
                    )
                }
            }
        } catch (e: SecurityException) {
            // Доступа нет — просто пустой список.
        }
        return result
    }

    /**
     * Открывает фото или видео для чтения в точности как оно лежит в памяти.
     *
     * Без особого запроса Android вырезает из файла геометку (место съёмки),
     * когда его читает другое приложение. С разрешением ACCESS_MEDIA_LOCATION
     * и [MediaStore.setRequireOriginal] система отдаёт файл без изменений.
     * Если так открыть не вышло — читаем как обычно (файл без геометки),
     * чтобы добавление всё равно работало.
     *
     * Размер берётся у самого открытого файла. Размер из базы галереи для
     * проверки не годится: после правки снимка или его доработки камерой
     * он бывает устаревшим, и исправные файлы ошибочно не добавлялись.
     */
    fun openOriginal(context: Context, uri: Uri): OpenedFile {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            granted(context, Manifest.permission.ACCESS_MEDIA_LOCATION)
        ) {
            try {
                return openWithSize(context.contentResolver, MediaStore.setRequireOriginal(uri))
            } catch (e: Exception) {
                // ниже — обычное чтение
            }
        }
        return openWithSize(context.contentResolver, uri)
    }

    /** Открывает документ из системного окна выбора; размер — как у [openOriginal]. */
    fun openDocument(context: Context, uri: Uri): OpenedFile = openWithSize(context.contentResolver, uri)

    private fun openWithSize(resolver: ContentResolver, uri: Uri): OpenedFile {
        val afd = resolver.openAssetFileDescriptor(uri, "r") ?: throw FileNotFoundException("не удалось открыть файл")
        // Длина, которую сообщает сам открытый файл; -1 — неизвестна (например, данные идут потоком).
        val length = afd.length
        return OpenedFile(afd.createInputStream(), length.takeIf { it >= 0 })
    }

    /** Миниатюра фото/видео из системы (для сетки выбора и для тайника). */
    fun systemThumbnail(context: Context, item: MediaItem, sizePx: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(item.uri, Size(sizePx, sizePx), null)
        } else {
            @Suppress("DEPRECATION")
            if (item.isVideo) {
                MediaStore.Video.Thumbnails.getThumbnail(
                    context.contentResolver, item.id, MediaStore.Video.Thumbnails.MINI_KIND, null,
                )
            } else {
                MediaStore.Images.Thumbnails.getThumbnail(
                    context.contentResolver, item.id, MediaStore.Images.Thumbnails.MINI_KIND, null,
                )
            }
        }
    } catch (e: Exception) {
        null
    }

    /** Миниатюра документа: первая страница PDF, кадр фото/видео; иначе null. */
    fun documentThumbnail(context: Context, doc: DocumentInfo, sizePx: Int): Bitmap? {
        if (doc.mime == "application/pdf") return pdfThumbnail(context, doc.uri, sizePx)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (!doc.mime.startsWith("image/") && !doc.mime.startsWith("video/")) return null
        return try {
            context.contentResolver.loadThumbnail(doc.uri, Size(sizePx, sizePx), null)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Есть ли в файле картинка (дорожка видео). Бывает, что видео сохранено
     * под «звуковым» именем, например «.aac», и Android по имени считает его
     * звуком; такой файл добавляем как видео. Заодно — кадр для миниатюры и
     * длительность. null — картинки нет или узнать не удалось.
     */
    fun probeVideo(context: Context, uri: Uri, sizePx: Int): VideoProbe? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") return null
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                ?.takeIf { it.startsWith("video/") } ?: "video/mp4"
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val frame = try {
                scaledFrame(retriever, sizePx)
            } catch (e: Exception) {
                null
            }
            VideoProbe(mime, frame, duration)
        } catch (e: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // уже не важно
            }
        }
    }

    /** Кадр около первой секунды, не крупнее [sizePx] по большей стороне. */
    private fun scaledFrame(retriever: MediaMetadataRetriever, sizePx: Int): Bitmap? {
        val timeUs = 1_000_000L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            return retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, sizePx, sizePx)
        }
        val full = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        val scale = sizePx.toFloat() / maxOf(full.width, full.height)
        if (scale >= 1f) return full
        return Bitmap.createScaledBitmap(
            full,
            (full.width * scale).toInt().coerceAtLeast(1),
            (full.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    /** Первая страница PDF на белом фоне (защищённые паролем PDF — null). */
    private fun pdfThumbnail(context: Context, uri: Uri, sizePx: Int): Bitmap? {
        val descriptor = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            null
        } ?: return null
        val renderer = try {
            PdfRenderer(descriptor)
        } catch (e: Exception) {
            descriptor.close()
            return null
        }
        return try {
            if (renderer.pageCount == 0) return null
            val page = renderer.openPage(0)
            try {
                val scale = sizePx.toFloat() / maxOf(page.width, page.height)
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } finally {
                page.close()
            }
        } catch (e: Exception) {
            null
        } finally {
            renderer.close()
        }
    }

    /** Сжимает миниатюру в JPEG для хранения в тайнике. */
    fun toJpeg(bitmap: Bitmap): ByteArray {
        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, bos)
        return bos.toByteArray()
    }

    /** Имя, тип и размер документа из системного окна выбора. */
    fun describeDocument(context: Context, uri: Uri): DocumentInfo {
        var name = "file"
        var size = -1L
        try {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (e: Exception) {
            // оставляем значения по умолчанию
        }
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        return DocumentInfo(uri, name, mime, size)
    }
}
