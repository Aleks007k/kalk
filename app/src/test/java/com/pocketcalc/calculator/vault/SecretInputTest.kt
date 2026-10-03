package com.pocketcalc.calculator.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretInputTest {

    // --- Распознавание набранного в калькуляторе ---

    @Test fun eightToTwelveDigitsArePinCandidates() {
        assertEquals(SecretCandidate.Pin("48291375"), SecretInput.classify("48291375"))
        assertEquals(SecretCandidate.Pin("482913750123"), SecretInput.classify("482913750123"))
    }

    @Test fun sixteenDigitsAreRecoveryCandidates() {
        assertEquals(
            SecretCandidate.Recovery("4821093755126604"),
            SecretInput.classify("4821093755126604"),
        )
    }

    @Test fun otherInputsAreNotSecrets() {
        assertNull(SecretInput.classify("1234567"))            // 7 цифр
        assertNull(SecretInput.classify("1234567890123"))      // 13 цифр
        assertNull(SecretInput.classify("123456789012345"))    // 15 цифр
        assertNull(SecretInput.classify("12345678901234567"))  // 17 цифр
        assertNull(SecretInput.classify("4829.1375"))          // дробь
        assertNull(SecretInput.classify("-48291375"))          // минус
        assertNull(SecretInput.classify("4829+1375"))          // выражение
        assertNull(SecretInput.classify("4829137%"))           // процент
        assertNull(SecretInput.classify("04829137"))           // ведущий ноль
        assertNull(SecretInput.classify(""))
    }

    // --- Правила для нового PIN ---

    @Test fun goodPinAccepted() {
        assertNull(SecretInput.checkPin("48291375"))
        assertNull(SecretInput.checkPin("907135284612"))
    }

    @Test fun pinProblemsDetected() {
        assertEquals(PinProblem.TOO_SHORT, SecretInput.checkPin("4829137"))
        assertEquals(PinProblem.TOO_LONG, SecretInput.checkPin("4829137512345"))
        assertEquals(PinProblem.LEADING_ZERO, SecretInput.checkPin("04829137"))
        assertEquals(PinProblem.ALL_SAME, SecretInput.checkPin("77777777"))
        assertEquals(PinProblem.SEQUENCE, SecretInput.checkPin("12345678"))
        assertEquals(PinProblem.SEQUENCE, SecretInput.checkPin("98765432"))
        assertEquals(PinProblem.NOT_DIGITS, SecretInput.checkPin("4829a375"))
    }

    @Test fun validPinIsAlwaysTypeableAsCandidate() {
        // Любой PIN, который проходит правила, распознаётся калькулятором как кандидат.
        for (pin in listOf("48291375", "100000007", "907135284612", "13579135")) {
            assertNull(SecretInput.checkPin(pin))
            assertEquals(SecretCandidate.Pin(pin), SecretInput.classify(pin))
        }
    }

    // --- Код восстановления ---

    @Test fun recoveryCodeShape() {
        repeat(500) {
            val code = SecretInput.generateRecoveryCode()
            assertEquals(16, code.length)
            assertTrue(code.all { it in '0'..'9' })
            assertTrue("первая цифра не ноль", code[0] != '0')
            // Каждый код распознаётся калькулятором как код восстановления.
            assertEquals(SecretCandidate.Recovery(code), SecretInput.classify(code))
        }
    }

    @Test fun recoveryCodesAreRandom() {
        assertNotEquals(SecretInput.generateRecoveryCode(), SecretInput.generateRecoveryCode())
    }

    @Test fun recoveryCodeFormatting() {
        assertEquals("4821 0937 5512 6604", SecretInput.formatRecoveryCode("4821093755126604"))
    }
}
