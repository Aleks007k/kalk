package com.pocketcalc.calculator.vault

import com.pocketcalc.calculator.crypto.KeyDerivation
import com.pocketcalc.calculator.crypto.KeystoreGate
import com.pocketcalc.calculator.crypto.SoftwareKeystoreGate
import com.pocketcalc.calculator.crypto.VaultKeyManager
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture

/** Ячейка, в которой лежит тайник. Какая из них настоящая — нигде не записано. */
enum class SlotPos { A, B }

/**
 * Открытый тайник. Держит мастер-ключ в памяти, пока тайник открыт.
 * [close] затирает ключ нулями — после этого тайник снова заперт.
 */
class VaultSession internal constructor(val slot: SlotPos, masterKey: ByteArray) {
    private var key: ByteArray? = masterKey

    val isOpen: Boolean get() = key != null

    /** Мастер-ключ открытого тайника. Не изменяйте и не храните копий. */
    val masterKey: ByteArray get() = key ?: throw IllegalStateException("тайник закрыт")

    fun close() {
        key?.fill(0)
        key = null
    }
}

/**
 * Тайники на диске: два слота (A и B) и общее хранилище файлов.
 *
 * При первой настройке настоящий тайник случайно попадает в слот A или B,
 * а во второй слот кладётся «наполнитель», неотличимый от настоящего тайника
 * (со своим пустым оглавлением). Поэтому ни по положению, ни по числу файлов
 * нельзя понять, какой слот настоящий и задан ли фальшивый PIN.
 *
 * Чистая логика без Android: покрыта JVM-тестами. Ворота защищённого чипа
 * ([KeystoreGate]) передаются снаружи.
 */
class VaultRepository(
    private val dir: File,
    private val pinParams: KeyDerivation.Params = KeyDerivation.PIN_DEFAULT,
    private val recoveryParams: KeyDerivation.Params = KeyDerivation.RECOVERY_DEFAULT,
    private val rng: SecureRandom = SecureRandom(),
) {

    enum class State { NEEDS_SETUP, READY }

    init {
        dir.mkdirs()
    }

    val storage = VaultStorage(dir)
    private val tmpDir = File(dir, "tmp")

    private fun slotFile(pos: SlotPos) = File(dir, "slot_${pos.name}.bin")

    /**
     * Нужна ли первичная настройка.
     *
     * Если найден только один слот — значит, настройку прервали на середине.
     * Если при этом нет ни одного файла, половинчатый слот удаляется и настройка
     * начинается заново. Если же файлы есть — ничего не трогаем.
     */
    fun state(): State {
        val a = slotFile(SlotPos.A).exists()
        val b = slotFile(SlotPos.B).exists()
        return when {
            a && b -> State.READY
            !a && !b -> State.NEEDS_SETUP
            storage.hasBlobs() -> State.READY
            else -> {
                slotFile(SlotPos.A).delete()
                slotFile(SlotPos.B).delete()
                State.NEEDS_SETUP
            }
        }
    }

    /**
     * Первичная настройка: создаёт настоящий тайник под [pin] и [recoveryCode]
     * и «наполнитель» во втором слоте. Возвращает открытый настоящий тайник.
     */
    fun setup(pin: CharArray, recoveryCode: CharArray, gate: KeystoreGate): VaultSession {
        check(state() == State.NEEDS_SETUP) { "тайник уже настроен" }
        val realPos = if (rng.nextBoolean()) SlotPos.A else SlotPos.B

        // Настоящий слот и наполнитель создаются параллельно: это самая долгая часть.
        val filler = CompletableFuture.supplyAsync { createFiller() }
        val (realSlot, realMaster) = VaultKeyManager.createSlot(pin, recoveryCode, gate, pinParams, recoveryParams)
        val (fillerSlot, fillerMaster) = filler.join()

        try {
            storage.saveIndex(realMaster, VaultIndex.empty())
            storage.saveIndex(fillerMaster, VaultIndex.empty())
            writeSlot(other(realPos), fillerSlot)
            writeSlot(realPos, realSlot)
        } finally {
            fillerMaster.fill(0)
        }
        return VaultSession(realPos, realMaster)
    }

    /** Открывает тайник по PIN. Проверяет оба слота всегда. null — PIN не подошёл. */
    fun unlockWithPin(pin: CharArray, gate: KeystoreGate): VaultSession? =
        pick(forBothSlots { slot -> VaultKeyManager.openWithPin(slot, pin, gate) })

    /** Открывает тайник по коду восстановления. Проверяет оба слота всегда. */
    fun unlockWithRecovery(code: CharArray): VaultSession? =
        pick(forBothSlots { slot -> VaultKeyManager.openWithRecovery(slot, code) })

    /** Меняет PIN открытого тайника. Мастер-ключ и код восстановления не меняются. */
    fun changePin(session: VaultSession, newPin: CharArray, gate: KeystoreGate) {
        val slot = readSlot(session.slot) ?: error("слот не найден")
        val updated = VaultKeyManager.changePin(slot, session.masterKey, newPin, gate, pinParams)
        writeSlot(session.slot, updated)
    }

    /** Удаляет мусор от прерванных операций. Вызывать при запуске приложения. */
    fun cleanupTmp() = storage.cleanupTmp()

    // --- Внутреннее -------------------------------------------------------

    private fun createFiller(): Pair<VaultKeyManager.Slot, ByteArray> {
        val pin = CharArray(SecretInput.PIN_MAX) { '0' + rng.nextInt(10) }
        val code = CharArray(SecretInput.RECOVERY_LENGTH) { '0' + rng.nextInt(10) }
        val gate = SoftwareKeystoreGate(ByteArray(32).also { rng.nextBytes(it) })
        return try {
            VaultKeyManager.createSlot(pin, code, gate, pinParams, recoveryParams)
        } finally {
            pin.fill('0')
            code.fill('0')
        }
    }

    /**
     * Выполняет [op] для обоих слотов параллельно и дожидается обоих результатов.
     * Время ответа не зависит от того, какой слот подошёл (и подошёл ли вообще).
     */
    private fun forBothSlots(op: (VaultKeyManager.Slot) -> ByteArray?): Pair<ByteArray?, ByteArray?> {
        val slotA = readSlot(SlotPos.A)
        val slotB = readSlot(SlotPos.B)
        val futureB = CompletableFuture.supplyAsync { slotB?.let { safely { op(it) } } }
        val a = slotA?.let { safely { op(it) } }
        val b = futureB.join()
        return a to b
    }

    private fun pick(results: Pair<ByteArray?, ByteArray?>): VaultSession? {
        val (a, b) = results
        return when {
            a != null -> {
                b?.fill(0)
                VaultSession(SlotPos.A, a)
            }
            b != null -> VaultSession(SlotPos.B, b)
            else -> null
        }
    }

    private inline fun safely(block: () -> ByteArray?): ByteArray? =
        try {
            block()
        } catch (e: Exception) {
            null
        }

    private fun readSlot(pos: SlotPos): VaultKeyManager.Slot? {
        val f = slotFile(pos)
        return if (f.exists()) VaultKeyManager.Slot(f.readBytes()) else null
    }

    private fun writeSlot(pos: SlotPos, slot: VaultKeyManager.Slot) {
        AtomicFiles.writeBytes(slotFile(pos), tmpDir, slot.bytes)
    }

    private fun other(pos: SlotPos) = if (pos == SlotPos.A) SlotPos.B else SlotPos.A
}
