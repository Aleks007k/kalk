package com.pocketcalc.calculator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketcalc.calculator.R
import com.pocketcalc.calculator.ui.theme.CalcPalette
import com.pocketcalc.calculator.vault.PinProblem
import com.pocketcalc.calculator.vault.SecretInput

/**
 * Каркас шага настройки: заголовок, подсказка, содержимое по центру
 * и нижняя часть (клавиатура, кнопки).
 */
@Composable
fun StepLayout(
    title: String,
    palette: CalcPalette,
    hint: String? = null,
    bottom: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            text = title,
            color = palette.displayPrimary,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (hint != null) {
            Spacer(Modifier.height(8.dp))
            Text(text = hint, color = palette.displaySecondary, fontSize = 16.sp)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
        bottom()
    }
}

/** Цифровая клавиатура 0–9 и «стереть». */
@Composable
fun DigitPad(
    palette: CalcPalette,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        for (row in listOf("123", "456", "789")) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (d in row) {
                    PadKey(d.toString(), palette.digitBg, palette.digitText, Modifier.weight(1f)) {
                        onDigit(d)
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            PadKey("0", palette.digitBg, palette.digitText, Modifier.weight(1f)) { onDigit('0') }
            PadKey("⌫", palette.funcBg, palette.funcText, Modifier.weight(1f), onClick = onBackspace)
        }
    }
}

@Composable
private fun PadKey(
    label: String,
    bg: Color,
    fg: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(64.dp)
            .padding(5.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = fg, fontSize = 26.sp)
    }
}

/** Основная кнопка в цветах калькулятора. */
@Composable
fun PrimaryButton(
    text: String,
    palette: CalcPalette,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.opBg,
            contentColor = palette.opText,
        ),
    ) {
        Text(text = text, fontSize = 17.sp)
    }
}

/**
 * Создание PIN в два шага: ввод (с проверкой правил) и повтор.
 * Когда PIN подтверждён, вызывается [onPinChosen].
 */
@Composable
fun PinCreator(
    title: String,
    palette: CalcPalette,
    onPinChosen: (String) -> Unit,
) {
    var first by remember { mutableStateOf<String?>(null) }
    var entry by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val confirming = first != null

    val errTooShort = stringResource(R.string.pin_err_too_short)
    val errTooLong = stringResource(R.string.pin_err_too_long)
    val errLeadingZero = stringResource(R.string.pin_err_leading_zero)
    val errAllSame = stringResource(R.string.pin_err_all_same)
    val errSequence = stringResource(R.string.pin_err_sequence)
    val errMismatch = stringResource(R.string.pin_err_mismatch)

    fun problemText(p: PinProblem): String = when (p) {
        PinProblem.TOO_SHORT, PinProblem.NOT_DIGITS -> errTooShort
        PinProblem.TOO_LONG -> errTooLong
        PinProblem.LEADING_ZERO -> errLeadingZero
        PinProblem.ALL_SAME -> errAllSame
        PinProblem.SEQUENCE -> errSequence
    }

    StepLayout(
        title = if (confirming) stringResource(R.string.setup_pin_confirm_title) else title,
        palette = palette,
        hint = stringResource(R.string.setup_pin_hint),
        bottom = {
            DigitPad(
                palette = palette,
                onDigit = { d ->
                    if (entry.length < SecretInput.PIN_MAX) {
                        entry += d
                        error = null
                    }
                },
                onBackspace = { entry = entry.dropLast(1) },
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (confirming) {
                    TextButton(onClick = {
                        first = null
                        entry = ""
                        error = null
                    }) {
                        Text(text = stringResource(R.string.setup_back), color = palette.displaySecondary)
                    }
                }
                Spacer(Modifier.weight(1f))
                PrimaryButton(
                    text = stringResource(R.string.setup_next),
                    palette = palette,
                    enabled = entry.length >= SecretInput.PIN_MIN,
                ) {
                    val firstPin = first
                    if (firstPin == null) {
                        val problem = SecretInput.checkPin(entry)
                        if (problem != null) {
                            error = problemText(problem)
                        } else {
                            first = entry
                        }
                    } else if (entry == firstPin) {
                        onPinChosen(entry)
                    } else {
                        error = errMismatch
                        first = null
                    }
                    entry = ""
                }
            }
        },
    ) {
        Spacer(Modifier.height(24.dp))
        // Точки вместо цифр: PIN не видно через плечо.
        Text(
            text = if (entry.isEmpty()) " " else "●".repeat(entry.length),
            color = palette.displayPrimary,
            fontSize = 32.sp,
            letterSpacing = 6.sp,
        )
        val err = error
        if (err != null) {
            Text(text = err, color = palette.error, fontSize = 15.sp, textAlign = TextAlign.Center)
        }
    }
}
