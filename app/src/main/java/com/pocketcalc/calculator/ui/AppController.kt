package com.pocketcalc.calculator.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

    /** Сколько можно провести в системном окне (выбор файлов, удаление) без запирания. */
    private const val EXTERNAL_TIMEOUT_MS = 3 * 60_000L

    var screen by mutableStateOf(Screen.CALCULATOR)
        private set

    /** Открытый тайник или null. */
    var session: VaultSession? = null
        private set

    /** Приложение на экране (между onStart и onStop). */
    @Volatile
    var isForeground: Boolean = false
        private set

    private var initialized = false

    private val handler = Handler(Looper.getMainLooper())

    /** До какого момента уход в системное окно по нашей кнопке не запирает тайник. */
    private var externalDeadline = 0L

    private val lockIfStillAway = Runnable {
        if (!isForeground) lock()
        externalDeadline = 0L
    }

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

    // --- Системные окна, открытые нашей кнопкой ----------------------------

    /** Вызвать прямо перед открытием системного окна (выбор файлов, удаление). */
    fun beginExternalActivity() {
        externalDeadline = SystemClock.elapsedRealtime() + EXTERNAL_TIMEOUT_MS
    }

    /** Вызвать, когда системное окно вернуло результат (или не открылось). */
    fun endExternalActivity() {
        externalDeadline = 0L
        handler.removeCallbacks(lockIfStillAway)
    }

    // --- Жизненный цикл окна ----------------------------------------------

    fun onActivityStart() {
        isForeground = true
        handler.removeCallbacks(lockIfStillAway)
        if (externalDeadline != 0L && SystemClock.elapsedRealtime() > externalDeadline) {
            // Слишком долго были в системном окне — запираем.
            externalDeadline = 0L
            lock()
        }
    }

    fun onActivityStop(changingConfigurations: Boolean) {
        isForeground = false
        if (changingConfigurations) return // поворот экрана не запирает
        val remaining = externalDeadline - SystemClock.elapsedRealtime()
        if (externalDeadline != 0L && remaining > 0) {
            // Ушли в системное окно по нашей кнопке: не запираем сразу,
            // но если не вернутся вовремя — запрём.
            handler.postDelayed(lockIfStillAway, remaining)
            return
        }
        lock()
    }
}
