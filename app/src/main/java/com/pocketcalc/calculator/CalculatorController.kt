package com.pocketcalc.calculator

import java.math.BigDecimal
import java.math.RoundingMode

/** Действия, которые пользователь совершает кнопками. */
enum class Key {
    D0, D1, D2, D3, D4, D5, D6, D7, D8, D9,
    DOT, PLUS, MINUS, TIMES, DIVIDE, PERCENT,
    SIGN,       // ±
    EQUALS,     // =
    CLEAR,      // AC
    BACKSPACE,  // ⌫
}

/** Что видно на экране калькулятора. */
data class CalculatorState(
    /** Верхняя строка: набранное выражение (может быть пустой). */
    val expression: String,
    /** Нижняя крупная строка: текущий ввод или результат. */
    val display: String,
    /** true, если последним действием было «=» (следующая цифра начнёт новый ввод). */
    val evaluated: Boolean = false,
)

/**
 * Логика ввода калькулятора: собирает выражение из нажатий кнопок,
 * форматирует его для показа и вычисляет результат через [CalculatorEngine].
 *
 * Не зависит от Android — покрыта JVM-тестами.
 *
 * Внутри выражение хранится в «сыром» виде (ASCII: + - * / % . и скобки).
 * Для показа оно переводится в красивые символы (× ÷ − и пробелы в тысячах).
 */
class CalculatorController {

    /** Максимум символов во всём выражении — чтобы строка не росла бесконечно. */
    private val maxExpressionLength = 60

    /** Максимум цифр в одном числе. 16 ≥ 16-значного кода восстановления. */
    private val maxDigitsPerNumber = 16

    private val sb = StringBuilder()
    private var evaluated = false

    /** «Сырое» выражение (для будущей проверки PIN). */
    val rawExpression: String get() = sb.toString()

    fun state(): CalculatorState {
        val raw = sb.toString()
        val pretty = if (raw.isEmpty()) "0" else prettify(raw)
        return CalculatorState(
            expression = pretty,
            display = pretty,
            evaluated = evaluated,
        )
    }

    fun press(key: Key): CalculatorState {
        when (key) {
            Key.CLEAR -> clearAll()
            Key.BACKSPACE -> backspace()
            Key.EQUALS -> equals()
            Key.DOT -> inputDot()
            Key.SIGN -> toggleSign()
            Key.PERCENT -> inputPercent()
            Key.PLUS -> inputOperator('+')
            Key.MINUS -> inputOperator('-')
            Key.TIMES -> inputOperator('*')
            Key.DIVIDE -> inputOperator('/')
            else -> inputDigit(digitOf(key))
        }
        return state()
    }

    // --- Обработка нажатий -----------------------------------------------

    private fun clearAll() {
        sb.setLength(0)
        evaluated = false
    }

    private fun backspace() {
        if (evaluated) {
            // После «=» backspace очищает всё.
            clearAll()
            return
        }
        if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1)
    }

    private fun inputDigit(d: Char) {
        if (evaluated) {
            // Новый ввод после результата — начинаем с чистого листа.
            clearAll()
        }
        val cur = currentNumber(sb.toString())
        val digitsInNumber = cur.count { it.isDigit() }
        if (digitsInNumber >= maxDigitsPerNumber) return
        // Ведущий ноль: "0" + цифра → заменяем ноль (кроме "0.").
        if (cur == "0") {
            sb.deleteCharAt(sb.length - 1)
        }
        if (sb.length >= maxExpressionLength) return
        sb.append(d)
    }

    private fun inputDot() {
        if (evaluated) clearAll()
        if (sb.length >= maxExpressionLength) return
        val cur = currentNumber(sb.toString())
        when {
            cur.isEmpty() -> sb.append("0.")        // начать дробь с нуля
            cur.contains('.') -> return             // вторая точка запрещена
            else -> sb.append('.')
        }
    }

    private fun inputOperator(op: Char) {
        if (sb.isEmpty()) {
            // Разрешаем начинать выражение только с минуса.
            if (op == '-') sb.append('-')
            evaluated = false
            return
        }
        evaluated = false
        val last = sb.last()
        when {
            // Заменяем висящий оператор на новый (кроме случая "(" или унарного минуса).
            isOperator(last) -> {
                if (last == '-' && (sb.length == 1 || isOperator(sb[sb.length - 2]) || sb[sb.length - 2] == '(')) {
                    // это унарный минус — не трогаем
                    return
                }
                sb.deleteCharAt(sb.length - 1)
                sb.append(op)
            }
            last == '.' -> {
                // "5." + оператор → убираем точку.
                sb.deleteCharAt(sb.length - 1)
                if (sb.length < maxExpressionLength) sb.append(op)
            }
            else -> if (sb.length < maxExpressionLength) sb.append(op)
        }
    }

    private fun inputPercent() {
        if (sb.isEmpty()) return
        if (evaluated) evaluated = false
        val last = sb.last()
        if (last.isDigit() || last == '%' || last == ')') {
            if (sb.length < maxExpressionLength) sb.append('%')
        }
    }

    private fun toggleSign() {
        // Меняем знак текущего числа. Если его нет — ничего.
        val raw = sb.toString()
        val start = currentNumberStart(raw)
        if (start >= raw.length) return
        // Вставляем или убираем минус перед числом.
        if (start > 0 && raw[start - 1] == '-' &&
            (start - 1 == 0 || isOperator(raw[start - 2]) || raw[start - 2] == '(')
        ) {
            sb.deleteCharAt(start - 1)
        } else {
            sb.insert(start, '-')
        }
    }

    private fun equals() {
        if (sb.isEmpty()) return
        // Висящие операторы и точка в конце не считаются ошибкой: "5+" → 5.
        val trimmed = sb.toString().trimEnd('+', '-', '*', '/', '.')
        if (trimmed.isEmpty()) return
        when (val r = CalculatorEngine.evaluate(trimmed)) {
            is EvalResult.Ok -> {
                sb.setLength(0)
                sb.append(plain(r.value))
                evaluated = true
            }
            // При ошибке (деление на ноль, незакрытая скобка) оставляем ввод как есть.
            EvalResult.DivByZero -> { /* показ ошибки — через UI */ }
            EvalResult.Invalid -> { /* то же */ }
        }
    }

    /** Форматирует число для показа: без лишних нулей, с пробелами в тысячах. */
    fun format(value: BigDecimal): String = prettify(plain(value))

    /** Признак того, что текущее выражение не вычисляется (для показа «Ошибка» в UI). */
    fun evaluationError(): Boolean {
        val raw = sb.toString()
        if (raw.isEmpty() || evaluated) return false
        // Не считаем ошибкой «промежуточный» ввод, оканчивающийся оператором/точкой.
        if (isOperator(raw.last()) || raw.last() == '.' || raw.last() == '(') return false
        return CalculatorEngine.evaluate(raw) !is EvalResult.Ok
    }

    // --- Вспомогательное --------------------------------------------------

    private fun digitOf(key: Key): Char = when (key) {
        Key.D0 -> '0'; Key.D1 -> '1'; Key.D2 -> '2'; Key.D3 -> '3'; Key.D4 -> '4'
        Key.D5 -> '5'; Key.D6 -> '6'; Key.D7 -> '7'; Key.D8 -> '8'; Key.D9 -> '9'
        else -> throw IllegalArgumentException("not a digit: $key")
    }

    private fun isOperator(c: Char) = c == '+' || c == '-' || c == '*' || c == '/'

    /** Индекс начала «текущего» (последнего) числа в сыром выражении. */
    private fun currentNumberStart(raw: String): Int {
        var i = raw.length
        while (i > 0) {
            val c = raw[i - 1]
            if (c.isDigit() || c == '.') i-- else break
        }
        return i
    }

    /** Текст последнего числа (может быть пустым, если выражение кончается оператором). */
    private fun currentNumber(raw: String): String = raw.substring(currentNumberStart(raw))


    // --- Форматирование ---------------------------------------------------

    /** BigDecimal → обычная строка без экспоненты, без лишних нулей. */
    private fun plain(value: BigDecimal): String {
        val v = value.stripTrailingZeros()
        // Ограничим дробную часть 10 знаками для показа.
        val scaled = if (v.scale() > 10) v.setScale(10, RoundingMode.HALF_UP).stripTrailingZeros() else v
        return scaled.toPlainString()
    }

    /** Красивый вид: × ÷ − и пробелы-разделители тысяч в целых частях чисел. */
    private fun prettify(raw: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c.isDigit()) {
                val start = i
                while (i < raw.length && (raw[i].isDigit() || raw[i] == '.')) i++
                out.append(groupNumber(raw.substring(start, i)))
            } else {
                out.append(
                    when (c) {
                        '*' -> '×'
                        '/' -> '÷'
                        '-' -> '−'
                        else -> c
                    }
                )
                i++
            }
        }
        return out.toString()
    }


    /** Разбивает целую часть числа по 3 цифры неразрывным пробелом. */
    private fun groupNumber(num: String): String {
        val dot = num.indexOf('.')
        val intPart = if (dot >= 0) num.substring(0, dot) else num
        val fracPart = if (dot >= 0) num.substring(dot) else ""
        if (intPart.length <= 3) return intPart + fracPart
        val grouped = StringBuilder()
        val firstGroup = intPart.length % 3
        var idx = 0
        if (firstGroup > 0) {
            grouped.append(intPart, 0, firstGroup)
            idx = firstGroup
        }
        while (idx < intPart.length) {
            if (grouped.isNotEmpty()) grouped.append(' ')
            grouped.append(intPart, idx, idx + 3)
            idx += 3
        }
        return grouped.toString() + fracPart
    }
}
