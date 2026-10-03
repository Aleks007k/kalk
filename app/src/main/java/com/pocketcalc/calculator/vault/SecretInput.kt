package com.pocketcalc.calculator.vault

import java.security.SecureRandom

/** Что было набрано на калькуляторе перед «=» — если это похоже на секрет. */
sealed interface SecretCandidate {
    /** 8–12 цифр: возможно, PIN. */
    data class Pin(val digits: String) : SecretCandidate

    /** Ровно 16 цифр: возможно, код восстановления. */
    data class Recovery(val digits: String) : SecretCandidate
}

/** Почему PIN не подходит. */
enum class PinProblem { NOT_DIGITS, TOO_SHORT, TOO_LONG, LEADING_ZERO, ALL_SAME, SEQUENCE }

/**
 * Правила для PIN и кода восстановления.
 *
 * Калькулятор не даёт набрать ведущий ноль (как любой калькулятор), поэтому
 * ни PIN, ни код восстановления не начинаются с нуля.
 */
object SecretInput {

    const val PIN_MIN = 8
    const val PIN_MAX = 12
    const val RECOVERY_LENGTH = 16

    private val pinRegex = Regex("[1-9][0-9]{${PIN_MIN - 1},${PIN_MAX - 1}}")
    private val recoveryRegex = Regex("[1-9][0-9]{${RECOVERY_LENGTH - 1}}")

    /**
     * Определяет, похоже ли набранное в калькуляторе на секрет.
     * [raw] — «сырое» выражение калькулятора (только цифры = кандидат).
     */
    fun classify(raw: String): SecretCandidate? = when {
        pinRegex.matches(raw) -> SecretCandidate.Pin(raw)
        recoveryRegex.matches(raw) -> SecretCandidate.Recovery(raw)
        else -> null
    }

    /** Проверяет новый PIN. Возвращает проблему или null, если PIN подходит. */
    fun checkPin(pin: String): PinProblem? = when {
        pin.any { it !in '0'..'9' } -> PinProblem.NOT_DIGITS
        pin.length < PIN_MIN -> PinProblem.TOO_SHORT
        pin.length > PIN_MAX -> PinProblem.TOO_LONG
        pin[0] == '0' -> PinProblem.LEADING_ZERO
        pin.all { it == pin[0] } -> PinProblem.ALL_SAME
        isStraightSequence(pin) -> PinProblem.SEQUENCE
        else -> null
    }

    /** 12345678, 98765432 и подобные: каждая цифра на 1 больше (или меньше) предыдущей. */
    private fun isStraightSequence(pin: String): Boolean {
        val steps = pin.zipWithNext { a, b -> b - a }
        return steps.all { it == 1 } || steps.all { it == -1 }
    }

    /** Новый случайный код восстановления: 16 цифр, первая — не ноль. */
    fun generateRecoveryCode(rng: SecureRandom = SecureRandom()): String {
        val sb = StringBuilder(RECOVERY_LENGTH)
        sb.append('1' + rng.nextInt(9))
        repeat(RECOVERY_LENGTH - 1) { sb.append('0' + rng.nextInt(10)) }
        return sb.toString()
    }

    /** "4821093755126604" → "4821 0937 5512 6604" (удобно записывать). */
    fun formatRecoveryCode(code: String): String = code.chunked(4).joinToString(" ")
}
