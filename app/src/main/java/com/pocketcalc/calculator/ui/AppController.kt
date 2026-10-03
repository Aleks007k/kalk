package com.pocketcalc.calculator.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pocketcalc.calculator.vault.VaultRepository
import com.pocketcalc.calculator.vault.VaultSession

/** Какой экран сейчас показан. */
enum class Screen { SETUP, CALCULATOR, VAULT, NEW_PIN }

/**
 * Состояние приложения на уровне процесса: какой экран открыт и открыт ли тайник.
 * При выходе из приложения тайник запирается ([lock]) — ключ затирается.
 */
object AppController {

    var screen by mutableStateOf(Screen.CALCULATOR)
        private set

    /** Открытый тайник или null. */
    var session: VaultSession? = null
        private set

    /** Приложение на экране (между onStart и onStop). */
    @Volatile
    var isForeground: Boolean = false

    private var initialized = false

    /** Выбирает первый экран один раз за жизнь процесса. */
    fun initOnce(repository: VaultRepository) {
        if (initialized) return
        initialized = true
        screen = if (repository.state() == VaultRepository.State.READY) {
            Screen.CALCULATOR
        } else {
            Screen.SETUP
        }
    }

    /** Тайник открыт. Если вход был по коду восстановления — сначала новый PIN. */
    fun onUnlocked(newSession: VaultSession, viaRecovery: Boolean) {
        if (!isForeground) {
            // Пока шла проверка, пользователь ушёл из приложения — не открываем.
            newSession.close()
            return
        }
        session?.close()
        session = newSession
        screen = if (viaRecovery) Screen.NEW_PIN else Screen.VAULT
    }

    fun onSetupFinished() {
        screen = Screen.CALCULATOR
    }

    fun onPinChanged() {
        if (session != null) screen = Screen.VAULT
    }

    /** Запереть тайник (затереть ключ) и вернуться к калькулятору. */
    fun lock() {
        session?.close()
        session = null
        if (screen == Screen.VAULT || screen == Screen.NEW_PIN) {
            screen = Screen.CALCULATOR
        }
    }
}
