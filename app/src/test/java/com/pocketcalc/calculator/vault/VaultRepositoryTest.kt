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
}
