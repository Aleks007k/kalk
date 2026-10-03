package com.pocketcalc.calculator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketcalc.calculator.CalculatorController
import com.pocketcalc.calculator.CalculatorEngine
import com.pocketcalc.calculator.EvalResult
import com.pocketcalc.calculator.Key
import com.pocketcalc.calculator.R
import com.pocketcalc.calculator.ui.theme.CalcPalette
import com.pocketcalc.calculator.ui.theme.calcPalette

private enum class Kind { DIGIT, FUNC, OP, EQUALS }

private data class Btn(val label: String, val key: Key, val kind: Kind)

private val LAYOUT: List<List<Btn>> = listOf(
    listOf(
        Btn("AC", Key.CLEAR, Kind.FUNC),
        Btn("⌫", Key.BACKSPACE, Kind.FUNC),
        Btn("%", Key.PERCENT, Kind.FUNC),
        Btn("÷", Key.DIVIDE, Kind.OP),
    ),
    listOf(
        Btn("7", Key.D7, Kind.DIGIT),
        Btn("8", Key.D8, Kind.DIGIT),
        Btn("9", Key.D9, Kind.DIGIT),
        Btn("×", Key.TIMES, Kind.OP),
    ),
    listOf(
        Btn("4", Key.D4, Kind.DIGIT),
        Btn("5", Key.D5, Kind.DIGIT),
        Btn("6", Key.D6, Kind.DIGIT),
        Btn("−", Key.MINUS, Kind.OP),
    ),
    listOf(
        Btn("1", Key.D1, Kind.DIGIT),
        Btn("2", Key.D2, Kind.DIGIT),
        Btn("3", Key.D3, Kind.DIGIT),
        Btn("+", Key.PLUS, Kind.OP),
    ),
    listOf(
        Btn("±", Key.SIGN, Kind.FUNC),
        Btn("0", Key.D0, Kind.DIGIT),
        Btn(".", Key.DOT, Kind.DIGIT),
        Btn("=", Key.EQUALS, Kind.EQUALS),
    ),
)

@Composable
fun CalculatorScreen(onEquals: (String) -> Unit = {}) {
    val palette = calcPalette(isSystemInDarkTheme())
    val controller = remember { CalculatorController() }

    var topLine by remember { mutableStateOf("") }
    var bigLine by remember { mutableStateOf("0") }
    var isError by remember { mutableStateOf(false) }

    val errGeneric = stringResource(R.string.error_generic)
    val errDivZero = stringResource(R.string.error_div_zero)

    fun onKey(key: Key) {
        if (key == Key.EQUALS) {
            // Набранное до вычисления — на проверку (PIN / код восстановления).
            onEquals(controller.rawExpression)
            val before = controller.state().display
            val evaluated = CalculatorEngine.evaluate(controller.rawExpression)
            val ns = controller.press(Key.EQUALS)
            when {
                ns.evaluated -> {
                    topLine = before
                    bigLine = ns.display
                    isError = false
                }
                controller.rawExpression.isEmpty() -> {
                    topLine = ""; bigLine = "0"; isError = false
                }
                else -> {
                    topLine = before
                    bigLine = if (evaluated is EvalResult.DivByZero) errDivZero else errGeneric
                    isError = true
                }
            }
            return
        }

        val ns = controller.press(key)
        isError = false
        bigLine = ns.display
        val raw = controller.rawExpression
        // Подсказка результата — только когда есть действие (одно число не подсказываем).
        val hasOperation = raw.drop(1).any { it in "+-*/" } || raw.contains('%')
        topLine = if (!ns.evaluated && raw.isNotEmpty() && hasOperation) {
            val last = raw.last()
            if (last.isDigit() || last == '%' || last == ')') {
                (CalculatorEngine.evaluate(raw) as? EvalResult.Ok)
                    ?.let { "= " + controller.format(it.value) } ?: ""
            } else ""
        } else ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .safeDrawingPadding()
            .padding(horizontal = 12.dp),
    ) {
        Display(
            top = topLine,
            big = bigLine,
            isError = isError,
            palette = palette,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        LAYOUT.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { btn ->
                    CalcButton(
                        btn = btn,
                        palette = palette,
                        modifier = Modifier.weight(1f),
                        onClick = { onKey(btn.key) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Display(
    top: String,
    big: String,
    isError: Boolean,
    palette: CalcPalette,
    modifier: Modifier = Modifier,
) {
    val topScroll = rememberScrollState()
    val bigScroll = rememberScrollState()
    LaunchedEffect(top) { topScroll.scrollTo(topScroll.maxValue) }
    LaunchedEffect(big) { bigScroll.scrollTo(bigScroll.maxValue) }

    Column(
        modifier = modifier.padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.End,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(topScroll),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                text = top,
                color = palette.displaySecondary,
                fontSize = 24.sp,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.End,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(bigScroll)
                .padding(top = 8.dp, bottom = 16.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                text = big,
                color = if (isError) palette.error else palette.displayPrimary,
                fontSize = 56.sp,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun CalcButton(
    btn: Btn,
    palette: CalcPalette,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg: Color
    val fg: Color
    when (btn.kind) {
        Kind.DIGIT -> { bg = palette.digitBg; fg = palette.digitText }
        Kind.FUNC -> { bg = palette.funcBg; fg = palette.funcText }
        Kind.OP -> { bg = palette.opBg; fg = palette.opText }
        Kind.EQUALS -> { bg = palette.equalsBg; fg = palette.equalsText }
    }

    val clickLabel = when (btn.key) {
        Key.CLEAR -> stringResource(R.string.cd_clear)
        Key.BACKSPACE -> stringResource(R.string.cd_backspace)
        else -> null
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(6.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClickLabel = clickLabel, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = btn.label,
            color = fg,
            fontSize = 28.sp,
        )
    }
}
