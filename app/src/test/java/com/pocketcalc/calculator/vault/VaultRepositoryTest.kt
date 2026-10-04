package com.pocketcalc.calculator.vault

import com.pocketcalc.calculator.crypto.KeyDerivation
import com.pocketcalc.calculator.crypto.SoftwareKeystoreGate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom

class VaultRepositoryTest {

    // Быстрые параметры Argon2 только для тестов.
    private val fast = KeyDerivation.Params(memoryKiB = 1024, iterations = 1, parallelism = 1)
    private val gate = SoftwareKeystoreGate(ByteArray(32) { (it + 1).toByte() })

    private val pin = "48291375"
    private val code = "4821093755126604"

    private fun newDir(): File = Files.createTempDirectory("repo").toFile()

    private fun repo(dir: File = newDir(), rng: SecureRandom = SecureRandom()) =
        VaultRepository(dir, fast, fast, rng)

    /** «Случайность», которая всегда выбирает заданный слот для настоящего тайника. */
    private fun fixedSlot(realInA: Boolean) = object : SecureRandom() {
        override fun nextBoolean(): Boolean = realInA
    }

    private fun setUp(r: VaultRepository): VaultSession =
        r.setup(pin.toCharArray(), code.toCharArray(), gate)

    @Test fun freshRepositoryNeedsSetup() {
        assertEquals(VaultRepository.State.NEEDS_SETUP, repo().state())
    }

    @Test fun setupMakesReadyAndReturnsOpenSession() {
        val r = repo()
        val s = setUp(r)
        assertTrue(s.isOpen)
        assertEquals(32, s.masterKey.size)
        assertEquals(VaultRepository.State.READY, r.state())
    }

    @Test fun setupCreatesTwoSameSizedSlotsAndTwoIndexes() {
        val dir = newDir()
        setUp(repo(dir))
        val a = File(dir, "slot_A.bin")
        val b = File(dir, "slot_B.bin")
        assertTrue(a.exists() && b.exists())
        // Настоящий слот и наполнитель неотличимы по размеру.
        assertEquals(a.length(), b.length())
        // Оглавлений тоже два — по их числу не понять, задан ли фальшивый PIN.
        assertEquals(2, dir.listFiles()!!.count { it.name.startsWith("index_") })
    }

    @Test fun setupTwiceIsRefused() {
        val r = repo()
        setUp(r)
        try {
            setUp(r)
            fail("повторная настройка должна быть запрещена")
        } catch (e: IllegalStateException) {
            // ожидаемо
        }
    }

    @Test fun correctPinOpensSameVault() {
        val r = repo()
        val created = setUp(r)
        val opened = r.unlockWithPin(pin.toCharArray(), gate)
        assertNotNull(opened)
        assertArrayEquals(created.masterKey, opened!!.masterKey)
        assertEquals(created.slot, opened.slot)
    }

    @Test fun wrongPinOpensNothing() {
        val r = repo()
        setUp(r)
        assertNull(r.unlockWithPin("48291376".toCharArray(), gate))
    }

    @Test fun pinOnOtherDeviceOpensNothing() {
        val r = repo()
        setUp(r)
        val otherPhone = SoftwareKeystoreGate(ByteArray(32) { (it + 77).toByte() })
        assertNull(r.unlockWithPin(pin.toCharArray(), otherPhone))
    }

    @Test fun recoveryCodeOpensSameVault() {
        val r = repo()
        val created = setUp(r)
        val opened = r.unlockWithRecovery(code.toCharArray())
        assertNotNull(opened)
        assertArrayEquals(created.masterKey, opened!!.masterKey)
    }

    @Test fun wrongRecoveryCodeOpensNothing() {
        val r = repo()
        setUp(r)
        assertNull(r.unlockWithRecovery("4821093755126605".toCharArray()))
    }

    @Test fun realVaultCanLandInEitherSlot() {
        for (realInA in listOf(true, false)) {
            val r = repo(rng = fixedSlot(realInA))
            val s = setUp(r)
            assertEquals(if (realInA) SlotPos.A else SlotPos.B, s.slot)
            // И открывается по PIN в той же ячейке.
            assertEquals(s.slot, r.unlockWithPin(pin.toCharArray(), gate)!!.slot)
        }
    }

    @Test fun changePinAfterRecovery() {
        val r = repo()
        val created = setUp(r)
        // Забыли PIN → вошли по коду → задали новый PIN.
        val viaRecovery = r.unlockWithRecovery(code.toCharArray())!!
        r.changePin(viaRecovery, "73915284".toCharArray(), gate)
        assertNull(r.unlockWithPin(pin.toCharArray(), gate))
        val withNewPin = r.unlockWithPin("73915284".toCharArray(), gate)
        assertArrayEquals(created.masterKey, withNewPin!!.masterKey)
        // Код восстановления продолжает работать.
        assertNotNull(r.unlockWithRecovery(code.toCharArray()))
    }

    @Test fun recoveryRestoresAccessAfterKeystoreLoss() {
        // Сценарий: «чип» сбросился (новые ворота). PIN больше не открывает,
        // но код восстановления открывает, а новый PIN работает с новыми воротами.
        val r = repo()
        val created = setUp(r)
        val newChip = SoftwareKeystoreGate(ByteArray(32) { (it + 200).toByte() })
        assertNull(r.unlockWithPin(pin.toCharArray(), newChip))
        val s = r.unlockWithRecovery(code.toCharArray())!!
        r.changePin(s, pin.toCharArray(), newChip)
        assertArrayEquals(created.masterKey, r.unlockWithPin(pin.toCharArray(), newChip)!!.masterKey)
    }

    @Test fun indexOfNewVaultIsEmptyAndWritable() {
        val r = repo()
        val s = setUp(r)
        val index = r.storage.loadIndex(s.masterKey)
        assertNotNull(index)
        assertEquals(0, index!!.entries.size)
    }

    @Test fun sessionCloseWipesKey() {
        val r = repo()
        val s = setUp(r)
        val keyRef = s.masterKey
        s.close()
        assertFalse(s.isOpen)
        assertTrue("ключ затёрт нулями", keyRef.all { it == 0.toByte() })
        try {
            s.masterKey
            fail("закрытый тайник не должен отдавать ключ")
        } catch (e: IllegalStateException) {
            // ожидаемо
        }
    }

    @Test fun keyCopySurvivesSessionClose() {
        // Долгая операция взяла копию ключа; тайник закрыли — копия цела.
        val r = repo()
        val s = setUp(r)
        val original = s.masterKey.copyOf()
        val copy = s.copyKey()
        s.close()
        assertArrayEquals(original, copy)
        try {
            s.copyKey()
            fail("после закрытия копию взять нельзя")
        } catch (e: IllegalStateException) {
            // ожидаемо
        }
    }

    @Test fun changePinOnClosedSessionFailsWithoutDamage() {
        // Если тайник закрыли до смены PIN, смена не проходит и слот не портится.
        val r = repo()
        val created = setUp(r)
        val master = created.masterKey.copyOf()
        val s = r.unlockWithPin(pin.toCharArray(), gate)!!
        s.close()
        try {
            r.changePin(s, "73915284".toCharArray(), gate)
            fail("смена PIN закрытого тайника должна быть отклонена")
        } catch (e: IllegalStateException) {
            // ожидаемо
        }
        assertArrayEquals(master, r.unlockWithPin(pin.toCharArray(), gate)!!.masterKey)
    }

    @Test fun interruptedSetupWithoutFilesIsRestarted() {
        val dir = newDir()
        File(dir, "slot_A.bin").writeBytes(ByteArray(10))   // половинчатая настройка
        val r = repo(dir)
        assertEquals(VaultRepository.State.NEEDS_SETUP, r.state())
        assertFalse(File(dir, "slot_A.bin").exists())
        setUp(r)
        assertEquals(VaultRepository.State.READY, r.state())
    }

    @Test fun singleSlotWithFilesIsNeverWiped() {
        // Если файлы есть, ничего не удаляем, даже если один слот пропал.
        val dir = newDir()
        val r = repo(dir)
        val s = setUp(r)
        r.storage.addBlob(s.masterKey, ByteArrayInputStream("фото".toByteArray()))
        File(dir, "slot_B.bin").delete()
        assertEquals(VaultRepository.State.READY, r.state())
        assertTrue("оставшийся слот не удалён", File(dir, "slot_A.bin").exists())
    }

    @Test fun repositoryReopensFromDisk() {
        val dir = newDir()
        val created = setUp(repo(dir))
        // «Перезапуск приложения»: новый объект на той же папке.
        val again = repo(dir)
        assertEquals(VaultRepository.State.READY, again.state())
        assertArrayEquals(created.masterKey, again.unlockWithPin(pin.toCharArray(), gate)!!.masterKey)
    }

    // --- Фальшивый PIN, смена PIN, новый код (этап 6) ---------------------

    private val decoyPin = "73915824"

    /** «Через две минуты»: все файлы на диске считаются давними и подлежат уборке. */
    private fun later() = System.currentTimeMillis() + 120_000

    private fun blobCount(dir: File) = File(dir, "blobs").listFiles()?.size ?: 0
    private fun indexCount(dir: File) = dir.listFiles()!!.count { it.name.startsWith("index_") }

    private fun addFile(r: VaultRepository, s: VaultSession, seed: Int): VaultEntry {
        val data = ByteArray(5000) { ((it * 7 + seed) % 251).toByte() }
        return (r.importFile(s, ByteArrayInputStream(data), ImportMeta("f$seed.jpg", "image/jpeg"), ByteArray(50) { seed.toByte() })
            as ImportResult.Added).entry
    }

    @Test fun decoyPinOpensSeparateEmptyVault() {
        val dir = newDir()
        val r = repo(dir)
        val real = setUp(r)
        addFile(r, real, 1)
        r.setDecoyPin(real, decoyPin.toCharArray(), gate, now = later())

        val decoy = r.unlockWithPin(decoyPin.toCharArray(), gate)!!
        assertTrue("фальшивый тайник в другом слоте", decoy.slot != real.slot)
        assertTrue(r.isDecoy(decoy))
        assertEquals(0, r.loadIndex(decoy)!!.entries.size)

        assertFalse(r.isDecoy(real))
        assertTrue(r.hasDecoy(real))
        val again = r.unlockWithPin(pin.toCharArray(), gate)!!
        assertEquals(real.slot, again.slot)
        assertEquals("файлы настоящего тайника целы", 1, r.loadIndex(again)!!.entries.size)
        assertEquals("оглавлений по-прежнему два", 2, indexCount(dir))
    }

    @Test fun decoyPinMustDifferFromRealPin() {
        val dir = newDir()
        val r = repo(dir)
        val real = setUp(r)
        val slotsBefore = File(dir, "slot_A.bin").readBytes().toList() to File(dir, "slot_B.bin").readBytes().toList()
        try {
            r.setDecoyPin(real, pin.toCharArray(), gate, now = later())
            fail("фальшивый PIN не может совпадать с настоящим")
        } catch (e: PinInUseException) {
            // ожидаемо
        }
        val slotsAfter = File(dir, "slot_A.bin").readBytes().toList() to File(dir, "slot_B.bin").readBytes().toList()
        assertEquals("слоты не тронуты", slotsBefore, slotsAfter)
        assertFalse(r.hasDecoy(real))
    }

    @Test fun newDecoyReplacesOldDecoyAndItsFiles() {
        val dir = newDir()
        val r = repo(dir)
        val real = setUp(r)
        val realEntry = addFile(r, real, 1)
        r.setDecoyPin(real, decoyPin.toCharArray(), gate, now = later())
        val decoy = r.unlockWithPin(decoyPin.toCharArray(), gate)!!
        addFile(r, decoy, 2)
        assertEquals(4, blobCount(dir))   // по файлу и миниатюре в каждом тайнике

        val newDecoyPin = "58203716"
        r.setDecoyPin(real, newDecoyPin.toCharArray(), gate, now = later())

        assertNull("старый фальшивый PIN больше ничего не открывает", r.unlockWithPin(decoyPin.toCharArray(), gate))
        val fresh = r.unlockWithPin(newDecoyPin.toCharArray(), gate)!!
        assertEquals(0, r.loadIndex(fresh)!!.entries.size)
        assertEquals("файлы прежнего фальшивого тайника удалены", 2, blobCount(dir))
        val realAgain = r.unlockWithPin(pin.toCharArray(), gate)!!
        val entry = r.loadIndex(realAgain)!!.find(realEntry.id)!!
        assertEquals(5000, r.openContent(realAgain, entry).use { it.readBytes() }.size)
        assertNotNull(r.readThumbnail(realAgain, entry))
        assertEquals(2, indexCount(dir))
    }

    @Test fun removeDecoyPinLeavesOnlyRealVault() {
        val dir = newDir()
        val r = repo(dir)
        val real = setUp(r)
        addFile(r, real, 1)
        r.setDecoyPin(real, decoyPin.toCharArray(), gate, now = later())
        r.removeDecoyPin(real, now = later())

        assertNull(r.unlockWithPin(decoyPin.toCharArray(), gate))
        assertFalse(r.hasDecoy(real))
        assertEquals(1, r.loadIndex(r.unlockWithPin(pin.toCharArray(), gate)!!)!!.entries.size)
        assertEquals(2, indexCount(dir))
        // Слоты снова одинакового размера: наполнитель неотличим от фальшивого тайника.
        assertEquals(File(dir, "slot_A.bin").length(), File(dir, "slot_B.bin").length())
    }

    @Test fun decoyVaultCannotReplaceTheRealOne() {
        val dir = newDir()
        val r = repo(dir)
        val real = setUp(r)
        addFile(r, real, 1)
        r.setDecoyPin(real, decoyPin.toCharArray(), gate, now = later())
        val decoy = r.unlockWithPin(decoyPin.toCharArray(), gate)!!
        try {
            r.setDecoyPin(decoy, "60418273".toCharArray(), gate, now = later())
            fail("из фальшивого тайника нельзя трогать второй слот")
        } catch (e: IllegalStateException) {
            // ожидаемо
        }
        try {
            r.removeDecoyPin(decoy, now = later())
            fail("из фальшивого тайника нельзя трогать второй слот")
        } catch (e: IllegalStateException) {
            // ожидаемо
        }
        val realAgain = r.unlockWithPin(pin.toCharArray(), gate)!!
        assertEquals("настоящий тайник цел", 1, r.loadIndex(realAgain)!!.entries.size)
    }

    @Test fun freshUnreferencedFilesSurviveCleanup() {
        val dir = newDir()
        val r = repo(dir)
        val real = setUp(r)
        // Файл, который «прямо сейчас» записывает другая операция.
        val inFlight = File(File(dir, "blobs"), "0123456789abcdef0123456789abcdef")
        inFlight.writeBytes(ByteArray(10))
        r.setDecoyPin(real, decoyPin.toCharArray(), gate)   // настоящее время
        assertTrue("свежий файл не удалён", inFlight.exists())
    }

    @Test fun changePinCannotTakeTheDecoyPin() {
        val r = repo()
        val real = setUp(r)
        r.setDecoyPin(real, decoyPin.toCharArray(), gate, now = later())
        try {
            r.changePin(real, decoyPin.toCharArray(), gate)
            fail("два тайника с одним PIN быть не может")
        } catch (e: PinInUseException) {
            // ожидаемо
        }
        assertEquals(real.slot, r.unlockWithPin(pin.toCharArray(), gate)!!.slot)
    }

    @Test fun decoyCanChangeItsOwnPin() {
        val r = repo()
        val real = setUp(r)
        r.setDecoyPin(real, decoyPin.toCharArray(), gate, now = later())
        val decoy = r.unlockWithPin(decoyPin.toCharArray(), gate)!!
        val newDecoyPin = "64028175"
        r.changePin(decoy, newDecoyPin.toCharArray(), gate)
        assertEquals(decoy.slot, r.unlockWithPin(newDecoyPin.toCharArray(), gate)!!.slot)
        assertEquals(real.slot, r.unlockWithPin(pin.toCharArray(), gate)!!.slot)
    }

    @Test fun newRecoveryCodeReplacesOldOne() {
        val r = repo()
        val real = setUp(r)
        val newCode = "7351902846130579"
        r.changeRecovery(real, newCode.toCharArray())
        assertNull("старый код больше не работает", r.unlockWithRecovery(code.toCharArray()))
        assertEquals(real.slot, r.unlockWithRecovery(newCode.toCharArray())!!.slot)
        assertEquals("PIN по-прежнему работает", real.slot, r.unlockWithPin(pin.toCharArray(), gate)!!.slot)
    }
}
