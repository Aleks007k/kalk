package com.pocketcalc.calculator.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AesGcmKey
import com.google.crypto.tink.aead.AesGcmParameters
import com.google.crypto.tink.util.SecretBytes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom

/**
 * Хранилище ключей одного тайника («слот»).
 *
 * У тайника есть случайный мастер-ключ (32 байта) — им шифруются все файлы.
 * Сам мастер-ключ в открытом виде не хранится: он завёрнут в два «конверта».
 *
 *  • Конверт PIN: ключ = Argon2id(PIN) → проведён через [KeystoreGate]
 *    (защищённый чип) → им зашифрован мастер-ключ (AES-256-GCM).
 *    Из-за ворот чипа PIN нельзя подбирать на чужом устройстве.
 *
 *  • Конверт восстановления: ключ = Argon2id(код) без ворот чипа. Открывает
 *    тайник, даже если чип сброшен (например, после обновления системы).
 *
 * Неверный PIN/код → расшифровка конверта падает → [openWithPin]/[openWithRecovery]
 * возвращают null. Содержимое обоих конвертов неотличимо от случайных байтов,
 * поэтому фальшивый слот ([randomSlot]) не отличить от настоящего.
 */
object VaultKeyManager {

    private const val MAGIC = 0x56533101 // "VS1" + версия 1
    private const val MASTER_KEY_SIZE = 32
    private const val SALT_SIZE = 16

    private val AD_PIN = "pin".toByteArray()
    private val AD_REC = "recovery".toByteArray()

    init {
        AeadConfig.register()
    }

    private val rng = SecureRandom()

    /** Непрозрачный сериализованный слот. */
    class Slot(val bytes: ByteArray)

    private class Wrapper(
        val params: KeyDerivation.Params,
        val salt: ByteArray,
        val ciphertext: ByteArray,
    )

    // --- Создание и открытие ---------------------------------------------

    /**
     * Создаёт новый слот со случайным мастер-ключом, завёрнутым под [pin] и
     * [recoveryCode]. Возвращает слот и сам мастер-ключ (нужен сразу после
     * создания, чтобы зашифровать первые данные).
     */
    fun createSlot(
        pin: CharArray,
        recoveryCode: CharArray,
        gate: KeystoreGate,
        pinParams: KeyDerivation.Params = KeyDerivation.PIN_DEFAULT,
        recoveryParams: KeyDerivation.Params = KeyDerivation.RECOVERY_DEFAULT,
    ): Pair<Slot, ByteArray> {
        val masterKey = ByteArray(MASTER_KEY_SIZE).also { rng.nextBytes(it) }
        val pinWrapper = wrap(masterKey, pin, gate, pinParams, AD_PIN)
        val recWrapper = wrap(masterKey, recoveryCode, PassthroughGate, recoveryParams, AD_REC)
        return Slot(serialize(pinWrapper, recWrapper)) to masterKey
    }

    /** Возвращает мастер-ключ по PIN, либо null при неверном PIN/чужом устройстве. */
    fun openWithPin(slot: Slot, pin: CharArray, gate: KeystoreGate): ByteArray? {
        val (pinWrapper, _) = deserialize(slot.bytes) ?: return null
        return unwrap(pinWrapper, pin, gate, AD_PIN)
    }

    /** Возвращает мастер-ключ по коду восстановления, либо null при неверном коде. */
    fun openWithRecovery(slot: Slot, recoveryCode: CharArray): ByteArray? {
        val (_, recWrapper) = deserialize(slot.bytes) ?: return null
        return unwrap(recWrapper, recoveryCode, PassthroughGate, AD_REC)
    }

    /** Меняет PIN, не трогая мастер-ключ и конверт восстановления. */
    fun changePin(
        slot: Slot,
        masterKey: ByteArray,
        newPin: CharArray,
        gate: KeystoreGate,
        pinParams: KeyDerivation.Params = KeyDerivation.PIN_DEFAULT,
    ): Slot {
        val (_, recWrapper) = deserialize(slot.bytes) ?: error("битый слот")
        val pinWrapper = wrap(masterKey, newPin, gate, pinParams, AD_PIN)
        return Slot(serialize(pinWrapper, recWrapper))
    }

    /** Выпускает новый код восстановления, не трогая мастер-ключ и конверт PIN. */
    fun changeRecovery(
        slot: Slot,
        masterKey: ByteArray,
        newRecoveryCode: CharArray,
        recoveryParams: KeyDerivation.Params = KeyDerivation.RECOVERY_DEFAULT,
    ): Slot {
        val (pinWrapper, _) = deserialize(slot.bytes) ?: error("битый слот")
        val recWrapper = wrap(masterKey, newRecoveryCode, PassthroughGate, recoveryParams, AD_REC)
        return Slot(serialize(pinWrapper, recWrapper))
    }

    /**
     * Фальшивый слот: по структуре и размеру неотличим от настоящего, но его
     * PIN и код восстановления никому не известны (сгенерированы случайно).
     * Нужен, чтобы нельзя было доказать существование второго тайника.
     */
    fun randomSlot(
        pinParams: KeyDerivation.Params = KeyDerivation.PIN_DEFAULT,
        recoveryParams: KeyDerivation.Params = KeyDerivation.RECOVERY_DEFAULT,
    ): Slot {
        val randomPin = randomDigits(10)
        val randomRec = randomDigits(16)
        val randomGate = SoftwareKeystoreGate(ByteArray(32).also { rng.nextBytes(it) })
        return createSlot(randomPin, randomRec, randomGate, pinParams, recoveryParams).first
    }

    // --- Внутреннее -------------------------------------------------------

    private fun wrap(
        masterKey: ByteArray,
        secret: CharArray,
        gate: KeystoreGate,
        params: KeyDerivation.Params,
        ad: ByteArray,
    ): Wrapper {
        val salt = ByteArray(SALT_SIZE).also { rng.nextBytes(it) }
        val derived = KeyDerivation.deriveKey(secret, salt, 32, params)
        val wrapKey = gate.harden(derived)
        val ct = aead(wrapKey).encrypt(masterKey, ad)
        return Wrapper(params, salt, ct)
    }

    private fun unwrap(
        wrapper: Wrapper,
        secret: CharArray,
        gate: KeystoreGate,
        ad: ByteArray,
    ): ByteArray? {
        val derived = KeyDerivation.deriveKey(secret, wrapper.salt, 32, wrapper.params)
        val wrapKey = gate.harden(derived)
        return try {
            aead(wrapKey).decrypt(wrapper.ciphertext, ad)
        } catch (e: Exception) {
            // Неверный ключ/повреждение → GeneralSecurityException. Трактуем как «не открылось».
            null
        }
    }

    private fun aead(key32: ByteArray): Aead {
        val params = AesGcmParameters.builder()
            .setKeySizeBytes(32)
            .setIvSizeBytes(12)
            .setTagSizeBytes(16)
            .setVariant(AesGcmParameters.Variant.NO_PREFIX)
            .build()
        val key = AesGcmKey.builder()
            .setParameters(params)
            .setKeyBytes(SecretBytes.copyFrom(key32, InsecureSecretKeyAccess.get()))
            .build()
        val handle = KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.importKey(key).withRandomId().makePrimary())
            .build()
        return handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private fun randomDigits(n: Int): CharArray =
        CharArray(n) { ('0' + rng.nextInt(10)) }

    // --- Сериализация -----------------------------------------------------

    private fun serialize(pin: Wrapper, rec: Wrapper): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeInt(MAGIC)
            writeWrapper(out, pin)
            writeWrapper(out, rec)
        }
        return bos.toByteArray()
    }

    private fun writeWrapper(out: DataOutputStream, w: Wrapper) {
        out.writeInt(w.params.memoryKiB)
        out.writeInt(w.params.iterations)
        out.writeInt(w.params.parallelism)
        out.writeShort(w.salt.size)
        out.write(w.salt)
        out.writeInt(w.ciphertext.size)
        out.write(w.ciphertext)
    }

    private fun deserialize(bytes: ByteArray): Pair<Wrapper, Wrapper>? {
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readInt() != MAGIC) return null
                val pin = readWrapper(input)
                val rec = readWrapper(input)
                pin to rec
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readWrapper(input: DataInputStream): Wrapper {
        val mem = input.readInt()
        val iter = input.readInt()
        val par = input.readInt()
        val salt = ByteArray(input.readUnsignedShort())
        input.readFully(salt)
        val ct = ByteArray(input.readInt())
        input.readFully(ct)
        return Wrapper(KeyDerivation.Params(mem, iter, par), salt, ct)
    }
}
