package com.pocketcalc.calculator.ui

import android.util.LruCache
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** Насколько развести или свести пальцы, чтобы сетка сменилась на одну колонку. */
private const val PINCH_STEP = 1.2f

/**
 * Щипок двумя пальцами, как в Галерее: развести пальцы — плитки крупнее
 * (колонок меньше), свести — мельче (колонок больше). Один щипок — одна
 * колонка, чтобы размер не «прыгал». Прокрутка и нажатия одним пальцем
 * работают как обычно. [onStep] получает −1 или +1 колонку.
 */
@Composable
fun Modifier.pinchToChangeColumns(onStep: (Int) -> Unit): Modifier {
    val currentOnStep by rememberUpdatedState(onStep)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var scale = 1f
            var stepped = false
            do {
                // Смотрим касания раньше сетки: пока на экране два пальца,
                // забираем их себе, чтобы сетка не прокручивалась и не
                // срабатывало нажатие на плитку.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    event.changes.forEach { it.consume() }
                    if (!stepped) {
                        scale *= event.calculateZoom()
                        if (scale > PINCH_STEP) {
                            currentOnStep(-1)
                            stepped = true
                        } else if (scale < 1f / PINCH_STEP) {
                            currentOnStep(+1)
                            stepped = true
                        }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}

/** Кэш миниатюр, ограниченный объёмом памяти (крупные плитки весят больше). */
fun thumbnailCache(maxBytes: Int): LruCache<String, ImageBitmap> =
    object : LruCache<String, ImageBitmap>(maxBytes) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }
