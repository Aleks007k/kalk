package com.pocketcalc.calculator.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Получение ключа из PIN или кода восстановления с помощью Argon2id.
 *
 * Argon2id — «медленная» функция: подбор PIN намеренно затратен.
 * Параметры (память, число проходов) хранятся рядом с зашифрованным ключом,
 * поэтому их можно менять в будущем, не ломая уже сохранённые данные.
 *
 * Чистый Java-код Bouncy Castle: работает одинаково на Android и на сервере,
 * нативных библиотек не требует, поэтому покрыт обычными JVM-тестами.
 */
object KeyDerivation {

    /** Параметры Argon2id. memoryKiB — память в килобайтах. */
    data class Params(
        val memoryKiB: Int,
        val iterations: Int,
        val parallelism: Int,
    ) {
        init {
            require(memoryKiB >= 8 * parallelism) { "мало памяти для Argon2" }
            require(iterations >= 1)
            require(parallelism >= 1)
        }
    }

    /**
     * Боевые параметры по умолчанию для PIN (~64 МБ).
     * Точное время подстроим на реальном телефоне на этапе укрепления.
     */
    val PIN_DEFAULT = Params(memoryKiB = 64 * 1024, iterations = 3, parallelism = 4)

    /** Параметры для кода восстановления — тяжелее, т.к. вводится редко. */
    val RECOVERY_DEFAULT = Params(memoryKiB = 64 * 1024, iterations = 5, parallelism = 4)

    /**
     * Выводит ключ длиной [outLen] байт из секрета [secret] и соли [salt].
     * [secret] — символы PIN/кода; вызывающий сам очищает массив после использования.
     */
    fun deriveKey(
        secret: CharArray,
        salt: ByteArray,
        outLen: Int = 32,
        params: Params = PIN_DEFAULT,
    ): ByteArray {
        require(salt.isNotEmpty()) { "соль не должна быть пустой" }
        require(outLen in 16..64)
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(params.memoryKiB)
            .withIterations(params.iterations)
            .withParallelism(params.parallelism)
            .withSalt(salt)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(parameters)
        val out = ByteArray(outLen)
        generator.generateBytes(secret, out)
        return out
    }
}
