package com.pocketcalc.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class CalculatorEngineTest {

    private fun ok(expr: String): BigDecimal {
        val r = CalculatorEngine.evaluate(expr)
        assertTrue("ожидали успех для \"$expr\", получили $r", r is EvalResult.Ok)
        return (r as EvalResult.Ok).value
    }

    private fun eq(expr: String, expected: String) {
        assertEquals("для \"$expr\"", 0, ok(expr).compareTo(BigDecimal(expected)))
    }

    @Test fun addition() = eq("2+3", "5")
    @Test fun subtraction() = eq("10-4", "6")
    @Test fun multiplication() = eq("6*7", "42")
    @Test fun division() = eq("20/4", "5")

    @Test fun precedence() {
        eq("2+3*4", "14")
        eq("2*3+4", "10")
        eq("10-2*3", "4")
    }

    @Test fun parentheses() {
        eq("(2+3)*4", "20")
        eq("2*(3+4)", "14")
        eq("((1+2)*(3+4))", "21")
    }

    @Test fun decimals() {
        eq("0.1+0.2", "0.3")
        eq("1.5*2", "3")
        eq("3.14*2", "6.28")
    }

    @Test fun repeatingDivision() {
        // 1/3 округляется до 16 знаков, но результат — число, а не ошибка.
        val v = ok("1/3")
        assertTrue(v.toPlainString().startsWith("0.333333"))
    }

    @Test fun unaryMinus() {
        eq("-5", "-5")
        eq("-5+3", "-2")
        eq("3*-2", "-6")
        eq("-(2+3)", "-5")
    }

    @Test fun displaySymbols() {
        // Экранные символы × ÷ − должны пониматься наравне с ASCII.
        eq("6×7", "42")
        eq("20÷4", "5")
        eq("10−3", "7")
    }

    @Test fun percent() {
        eq("50%", "0.5")
        eq("200*10%", "20")
        eq("10%+10%", "0.2")
    }

    @Test fun thousandsSeparatorIgnored() {
        // Неразрывные пробелы между тысячами не мешают разбору.
        eq("1 234+1", "1235")
    }

    @Test fun divByZero() {
        assertEquals(EvalResult.DivByZero, CalculatorEngine.evaluate("5/0"))
        assertEquals(EvalResult.DivByZero, CalculatorEngine.evaluate("5/(3-3)"))
    }

    @Test fun invalid() {
        assertEquals(EvalResult.Invalid, CalculatorEngine.evaluate(""))
        assertEquals(EvalResult.Invalid, CalculatorEngine.evaluate("5++"))
        assertEquals(EvalResult.Invalid, CalculatorEngine.evaluate("5+"))
        assertEquals(EvalResult.Invalid, CalculatorEngine.evaluate("(2+3"))
        assertEquals(EvalResult.Invalid, CalculatorEngine.evaluate("2+3)"))
        assertEquals(EvalResult.Invalid, CalculatorEngine.evaluate("abc"))
    }

    @Test fun longDigitsPreserved() {
        // 16-значное число не теряет точность (важно для кода восстановления).
        eq("4821093755126604", "4821093755126604")
    }
}
