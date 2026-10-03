package com.pocketcalc.calculator.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Ворота защищённого чипа телефона на Android Keystore.
 *
 * Неизвлекаемый HMAC-ключ создаётся в защищённой зоне процессора при первом
 * использовании и никогда её не покидает. [harden] вычисляет HMAC внутри чипа,
 * поэтому повторить результат можно только на этом телефоне: подбирать PIN
 * на чужом железе бесполезно.
 *
 * Ключ НЕ привязан к блокировке экрана: смена блокировки экрана или отпечатков
 * его не уничтожает. Если ключ всё же пропадёт (сбой после обновления системы),
 * тайник открывается кодом восстановления, а новый PIN привязывается к новому ключу.
 */
class AndroidKeystoreGate(private val alias: String = DEFAULT_ALIAS) : KeystoreGate {

    override fun harden(input: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(getOrCreateKey())
        return mac.doFinal(input)
    }

    private fun getOrCreateKey(): SecretKey = synchronized(LOCK) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) {
            existing
        } else {
            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
                ANDROID_KEYSTORE,
            )
            generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).build())
            generator.generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "calc_gate_hmac_v1"
        val LOCK = Any()
    }
}
