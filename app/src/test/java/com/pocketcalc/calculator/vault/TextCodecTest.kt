package com.pocketcalc.calculator.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TextCodecTest {

    private val text = "Привет, мир! Заметка №1 — проверка"

    @Test fun utf8WithoutBom() {
        assertEquals(text, TextCodec.decode(text.toByteArray(Charsets.UTF_8)))
    }

    @Test fun utf8WithBom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)
        assertEquals(text, TextCodec.decode(bytes))
    }

    @Test fun windows1251IsRecognized() {
        // Так сохраняют русский текст старые программы для Windows.
        val bytes = text.toByteArray(charset("windows-1251"))
        assertEquals(text, TextCodec.decode(bytes))
    }

    @Test fun utf16WithBom() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)
        assertEquals(text, TextCodec.decode(le))
        assertEquals(text, TextCodec.decode(be))
    }

    @Test fun lineBreaksBecomeUnix() {
        val bytes = "раз\r\nдва\rтри\nчетыре".toByteArray(Charsets.UTF_8)
        assertEquals("раз\nдва\nтри\nчетыре", TextCodec.decode(bytes))
    }

    @Test fun emptyFile() {
        assertEquals("", TextCodec.decode(ByteArray(0)))
    }

    @Test fun savesAsUtf8() {
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), TextCodec.encode(text))
        assertEquals(text, TextCodec.decode(TextCodec.encode(text)))
    }

    @Test fun titleIsFirstNonEmptyLine() {
        assertEquals("Список покупок", TextCodec.titleOf("\n\n  Список покупок  \nмолоко\nхлеб"))
    }

    @Test fun titleDropsForbiddenCharactersAndIsShort() {
        assertEquals("a b c d", TextCodec.titleOf("a/b:c*d?"))
        assertEquals(40, TextCodec.titleOf("х".repeat(100))!!.length)
    }

    @Test fun noTitleForBlankText() {
        assertEquals(null, TextCodec.titleOf("   \n\t\n"))
        assertEquals(null, TextCodec.titleOf("///"))
    }
}
