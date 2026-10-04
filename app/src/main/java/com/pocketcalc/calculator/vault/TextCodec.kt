package com.pocketcalc.calculator.vault

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Текст заметок. Читает UTF-8 (с меткой BOM или без), UTF-16 с меткой и
 * старую русскую кодировку Windows-1251 (её выдают многие программы для
 * Windows). Сохраняет всегда в UTF-8. Переводы строк приводятся к "\n".
 */
object TextCodec {

    private val WINDOWS_1251: Charset = Charset.forName("windows-1251")

    fun decode(bytes: ByteArray): String = normalizeLineBreaks(decodeRaw(bytes))

    fun encode(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    /**
     * Название для новой заметки — её первая непустая строка, очищенная от
     * знаков, запрещённых в именах файлов, и не длиннее [maxLength].
     * null — если текста нет.
     */
    fun titleOf(text: String, maxLength: Int = 40): String? {
        val firstLine = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val cleaned = firstLine
            .replace(FORBIDDEN_IN_NAMES, " ")
            .replace(SPACES, " ")
            .trim()
            .take(maxLength)
            .trim()
        return cleaned.ifEmpty { null }
    }

    private val FORBIDDEN_IN_NAMES = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private val SPACES = Regex("\\s+")

    private fun decodeRaw(bytes: ByteArray): String {
        if (bytes.startsWith(0xEF, 0xBB, 0xBF)) return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.startsWith(0xFF, 0xFE)) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.startsWith(0xFE, 0xFF)) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        val strictUtf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            strictUtf8.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, WINDOWS_1251)
        }
    }

    private fun normalizeLineBreaks(text: String): String =
        if (text.indexOf('\r') < 0) text else text.replace("\r\n", "\n").replace('\r', '\n')

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }
}
