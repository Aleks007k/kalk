package com.pocketcalc.calculator

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Результат вычисления выражения.
 */
sealed interface EvalResult {
    /** Успех: готовое числовое значение. */
    data class Ok(val value: BigDecimal) : EvalResult

    /** Деление на ноль. */
    data object DivByZero : EvalResult

    /** Некорректное выражение (например, "5++"). */
    data object Invalid : EvalResult
}

/**
 * Чистый вычислитель арифметических выражений над [BigDecimal].
 *
 * Понимает как экранные символы (× ÷ − %), так и ASCII (* / -).
 * Грамматика: + − × ÷ с обычным приоритетом, скобки, унарный минус,
 * постфиксный процент. Процент — это «разделить на 100» применительно
 * к числу, за которым он стоит: 50% = 0.5, 200×10% = 20.
 *
 * Класс не зависит от Android, поэтому покрыт обычными JVM-тестами.
 */
object CalculatorEngine {

    /** 16 значащих цифр — как у настоящего калькулятора; хватает и для деления. */
    private val MC = MathContext(16, RoundingMode.HALF_UP)

    private val HUNDRED = BigDecimal(100)

    fun evaluate(expression: String): EvalResult {
        val tokens = tokenize(expression) ?: return EvalResult.Invalid
        if (tokens.isEmpty()) return EvalResult.Invalid
        val parser = Parser(tokens)
        val value = try {
            parser.parseExpr()
        } catch (e: DivByZeroException) {
            return EvalResult.DivByZero
        } catch (e: ParseException) {
            return EvalResult.Invalid
        }
        if (!parser.atEnd()) return EvalResult.Invalid
        return EvalResult.Ok(value.round(MC).stripTrailingZeros())
    }

    // --- Токенайзер -------------------------------------------------------

    private sealed interface Token {
        data class Num(val value: BigDecimal) : Token
        data object Plus : Token
        data object Minus : Token
        data object Mul : Token
        data object Div : Token
        data object Percent : Token
        data object LParen : Token
        data object RParen : Token
    }

    /** Возвращает список токенов, либо null при недопустимом символе. */
    private fun tokenize(raw: String): List<Token>? {
        // Пробелы и неразрывные пробелы (разделители тысяч) значения не имеют.
        val expr = raw.filterNot { it == ' ' || it == ' ' || it.isWhitespace() }
        val tokens = ArrayList<Token>()
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            when {
                c.isDigit() || c == '.' -> {
                    val start = i
                    var dots = if (c == '.') 1 else 0
                    i++
                    while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) {
                        if (expr[i] == '.') dots++
                        i++
                    }
                    val raw = expr.substring(start, i)
                    if (dots > 1) return null
                    val normalized = if (raw.startsWith(".")) "0$raw" else raw
                    val value = try {
                        BigDecimal(normalized)
                    } catch (e: NumberFormatException) {
                        return null
                    }
                    tokens.add(Token.Num(value))
                }
                c == '+' -> { tokens.add(Token.Plus); i++ }
                c == '-' || c == '−' -> { tokens.add(Token.Minus); i++ }
                c == '*' || c == '×' -> { tokens.add(Token.Mul); i++ }
                c == '/' || c == '÷' -> { tokens.add(Token.Div); i++ }
                c == '%' -> { tokens.add(Token.Percent); i++ }
                c == '(' -> { tokens.add(Token.LParen); i++ }
                c == ')' -> { tokens.add(Token.RParen); i++ }
                else -> return null
            }
        }
        return tokens
    }

    // --- Парсер (рекурсивный спуск) --------------------------------------

    private class ParseException : Exception()
    private class DivByZeroException : Exception()

    private class Parser(private val tokens: List<Token>) {
        private var pos = 0

        fun atEnd() = pos >= tokens.size

        private fun peek(): Token? = tokens.getOrNull(pos)
        private fun next(): Token = tokens.getOrNull(pos++) ?: throw ParseException()

        // expr := term (('+'|'−') term)*
        fun parseExpr(): BigDecimal {
            var acc = parseTerm()
            while (true) {
                when (peek()) {
                    Token.Plus -> { pos++; acc = acc.add(parseTerm()) }
                    Token.Minus -> { pos++; acc = acc.subtract(parseTerm()) }
                    else -> return acc
                }
            }
        }

        // term := factor (('×'|'÷') factor)*
        private fun parseTerm(): BigDecimal {
            var acc = parseFactor()
            while (true) {
                when (peek()) {
                    Token.Mul -> { pos++; acc = acc.multiply(parseFactor()) }
                    Token.Div -> {
                        pos++
                        val divisor = parseFactor()
                        if (divisor.signum() == 0) throw DivByZeroException()
                        acc = acc.divide(divisor, MC)
                    }
                    else -> return acc
                }
            }
        }

        // factor := '−' factor | postfix
        private fun parseFactor(): BigDecimal {
            if (peek() == Token.Minus) {
                pos++
                return parseFactor().negate()
            }
            if (peek() == Token.Plus) {
                pos++
                return parseFactor()
            }
            return parsePostfix()
        }

        // postfix := primary '%'*
        private fun parsePostfix(): BigDecimal {
            var value = parsePrimary()
            while (peek() == Token.Percent) {
                pos++
                value = value.divide(HUNDRED, MC)
            }
            return value
        }

        // primary := number | '(' expr ')'
        private fun parsePrimary(): BigDecimal {
            return when (val t = next()) {
                is Token.Num -> t.value
                Token.LParen -> {
                    val inner = parseExpr()
                    if (next() != Token.RParen) throw ParseException()
                    inner
                }
                else -> throw ParseException()
            }
        }
    }
}
