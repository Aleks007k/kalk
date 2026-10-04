package com.pocketcalc.calculator.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Значки просмотра рисуются линиями, а не берутся из шрифта: на телефонах
 * Samsung символы вроде «▶» иногда превращаются в цветные эмодзи.
 */

/** Круглая кнопка размером с палец. */
@Composable
internal fun RoundIconButton(
    onClick: () -> Unit,
    size: Dp = 48.dp,
    background: Color = Color.Transparent,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** Стрелка «назад». */
@Composable
internal fun BackArrowIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val s = size.minDimension
        val stroke = s * 0.1f
        val x0 = (size.width - s) / 2
        val left = x0 + s * 0.16f
        val right = x0 + s * 0.86f
        val y = size.height / 2
        val wing = s * 0.3f
        drawLine(color, Offset(right, y), Offset(left, y), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(left, y), Offset(left + wing, y - wing), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(left, y), Offset(left + wing, y + wing), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

/** Три точки «⋮» — меню действий. */
@Composable
internal fun MoreIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val r = size.minDimension * 0.09f
        val cx = size.width / 2
        for (f in floatArrayOf(0.22f, 0.5f, 0.78f)) {
            drawCircle(color, r, Offset(cx, size.height * f))
        }
    }
}

/** Шестерёнка «настройки». */
@Composable
internal fun GearIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val s = size.minDimension
        val c = center
        val outer = s * 0.47f
        val body = s * 0.34f
        val hole = s * 0.14f
        val toothW = s * 0.16f
        // Восемь зубцов вокруг кольца.
        for (i in 0 until 8) {
            rotate(degrees = i * 45f, pivot = c) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(c.x - toothW / 2, c.y - outer),
                    size = Size(toothW, outer - body + s * 0.05f),
                    cornerRadius = CornerRadius(s * 0.03f),
                )
            }
        }
        drawCircle(color, radius = (body + hole) / 2, center = c, style = Stroke(width = body - hole))
    }
}

/** «Играть» (треугольник) или «пауза» (две полосы). */
@Composable
internal fun PlayPauseIcon(showPause: Boolean, modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (showPause) {
            val barW = w * 0.2f
            val gap = w * 0.16f
            val left = (w - 2 * barW - gap) / 2
            val corner = CornerRadius(barW * 0.3f)
            drawRoundRect(color, Offset(left, h * 0.18f), Size(barW, h * 0.64f), corner)
            drawRoundRect(color, Offset(left + barW + gap, h * 0.18f), Size(barW, h * 0.64f), corner)
        } else {
            val path = Path().apply {
                moveTo(w * 0.3f, h * 0.16f)
                lineTo(w * 0.3f, h * 0.84f)
                lineTo(w * 0.86f, h * 0.5f)
                close()
            }
            drawPath(path, color)
        }
    }
}

/** Круглая стрелка с «10»: перемотка на 10 секунд назад или вперёд. */
@Composable
internal fun Seek10Icon(forward: Boolean, modifier: Modifier = Modifier, color: Color = Color.White) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = size.minDimension
            val stroke = s * 0.08f
            val radius = s * 0.38f
            val center = Offset(size.width / 2, size.height / 2)
            // Дуга почти по кругу; разрыв сверху — туда смотрит стрелка.
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = if (forward) -300f else 300f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // Наконечник в верхней точке: влево — назад, вправо — вперёд.
            val dir = if (forward) 1f else -1f
            val head = s * 0.14f
            val topY = center.y - radius
            val tipX = center.x + dir * head
            val path = Path().apply {
                moveTo(tipX, topY)
                lineTo(tipX - dir * head, topY - head * 0.85f)
                lineTo(tipX - dir * head, topY + head * 0.85f)
                close()
            }
            drawPath(path, color)
        }
        Text(text = "10", color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
