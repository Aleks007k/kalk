package com.pocketcalc.calculator.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class FileCryptoTest {

    private val key = ByteArray(32) { (it * 7 + 1).toByte() }
    private val ad = "blob-0001".toByteArray()

    private fun enc(data: ByteArray, key: ByteArray = this.key, ad: ByteArray = this.ad): ByteArray {
        val out = ByteArrayOutputStream()
        FileCrypto.encrypt(ByteArrayInputStream(data), out, key, ad)
        return out.toByteArray()
    }

    private fun dec(ct: ByteArray, key: ByteArray = this.key, ad: ByteArray = this.ad): ByteArray {
        val out = ByteArrayOutputStream()
        FileCrypto.decrypt(ByteArrayInputStream(ct), out, key, ad)
        return out.toByteArray()
    }

    @Test fun roundTripSmall() {
        val data = "Секретный текст 🔒".toByteArray()
        assertArrayEquals(data, dec(enc(data)))
    }

    @Test fun roundTripEmpty() {
        val data = ByteArray(0)
        assertArrayEquals(data, dec(enc(data)))
    }

    @Test fun roundTripLargeMultiSegment() {
        // 5 МБ + немного — несколько кусков по 1 МБ.
        val data = ByteArray(5 * 1024 * 1024 + 12345) { (it % 251).toByte() }
        assertArrayEquals(data, dec(enc(data)))
    }

    @Test fun ciphertextDiffersFromPlaintext() {
        val data = ByteArray(1000) { 1 }
        val ct = enc(data)
        assertFalse(ct.copyOfRange(0, 1000).contentEquals(data))
    }

    @Test fun wrongKeyFails() {
        val ct = enc("данные".toByteArray())
        val wrong = ByteArray(32) { (it * 7 + 2).toByte() }
        assertThrows { dec(ct, key = wrong) }
    }

    @Test fun wrongAssociatedDataFails() {
        val ct = enc("данные".toByteArray())
        assertThrows { dec(ct, ad = "blob-9999".toByteArray()) }
    }

    @Test fun tamperedCiphertextFails() {
        val data = ByteArray(4000) { (it % 255).toByte() }
        val ct = enc(data)
        // Портим один байт в середине зашифрованных данных.
        ct[ct.size / 2] = (ct[ct.size / 2].toInt() xor 0x01).toByte()
        assertThrows { dec(ct) }
    }

    @Test fun truncatedCiphertextFails() {
        val data = ByteArray(3 * 1024 * 1024) { (it % 255).toByte() }
        val ct = enc(data)
        val cut = ct.copyOfRange(0, ct.size - 100)
        assertThrows { dec(cut) }
    }

    private fun assertThrows(block: () -> Unit) {
        var threw = false
        try {
            block()
        } catch (e: Throwable) {
            threw = true
        }
        assertTrue("ожидали ошибку расшифровки, но её не было", threw)
    }
}
