package com.pocketcalc.calculator.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * «Ворота» защищённой зоны телефона.
 *
 * Превращают входные байты в выходные детерминированно, но так, что повторить
 * это можно только на ЭТОМ телефоне. На Android за [harden] отвечает
 * неизвлекаемый ключ в защищённом чипе (реализация появится в Android-слое).
 *
 * Благодаря этим воротам ключ из PIN нельзя подобрать на чужом железе:
 * каждую попытку приходится делать на самом телефоне.
 */
interface KeystoreGate {
    fun harden(input: ByteArray): ByteArray
}

/**
 * Программная реализация на HMAC-SHA256 с заданным секретом.
 *
 * Используется в тестах и как запасной вариант. Разные [secret] изображают
 * разные телефоны: ключ, завёрнутый под один секрет, не открыть под другим.
 */
class SoftwareKeystoreGate(private val secret: ByteArray) : KeystoreGate {
    override fun harden(input: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(input)
    }
}

/** Ворота «без защиты чипа» — просто возвращают вход. Для пути восстановления. */
object PassthroughGate : KeystoreGate {
    override fun harden(input: ByteArray): ByteArray = input
}
