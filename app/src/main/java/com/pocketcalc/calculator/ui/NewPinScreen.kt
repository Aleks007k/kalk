package com.pocketcalc.calculator.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pocketcalc.calculator.CalcApp
import com.pocketcalc.calculator.R
import com.pocketcalc.calculator.ui.theme.calcPalette
import com.pocketcalc.calculator.vault.PinInUseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Новый PIN после входа по коду восстановления.
 * «Назад» запирает тайник без изменений.
 */
@Composable
fun NewPinScreen(app: CalcApp, onDone: () -> Unit, onCancel: () -> Unit) {
    SecureWindow()
    BackHandler(onBack = onCancel)
    val palette = calcPalette(isSystemInDarkTheme())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val msgPinInUse = stringResource(R.string.pin_in_use)
    var saving by remember { mutableStateOf(false) }

    if (saving) {
        StepLayout(title = stringResource(R.string.newpin_saving), palette = palette) {
            Spacer(Modifier.height(48.dp))
            CircularProgressIndicator(color = palette.opBg)
        }
        return
    }

    PinCreator(
        title = stringResource(R.string.newpin_title),
        palette = palette,
        onPinChosen = { newPin ->
            val session = AppController.session
            if (session == null) {
                onCancel()
            } else {
                saving = true
                scope.launch {
                    val result = withContext(Dispatchers.Default) {
                        val p = newPin.toCharArray()
                        try {
                            app.repository.changePin(session, p, app.gate)
                            OpResult.OK
                        } catch (e: PinInUseException) {
                            OpResult.PIN_IN_USE
                        } catch (e: Exception) {
                            OpResult.FAILED
                        } finally {
                            p.fill('0')
                        }
                    }
                    saving = false
                    when (result) {
                        OpResult.OK -> onDone()
                        // Такой PIN уже открывает другой тайник — просим придумать другой.
                        OpResult.PIN_IN_USE -> Toast.makeText(context, msgPinInUse, Toast.LENGTH_LONG).show()
                        OpResult.FAILED -> onCancel()
                    }
                }
            }
        },
    )
}
