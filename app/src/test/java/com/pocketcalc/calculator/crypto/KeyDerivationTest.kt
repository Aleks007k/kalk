package com.pocketcalc.calculator.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyDerivationTest {

    // Быстрые параметры только для тестов (боевые — тяжелее).
    private val fast = KeyDerivation.Params(memoryKiB = 1024, iterations = 1, parallelism = 1)
    private val salt = ByteArray(16) { it.toByte() }

    private fun derive(pin: String, salt: ByteArray) =
        KeyDerivation.deriveKey(pin.toCharArray(), salt, outLen = 32, params = fast)

    @Test fun deterministic() {
        assertTrue(derive("12345678", salt).contentEquals(derive("12345678", salt)))
    }

    @Test fun differentPinDifferentKey() {
        assertFalse(derive("12345678", salt).contentEquals(derive("12345679", salt)))
    }

    @Test fun differentSaltDifferentKey() {
        val salt2 = ByteArray(16) { (it + 1).toByte() }
        assertFalse(derive("12345678", salt).contentEquals(derive("12345678", salt2)))
    }

    @Test fun outputLength() {
        assertEquals(32, derive("12345678", salt).size)
        assertEquals(16, KeyDerivation.deriveKey("12345678".toCharArray(), salt, 16, fast).size)
    }

    @Test fun keyIsNotTrivial() {
        // Ключ не состоит из одних нулей.
        assertFalse(derive("12345678", salt).all { it == 0.toByte() })
    }
}
