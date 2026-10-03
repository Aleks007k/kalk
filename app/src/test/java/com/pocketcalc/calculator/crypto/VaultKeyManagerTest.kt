package com.pocketcalc.calculator.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VaultKeyManagerTest {

    // Быстрые параметры Argon2 только для тестов.
    private val fast = KeyDerivation.Params(memoryKiB = 1024, iterations = 1, parallelism = 1)
    private fun gate(seed: Int = 1) = SoftwareKeystoreGate(ByteArray(32) { (it + seed).toByte() })

    private fun create(pin: String, rec: String, gate: KeystoreGate) =
        VaultKeyManager.createSlot(pin.toCharArray(), rec.toCharArray(), gate, fast, fast)

    @Test fun openWithCorrectPin() {
        val g = gate()
        val (slot, master) = create("12345678", "1111222233334444", g)
        val opened = VaultKeyManager.openWithPin(slot, "12345678".toCharArray(), g)
        assertNotNull(opened)
        assertArrayEquals(master, opened)
    }

    @Test fun wrongPinReturnsNull() {
        val g = gate()
        val (slot, _) = create("12345678", "1111222233334444", g)
        assertNull(VaultKeyManager.openWithPin(slot, "87654321".toCharArray(), g))
    }

    @Test fun openWithCorrectRecovery() {
        val g = gate()
        val (slot, master) = create("12345678", "1111222233334444", g)
        val opened = VaultKeyManager.openWithRecovery(slot, "1111222233334444".toCharArray())
        assertNotNull(opened)
        assertArrayEquals(master, opened)
    }

    @Test fun wrongRecoveryReturnsNull() {
        val g = gate()
        val (slot, _) = create("12345678", "1111222233334444", g)
        assertNull(VaultKeyManager.openWithRecovery(slot, "9999888877776666".toCharArray()))
    }

    @Test fun pinBoundToDevice() {
        // Правильный PIN, но «другой телефон» (другой gate) → не открывается.
        val (slot, _) = create("12345678", "1111222233334444", gate(seed = 1))
        assertNull(VaultKeyManager.openWithPin(slot, "12345678".toCharArray(), gate(seed = 99)))
    }

    @Test fun recoveryWorksRegardlessOfDevice() {
        // Код восстановления не зависит от чипа: открывает даже на «другом телефоне».
        val (slot, master) = create("12345678", "1111222233334444", gate(seed = 1))
        val opened = VaultKeyManager.openWithRecovery(slot, "1111222233334444".toCharArray())
        assertArrayEquals(master, opened)
    }

    @Test fun changePinKeepsMasterAndRecovery() {
        val g = gate()
        val (slot, master) = create("12345678", "1111222233334444", g)
        val newSlot = VaultKeyManager.changePin(slot, master, "55556666".toCharArray(), g, fast)
        // Старый PIN больше не работает, новый — работает, мастер тот же.
        assertNull(VaultKeyManager.openWithPin(newSlot, "12345678".toCharArray(), g))
        assertArrayEquals(master, VaultKeyManager.openWithPin(newSlot, "55556666".toCharArray(), g))
        // Код восстановления не тронут.
        assertArrayEquals(master, VaultKeyManager.openWithRecovery(newSlot, "1111222233334444".toCharArray()))
    }

    @Test fun changeRecoveryKeepsMasterAndPin() {
        val g = gate()
        val (slot, master) = create("12345678", "1111222233334444", g)
        val newSlot = VaultKeyManager.changeRecovery(slot, master, "0000000000000000".toCharArray(), fast)
        assertNull(VaultKeyManager.openWithRecovery(newSlot, "1111222233334444".toCharArray()))
        assertArrayEquals(master, VaultKeyManager.openWithRecovery(newSlot, "0000000000000000".toCharArray()))
        assertArrayEquals(master, VaultKeyManager.openWithPin(newSlot, "12345678".toCharArray(), g))
    }

    @Test fun serializationRoundTrip() {
        val g = gate()
        val (slot, master) = create("12345678", "1111222233334444", g)
        // Пересоздаём слот из его же байтов — должен открываться так же.
        val restored = VaultKeyManager.Slot(slot.bytes.copyOf())
        assertArrayEquals(master, VaultKeyManager.openWithPin(restored, "12345678".toCharArray(), g))
    }

    @Test fun randomSlotDoesNotOpenAndLooksLikeReal() {
        val real = create("12345678", "1111222233334444", gate()).first
        val fake = VaultKeyManager.randomSlot(fast, fast)
        // Фальшивый слот не открывается обычными попытками.
        assertNull(VaultKeyManager.openWithPin(fake, "12345678".toCharArray(), gate()))
        assertNull(VaultKeyManager.openWithRecovery(fake, "1111222233334444".toCharArray()))
        // И по размеру совпадает с настоящим (неотличим).
        assertEquals(real.bytes.size, fake.bytes.size)
    }

    @Test fun masterKeyIsThirtyTwoBytes() {
        val (_, master) = create("12345678", "1111222233334444", gate())
        assertEquals(32, master.size)
    }

    @Test fun twoSlotsHaveDifferentMasterKeys() {
        val (_, m1) = create("12345678", "1111222233334444", gate())
        val (_, m2) = create("12345678", "1111222233334444", gate())
        // Одинаковые PIN/код, но мастер-ключи случайны и независимы.
        assertFalse(m1.contentEquals(m2))
    }

    @Test fun tamperedRecoveryWrapperDetected() {
        // Последний байт слота — конец конверта восстановления. Его порча должна
        // ломать восстановление, но НЕ трогать независимый конверт PIN.
        val g = gate()
        val (slot, master) = create("12345678", "1111222233334444", g)
        val bytes = slot.bytes.copyOf()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        val tampered = VaultKeyManager.Slot(bytes)
        assertNull(VaultKeyManager.openWithRecovery(tampered, "1111222233334444".toCharArray()))
        assertArrayEquals(master, VaultKeyManager.openWithPin(tampered, "12345678".toCharArray(), g))
    }

    @Test fun garbageSlotReturnsNull() {
        val junk = VaultKeyManager.Slot(ByteArray(64) { 0 })
        assertNull(VaultKeyManager.openWithPin(junk, "12345678".toCharArray(), gate()))
        assertNull(VaultKeyManager.openWithRecovery(junk, "1111222233334444".toCharArray()))
    }

    @Test fun emptySlotReturnsNull() {
        val empty = VaultKeyManager.Slot(ByteArray(0))
        assertNull(VaultKeyManager.openWithPin(empty, "12345678".toCharArray(), gate()))
    }
}
