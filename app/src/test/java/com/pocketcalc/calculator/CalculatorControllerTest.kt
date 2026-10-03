package com.pocketcalc.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorControllerTest {

    private fun type(vararg keys: Key): CalculatorController {
        val c = CalculatorController()
        for (k in keys) c.press(k)
        return c
    }

    @Test fun startsAtZero() {
        assertEquals("0", CalculatorController().state().display)
    }

    @Test fun typingDigits() {
        val c = type(Key.D1, Key.D2, Key.D3)
        assertEquals("123", c.rawExpression)
        assertEquals("123", c.state().display)
    }

    @Test fun leadingZeroReplaced() {
        val c = type(Key.D0, Key.D5)
        assertEquals("5", c.rawExpression)
    }

    @Test fun thousandsGroupingInDisplay() {
        val c = type(Key.D1, Key.D2, Key.D3, Key.D4, Key.D5, Key.D6, Key.D7)
        assertEquals("1234567", c.rawExpression)
        assertEquals("1 234 567", c.state().display)
    }

    @Test fun simpleEquals() {
        val c = type(Key.D6, Key.TIMES, Key.D7, Key.EQUALS)
        assertEquals("42", c.rawExpression)
        assertTrue(c.state().evaluated)
        assertEquals("42", c.state().display)
    }

    @Test fun operatorReplacement() {
        // 5 + × → оператор заменяется на последний.
        val c = type(Key.D5, Key.PLUS, Key.TIMES, Key.D2, Key.EQUALS)
        assertEquals("10", c.rawExpression)
    }

    @Test fun digitAfterEqualsStartsFresh() {
        val c = type(Key.D6, Key.TIMES, Key.D7, Key.EQUALS, Key.D9)
        assertEquals("9", c.rawExpression)
        assertFalse(c.state().evaluated)
    }

    @Test fun operatorAfterEqualsContinues() {
        val c = type(Key.D6, Key.TIMES, Key.D7, Key.EQUALS, Key.PLUS, Key.D1, Key.EQUALS)
        assertEquals("43", c.rawExpression)
    }

    @Test fun backspace() {
        val c = type(Key.D1, Key.D2, Key.D3, Key.BACKSPACE)
        assertEquals("12", c.rawExpression)
    }

    @Test fun backspaceAfterEqualsClearsAll() {
        val c = type(Key.D5, Key.PLUS, Key.D5, Key.EQUALS, Key.BACKSPACE)
        assertEquals("", c.rawExpression)
        assertEquals("0", c.state().display)
    }

    @Test fun clear() {
        val c = type(Key.D1, Key.D2, Key.PLUS, Key.D3, Key.CLEAR)
        assertEquals("", c.rawExpression)
        assertEquals("0", c.state().display)
    }

    @Test fun decimalPoint() {
        val c = type(Key.D3, Key.DOT, Key.D1, Key.D4)
        assertEquals("3.14", c.rawExpression)
    }

    @Test fun decimalPointFromEmpty() {
        val c = type(Key.DOT, Key.D5)
        assertEquals("0.5", c.rawExpression)
    }

    @Test fun secondDecimalPointIgnored() {
        val c = type(Key.D3, Key.DOT, Key.D1, Key.DOT, Key.D4)
        assertEquals("3.14", c.rawExpression)
    }

    @Test fun maxSixteenDigitsPerNumber() {
        val c = CalculatorController()
        repeat(20) { c.press(Key.D9) }
        assertEquals(16, c.rawExpression.count { it.isDigit() })
    }

    @Test fun toggleSign() {
        val c = type(Key.D5, Key.SIGN)
        assertEquals("-5", c.rawExpression)
        c.press(Key.SIGN)
        assertEquals("5", c.rawExpression)
    }

    @Test fun toggleSignOnSecondOperand() {
        val c = type(Key.D8, Key.PLUS, Key.D3, Key.SIGN, Key.EQUALS)
        assertEquals("5", c.rawExpression)
    }

    @Test fun leadingMinusAllowed() {
        val c = type(Key.MINUS, Key.D5, Key.PLUS, Key.D2, Key.EQUALS)
        assertEquals("-3", c.rawExpression)
    }

    @Test fun divByZeroShowsError() {
        val c = type(Key.D5, Key.DIVIDE, Key.D0, Key.EQUALS)
        // «=» на делении на ноль не меняет выражение, а UI покажет ошибку.
        assertEquals("5/0", c.rawExpression)
        assertTrue(c.evaluationError())
    }

    @Test fun trailingOperatorIsNotError() {
        val c = type(Key.D5, Key.PLUS)
        assertFalse(c.evaluationError())
    }

    @Test fun pinLikeInputPreserved() {
        // Набор 10-значного числа без операторов — как ввод PIN.
        val c = type(Key.D4, Key.D8, Key.D2, Key.D9, Key.D1, Key.D3, Key.D7, Key.D5, Key.D5, Key.D1)
        assertEquals("4829137551", c.rawExpression)
        c.press(Key.EQUALS)
        assertEquals("4829137551", c.rawExpression)
    }
}
