package com.pocketcalc.calculator.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer

/** Картинка, готовая к показу. Размеры — в пикселях. */
sealed interface DecodedImage {
    val width: Int
    val height: Int

    class Still(val bitmap: Bitmap) : DecodedImage {
        override val width: Int get() = bitmap.width
        override val height: Int get() = bitmap.height
    }

    /** Анимированный GIF или WebP. */
    class Animated(val drawable: Drawable) : DecodedImage {
        override val width: Int get() = drawable.intrinsicWidth
        override val height: Int get() = drawable.intrinsicHeight
    }
}

/**
 * Превращает расшифрованные байты фото в картинку для экрана. Обычные снимки
 * (до ~16 Мп) показываются в полном разрешении; огромные (50–108 Мп)
 * уменьшаются в 2–4 раза, чтобы не переполнить память.
 */
object PhotoDecoder {

    private const val MAX_PIXELS = 16_000_000L

    /** Длинная сторона не больше этого: такие картинки видеокарта рисует без сбоев (панорамы). */
    private const val MAX_SIDE = 8192

    fun decode(bytes: ByteArray, mime: String): DecodedImage =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeModern(bytes, mime)
        } else {
            decodeLegacy(bytes)
        }

    /** Android 9+: HEIC, анимация, поворот по EXIF — всё делает система. */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeModern(bytes: ByteArray, mime: String): DecodedImage {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        val listener = ImageDecoder.OnHeaderDecodedListener { decoder, info, _ ->
            val sample = sampleSizeFor(info.size.width, info.size.height)
            if (sample > 1) decoder.setTargetSampleSize(sample)
        }
        if (mime == "image/gif" || mime == "image/webp") {
            when (val drawable = ImageDecoder.decodeDrawable(source, listener)) {
                is AnimatedImageDrawable -> return DecodedImage.Animated(drawable)
                is BitmapDrawable -> return DecodedImage.Still(drawable.bitmap)
                else -> Unit
            }
        }
        return DecodedImage.Still(ImageDecoder.decodeBitmap(source, listener))
    }

    /** Android 8: BitmapFactory и поворот по EXIF вручную. */
    private fun decodeLegacy(bytes: ByteArray): DecodedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("не удалось прочитать картинку")
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IOException("не удалось прочитать картинку")
        val orientation = try {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        return DecodedImage.Still(applyOrientation(bitmap, orientation))
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /** Во сколько раз уменьшить (степень двойки), чтобы уложиться в [MAX_PIXELS] и [MAX_SIDE]. */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while ((width.toLong() / sample) * (height.toLong() / sample) > MAX_PIXELS ||
            maxOf(width, height) / sample > MAX_SIDE
        ) {
            sample *= 2
        }
        return sample
    }
}
