package com.pocketcalc.calculator.vault

import java.io.InputStream
import java.security.MessageDigest

/** Отпечатки SHA-256 в виде строки из 64 шестнадцатеричных символов. */
object Sha256 {

    private const val BUFFER = 64 * 1024

    /** Отпечаток всего содержимого потока. Поток не закрывает. */
    fun of(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
        return hex(digest.digest())
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
