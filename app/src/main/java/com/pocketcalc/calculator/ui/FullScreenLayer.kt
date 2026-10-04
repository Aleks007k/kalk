package com.pocketcalc.calculator.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Экран поверх другого экрана (настройки, редактор заметки). Касания не
 * проходят сквозь него: без этого нажатие на пустое место верхнего экрана
 * сработало бы на кнопке, которая лежит под ним.
 */
@Composable
internal fun FullScreenLayer(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent()
                }
            },
    ) {
        content()
    }
}
