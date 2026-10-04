package com.pocketcalc.calculator.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketcalc.calculator.BuildConfig
import com.pocketcalc.calculator.CalcApp
import com.pocketcalc.calculator.R
import com.pocketcalc.calculator.ui.theme.CalcPalette
import com.pocketcalc.calculator.vault.PinInUseException
import com.pocketcalc.calculator.vault.SecretInput
import com.pocketcalc.calculator.vault.VaultSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SettingsStep {
    MENU, CHANGE_PIN, NEW_CODE_SHOW, NEW_CODE_CONFIRM, DECOY_INTRO, DECOY_PIN, WORKING,
}

/** Чем закончилась операция с PIN или кодом. */
internal enum class OpResult { OK, PIN_IN_USE, FAILED }

/** Что знаем о тайнике для меню настроек. */
private data class VaultInfo(val isDecoy: Boolean, val hasDecoy: Boolean)

/**
 * Настройки внутри тайника: смена PIN, новый код восстановления и (только в
 * настоящем тайнике) фальшивый PIN. В фальшивом тайнике последнего пункта
 * нет: тот, кому его открыли, не узнает о втором тайнике и не сможет
 * через настройки его стереть.
 */
@Composable
fun SettingsScreen(
    app: CalcApp,
    session: VaultSession,
    palette: CalcPalette,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(SettingsStep.MENU) }
    // Каждое изменение перечитывает сведения о тайнике.
    var version by remember { mutableIntStateOf(0) }
    val info by produceState<VaultInfo?>(null, version) {
        value = withContext(Dispatchers.IO) {
            try {
                VaultInfo(app.repository.isDecoy(session), app.repository.hasDecoy(session))
            } catch (e: Exception) {
                null
            }
        }
    }
    var newCode by remember { mutableStateOf("") }
    var askRemoveDecoy by remember { mutableStateOf(false) }

    val msgPinChanged = stringResource(R.string.pin_changed)
    val msgPinInUse = stringResource(R.string.pin_in_use)
    val msgDecoyIsReal = stringResource(R.string.decoy_is_real_pin)
    val msgFailed = stringResource(R.string.settings_failed)
    val msgCodeChanged = stringResource(R.string.code_changed)
    val msgDecoyDone = stringResource(R.string.decoy_done)
    val msgDecoyRemoved = stringResource(R.string.decoy_removed)

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    /**
     * Долгая операция (проверка PIN — около секунды) в фоне; на экране — «Сохраняю…».
     * [onResult] получает итог в главном потоке.
     */
    fun runOperation(work: () -> Unit, onResult: (OpResult) -> Unit) {
        step = SettingsStep.WORKING
        scope.launch {
            val result = try {
                withContext(Dispatchers.Default) { work() }
                OpResult.OK
            } catch (e: CancellationException) {
                throw e
            } catch (e: PinInUseException) {
                OpResult.PIN_IN_USE
            } catch (e: Exception) {
                OpResult.FAILED
            }
            version++
            onResult(result)
        }
    }

    BackHandler {
        when (step) {
            SettingsStep.MENU -> onClose()
            SettingsStep.WORKING -> Unit
            else -> step = SettingsStep.MENU
        }
    }

    when (step) {
        SettingsStep.MENU -> SettingsMenu(
            palette = palette,
            info = info,
            versionName = BuildConfig.VERSION_NAME,
            onBack = onClose,
            onChangePin = { step = SettingsStep.CHANGE_PIN },
            onNewCode = {
                newCode = SecretInput.generateRecoveryCode()
                step = SettingsStep.NEW_CODE_SHOW
            },
            onDecoy = { step = SettingsStep.DECOY_INTRO },
        )

        SettingsStep.CHANGE_PIN -> PinCreator(
            title = stringResource(R.string.newpin_title),
            palette = palette,
            onPinChosen = { pin ->
                runOperation(
                    work = {
                        val p = pin.toCharArray()
                        try {
                            app.repository.changePin(session, p, app.gate)
                        } finally {
                            p.fill('0')
                        }
                    },
                    onResult = { result ->
                        when (result) {
                            OpResult.OK -> {
                                toast(msgPinChanged)
                                step = SettingsStep.MENU
                            }
                            OpResult.PIN_IN_USE -> {
                                toast(msgPinInUse)
                                step = SettingsStep.CHANGE_PIN
                            }
                            OpResult.FAILED -> {
                                toast(msgFailed)
                                step = SettingsStep.MENU
                            }
                        }
                    },
                )
            },
        )

        SettingsStep.NEW_CODE_SHOW -> RecoveryCodeShow(
            code = newCode,
            palette = palette,
            onWritten = { step = SettingsStep.NEW_CODE_CONFIRM },
        )

        SettingsStep.NEW_CODE_CONFIRM -> RecoveryCodeConfirm(
            code = newCode,
            palette = palette,
            onShowAgain = { step = SettingsStep.NEW_CODE_SHOW },
            onConfirmed = {
                runOperation(
                    work = {
                        val c = newCode.toCharArray()
                        try {
                            app.repository.changeRecovery(session, c)
                        } finally {
                            c.fill('0')
                        }
                    },
                    onResult = { result ->
                        toast(if (result == OpResult.OK) msgCodeChanged else msgFailed)
                        newCode = ""
                        step = SettingsStep.MENU
                    },
                )
            },
        )

        SettingsStep.DECOY_INTRO -> DecoyIntro(
            palette = palette,
            hasDecoy = info?.hasDecoy == true,
            onSet = { step = SettingsStep.DECOY_PIN },
            onRemove = { askRemoveDecoy = true },
        )

        SettingsStep.DECOY_PIN -> PinCreator(
            title = stringResource(R.string.decoy_pin_title),
            palette = palette,
            onPinChosen = { pin ->
                runOperation(
                    work = {
                        val p = pin.toCharArray()
                        try {
                            app.repository.setDecoyPin(session, p, app.gate)
                        } finally {
                            p.fill('0')
                        }
                    },
                    onResult = { result ->
                        when (result) {
                            OpResult.OK -> {
                                toast(msgDecoyDone)
                                step = SettingsStep.MENU
                            }
                            OpResult.PIN_IN_USE -> {
                                toast(msgDecoyIsReal)
                                step = SettingsStep.DECOY_PIN
                            }
                            OpResult.FAILED -> {
                                toast(msgFailed)
                                step = SettingsStep.MENU
                            }
                        }
                    },
                )
            },
        )

        SettingsStep.WORKING -> StepLayout(
            title = stringResource(R.string.newpin_saving),
            palette = palette,
        ) {
            Spacer(Modifier.height(48.dp))
            CircularProgressIndicator(color = palette.opBg)
        }
    }

    if (askRemoveDecoy) {
        AlertDialog(
            onDismissRequest = { askRemoveDecoy = false },
            title = { Text(text = stringResource(R.string.decoy_remove_confirm_title)) },
            text = { Text(text = stringResource(R.string.decoy_remove_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    askRemoveDecoy = false
                    runOperation(
                        work = { app.repository.removeDecoyPin(session) },
                        onResult = { result ->
                            toast(if (result == OpResult.OK) msgDecoyRemoved else msgFailed)
                            step = SettingsStep.MENU
                        },
                    )
                }) {
                    Text(text = stringResource(R.string.decoy_remove), color = palette.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { askRemoveDecoy = false }) {
                    Text(text = stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

@Composable
private fun SettingsMenu(
    palette: CalcPalette,
    info: VaultInfo?,
    versionName: String,
    onBack: () -> Unit,
    onChangePin: () -> Unit,
    onNewCode: () -> Unit,
    onDecoy: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .safeDrawingPadding()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(onClick = onBack, size = 52.dp) {
                BackArrowIcon(modifier = Modifier.size(30.dp), color = palette.displayPrimary)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.settings_title),
                color = palette.displayPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(8.dp))
        SettingsItem(
            title = stringResource(R.string.settings_change_pin),
            subtitle = stringResource(R.string.settings_change_pin_hint),
            palette = palette,
            onClick = onChangePin,
        )
        SettingsItem(
            title = stringResource(R.string.settings_new_code),
            subtitle = stringResource(R.string.settings_new_code_hint),
            palette = palette,
            onClick = onNewCode,
        )
        // Фальшивый PIN — только в настоящем тайнике (и когда сведения уже прочитаны).
        if (info != null && !info.isDecoy) {
            SettingsItem(
                title = stringResource(R.string.settings_decoy),
                subtitle = stringResource(if (info.hasDecoy) R.string.settings_decoy_on else R.string.settings_decoy_off),
                palette = palette,
                onClick = onDecoy,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(R.string.settings_version, versionName),
            color = palette.displaySecondary,
            fontSize = 13.sp,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 12.dp),
        )
    }
}

@Composable
private fun SettingsItem(title: String, subtitle: String, palette: CalcPalette, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(text = title, color = palette.displayPrimary, fontSize = 18.sp)
        Spacer(Modifier.height(2.dp))
        Text(text = subtitle, color = palette.displaySecondary, fontSize = 14.sp)
    }
}

@Composable
private fun DecoyIntro(
    palette: CalcPalette,
    hasDecoy: Boolean,
    onSet: () -> Unit,
    onRemove: () -> Unit,
) {
    StepLayout(
        title = stringResource(R.string.settings_decoy),
        palette = palette,
        bottom = {
            PrimaryButton(
                text = stringResource(if (hasDecoy) R.string.decoy_set_new else R.string.decoy_set),
                palette = palette,
                modifier = Modifier.fillMaxWidth(),
                onClick = onSet,
            )
            if (hasDecoy) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.decoy_remove), color = palette.error, fontSize = 16.sp)
                }
            }
        },
    ) {
        Text(
            text = stringResource(R.string.decoy_text),
            color = palette.displayPrimary,
            fontSize = 17.sp,
        )
        if (hasDecoy) {
            Text(
                text = stringResource(R.string.decoy_replace_warning),
                color = palette.error,
                fontSize = 16.sp,
            )
        }
    }
}
