package com.pocketcalc.calculator.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketcalc.calculator.CalcApp
import com.pocketcalc.calculator.R
import com.pocketcalc.calculator.ui.theme.calcPalette
import com.pocketcalc.calculator.vault.SecretInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SetupStep { PIN, SHOW_CODE, CONFIRM_CODE, CREATING, ERROR, DONE }

/**
 * Первичная настройка (один раз): PIN → показ кода восстановления →
 * проверка, что код записан верно → создание тайника → подсказка, как войти.
 */
@Composable
fun SetupScreen(app: CalcApp, onDone: () -> Unit) {
    SecureWindow()
    val palette = calcPalette(isSystemInDarkTheme())
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(SetupStep.PIN) }
    var pin by remember { mutableStateOf("") }
    val code = remember { SecretInput.generateRecoveryCode() }

    fun create() {
        step = SetupStep.CREATING
        scope.launch {
            val ok = withContext(Dispatchers.Default) {
                val p = pin.toCharArray()
                val c = code.toCharArray()
                try {
                    // Сразу закрываем: войти нужно будет через калькулятор.
                    app.repository.setup(p, c, app.gate).close()
                    true
                } catch (e: Exception) {
                    false
                } finally {
                    p.fill('0')
                    c.fill('0')
                }
            }
            step = if (ok) SetupStep.DONE else SetupStep.ERROR
        }
    }

    when (step) {
        SetupStep.PIN -> PinCreator(
            title = stringResource(R.string.setup_pin_title),
            palette = palette,
            onPinChosen = {
                pin = it
                step = SetupStep.SHOW_CODE
            },
        )

        SetupStep.SHOW_CODE -> RecoveryCodeShow(
            code = code,
            palette = palette,
            onWritten = { step = SetupStep.CONFIRM_CODE },
        )

        SetupStep.CONFIRM_CODE -> RecoveryCodeConfirm(
            code = code,
            palette = palette,
            onShowAgain = { step = SetupStep.SHOW_CODE },
            onConfirmed = { create() },
        )

        SetupStep.CREATING -> StepLayout(
            title = stringResource(R.string.setup_creating),
            palette = palette,
        ) {
            Spacer(Modifier.height(48.dp))
            CircularProgressIndicator(color = palette.opBg)
        }

        SetupStep.ERROR -> StepLayout(
            title = stringResource(R.string.setup_error),
            palette = palette,
            bottom = {
                PrimaryButton(
                    text = stringResource(R.string.setup_retry),
                    palette = palette,
                    modifier = Modifier.fillMaxWidth(),
                ) { create() }
            },
        ) {}

        SetupStep.DONE -> StepLayout(
            title = stringResource(R.string.setup_done_title),
            palette = palette,
            bottom = {
                PrimaryButton(
                    text = stringResource(R.string.setup_done_ok),
                    palette = palette,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDone,
                )
            },
        ) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.setup_done_text),
                color = palette.displayPrimary,
                fontSize = 18.sp,
            )
        }
    }
}
