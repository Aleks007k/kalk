package com.pocketcalc.calculator.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.pocketcalc.calculator.CalcApp
import com.pocketcalc.calculator.vault.SecretCandidate
import com.pocketcalc.calculator.vault.SecretInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Корень приложения: показывает нужный экран и проверяет секреты из калькулятора. */
@Composable
fun AppRoot(app: CalcApp) {
    val scope = rememberCoroutineScope()
    val unlockMutex = remember { Mutex() }

    // Вызывается калькулятором при каждом «=». Если набранное похоже на PIN или
    // код восстановления — проверяем в фоне. Калькулятор при этом считает как обычно,
    // а неверный ввод ничем не выдаёт себя.
    val onEquals: (String) -> Unit = { raw ->
        val candidate = SecretInput.classify(raw)
        if (candidate != null) {
            scope.launch {
                unlockMutex.withLock {
                    val session = withContext(Dispatchers.Default) {
                        val digits = when (candidate) {
                            is SecretCandidate.Pin -> candidate.digits
                            is SecretCandidate.Recovery -> candidate.digits
                        }
                        val secret = digits.toCharArray()
                        try {
                            when (candidate) {
                                is SecretCandidate.Pin -> app.repository.unlockWithPin(secret, app.gate)
                                is SecretCandidate.Recovery -> app.repository.unlockWithRecovery(secret)
                            }
                        } finally {
                            secret.fill('0')
                        }
                    }
                    if (session != null) {
                        AppController.onUnlocked(
                            session,
                            viaRecovery = candidate is SecretCandidate.Recovery,
                        )
                    }
                }
            }
        }
    }

    when (AppController.screen) {
        Screen.SETUP -> SetupScreen(app, onDone = { AppController.onSetupFinished() })
        Screen.CALCULATOR -> CalculatorScreen(onEquals = onEquals)
        Screen.VAULT -> VaultScreen(app, onLock = { AppController.lock() })
        Screen.NEW_PIN -> NewPinScreen(
            app,
            onDone = { AppController.onPinChanged() },
            onCancel = { AppController.lock() },
        )
    }
}
