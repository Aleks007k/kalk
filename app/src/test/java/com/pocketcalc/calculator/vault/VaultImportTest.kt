package com.pocketcalc.calculator.vault

import com.pocketcalc.calculator.crypto.KeyDerivation
import com.pocketcalc.calculator.crypto.SoftwareKeystoreGate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class VaultImportTest {

    private val fast = KeyDerivation.Params(memoryKiB = 1024, iterations = 1, parallelism = 1)
    private val gate = SoftwareKeystoreGate(ByteArray(32) { (it + 1).toByte() })

    private fun newVault(): Triple<VaultRepository, VaultSession, File> {
        val dir = Files.createTempDirectory("import").toFile()
        val repo = VaultRepository(dir, fast, fast)
        val session = repo.setup("48291375".toCharArray(), "4821093755126604".toCharArray(), gate)
        return Triple(repo, session, dir)
    }

    private fun blobCount(dir: File) = File(dir, "blobs").listFiles()?.size ?: 0

    private fun photo(seed: Int, size: Int = 50_000) = ByteArray(size) { ((it * 31 + seed) % 251).toByte() }

    private fun sha256Hex(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun importAddsEntryWithMetadata() {
        val (repo, s, _) = newVault()
        val data = photo(1)
        val result = repo.importFile(
            s, ByteArrayInputStream(data),
            ImportMeta("IMG_0001.jpg", "image/jpeg", data.size.toLong(), takenAt = 1_700_000_000_000L),
            thumbnailJpeg = null,
        )
        assertTrue(result is ImportResult.Added)
        val entry = (result as ImportResult.Added).entry
        assertEquals("IMG_0001.jpg", entry.name)
        assertEquals(EntryKind.PHOTO, entry.kind)
        assertEquals(data.size.toLong(), entry.size)
        assertEquals(sha256Hex(data), entry.sha256)
        assertEquals(1_700_000_000_000L, entry.takenAt)
        assertEquals(1, repo.loadIndex(s)!!.entries.size)
    }

    @Test fun importedContentReadsBackExactly() {
        val (repo, s, _) = newVault()
        val data = photo(2, size = 2 * 1024 * 1024 + 333)   // несколько кусков
        val entry = (repo.importFile(s, ByteArrayInputStream(data), ImportMeta("v.mp4", "video/mp4"), null)
            as ImportResult.Added).entry
        val back = repo.openContent(s, entry).use { it.readBytes() }
        assertArrayEquals(data, back)
        assertEquals(EntryKind.VIDEO, entry.kind)
    }

    @Test fun sameFileTwiceIsNotDuplicated() {
        val (repo, s, dir) = newVault()
        val data = photo(3)
        val first = repo.importFile(s, ByteArrayInputStream(data), ImportMeta("a.jpg", "image/jpeg"), null)
        val second = repo.importFile(s, ByteArrayInputStream(data), ImportMeta("a (1).jpg", "image/jpeg"), null)
        assertTrue(first is ImportResult.Added)
        assertTrue(second is ImportResult.AlreadyThere)
        assertEquals((first as ImportResult.Added).entry.id, (second as ImportResult.AlreadyThere).entry.id)
        assertEquals(1, repo.loadIndex(s)!!.entries.size)
        assertEquals("лишний зашифрованный файл не остался", 1, blobCount(dir))
    }

    @Test fun thumbnailIsStoredAndReadBack() {
        val (repo, s, dir) = newVault()
        val thumb = ByteArray(3000) { (it % 200).toByte() }
        val entry = (repo.importFile(s, ByteArrayInputStream(photo(4)), ImportMeta("p.jpg", "image/jpeg"), thumb)
            as ImportResult.Added).entry
        assertArrayEquals(thumb, repo.readThumbnail(s, entry))
        assertEquals(2, blobCount(dir))   // сам файл + миниатюра
    }

    @Test fun noThumbnailMeansNull() {
        val (repo, s, _) = newVault()
        val entry = (repo.importFile(s, ByteArrayInputStream(photo(5)), ImportMeta("d.pdf", "application/pdf"), null)
            as ImportResult.Added).entry
        assertNull(repo.readThumbnail(s, entry))
        assertEquals(EntryKind.PDF, entry.kind)
    }

    @Test fun deleteRemovesEntryAndFiles() {
        val (repo, s, dir) = newVault()
        val entry = (repo.importFile(s, ByteArrayInputStream(photo(6)), ImportMeta("p.jpg", "image/jpeg"), ByteArray(10))
            as ImportResult.Added).entry
        assertEquals(2, blobCount(dir))
        assertTrue(repo.deleteEntry(s, entry.id))
        assertEquals(0, repo.loadIndex(s)!!.entries.size)
        assertEquals(0, blobCount(dir))
        assertFalse("повторное удаление ничего не делает", repo.deleteEntry(s, entry.id))
    }

    @Test fun deletedFileCanBeAddedAgain() {
        val (repo, s, _) = newVault()
        val data = photo(7)
        val entry = (repo.importFile(s, ByteArrayInputStream(data), ImportMeta("p.jpg", "image/jpeg"), null)
            as ImportResult.Added).entry
        repo.deleteEntry(s, entry.id)
        assertTrue(repo.importFile(s, ByteArrayInputStream(data), ImportMeta("p.jpg", "image/jpeg"), null)
            is ImportResult.Added)
    }

    @Test fun wrongExpectedSizeRejectsAndLeavesNothing() {
        val (repo, s, dir) = newVault()
        val data = photo(8)
        try {
            repo.importFile(s, ByteArrayInputStream(data), ImportMeta("p.jpg", "image/jpeg", size = 1), null)
            fail("несовпадение размера должно отменить добавление")
        } catch (e: IllegalStateException) {
            // ожидаемо
        }
        assertEquals(0, repo.loadIndex(s)!!.entries.size)
        assertEquals(0, blobCount(dir))
    }

    @Test fun importIntoClosedVaultIsRefused() {
        val (repo, s, dir) = newVault()
        s.close()
        try {
            repo.importFile(s, ByteArrayInputStream(photo(9)), ImportMeta("p.jpg", "image/jpeg"), null)
            fail("в закрытый тайник добавлять нельзя")
        } catch (e: IllegalStateException) {
            // ожидаемо
        }
        assertEquals(0, blobCount(dir))
    }

    @Test fun filesSurviveReopeningWithPin() {
        val (repo, s, dir) = newVault()
        val data = photo(10)
        repo.importFile(s, ByteArrayInputStream(data), ImportMeta("p.jpg", "image/jpeg"), null)
        s.close()
        // «Перезапуск»: новый репозиторий, вход по PIN.
        val again = VaultRepository(dir, fast, fast)
        val s2 = again.unlockWithPin("48291375".toCharArray(), gate)!!
        val entries = again.loadIndex(s2)!!.entries
        assertEquals(1, entries.size)
        assertArrayEquals(data, again.openContent(s2, entries[0]).use { it.readBytes() })
    }

    @Test fun manyFilesKeepOrderAndCount() {
        val (repo, s, _) = newVault()
        for (i in 0 until 20) {
            repo.importFile(s, ByteArrayInputStream(photo(100 + i, size = 1000)), ImportMeta("p$i.jpg", "image/jpeg"), null)
        }
        val names = repo.loadIndex(s)!!.entries.map { it.name }
        assertEquals((0 until 20).map { "p$it.jpg" }, names)
    }

    @Test fun importStoresOriginalFolder() {
        val (repo, s, _) = newVault()
        val entry = (repo.importFile(
            s, ByteArrayInputStream(photo(20)),
            ImportMeta("IMG_2.jpg", "image/jpeg", origFolder = "DCIM/Camera/"), null,
        ) as ImportResult.Added).entry
        assertEquals("DCIM/Camera/", entry.origFolder)
        assertEquals("DCIM/Camera/", repo.loadIndex(s)!!.find(entry.id)!!.origFolder)
    }

    @Test fun exportWritesExactCopy() {
        val (repo, s, _) = newVault()
        val data = photo(21, size = 3 * 1024 * 1024 + 17)   // несколько кусков
        val entry = (repo.importFile(s, ByteArrayInputStream(data), ImportMeta("v.mp4", "video/mp4"), null)
            as ImportResult.Added).entry
        val out = ByteArrayOutputStream()
        val sha = repo.exportContent(s, entry, out)
        assertArrayEquals(data, out.toByteArray())
        assertEquals(sha256Hex(data), sha)
    }

    @Test fun exportDetectsDamagedFile() {
        val (repo, s, dir) = newVault()
        val entry = (repo.importFile(s, ByteArrayInputStream(photo(22)), ImportMeta("p.jpg", "image/jpeg"), null)
            as ImportResult.Added).entry
        val blob = File(File(dir, "blobs"), entry.blobId)
        val bytes = blob.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        blob.writeBytes(bytes)
        try {
            repo.exportContent(s, entry, ByteArrayOutputStream())
            fail("повреждённый файл не должен выгружаться как целый")
        } catch (e: Exception) {
            // ожидаемо: расшифровка или проверка не прошла
        }
    }

    @Test fun exportChecksRecordedSizeAndHash() {
        val (repo, s, _) = newVault()
        val entry = (repo.importFile(s, ByteArrayInputStream(photo(23)), ImportMeta("p.jpg", "image/jpeg"), null)
            as ImportResult.Added).entry
        for (wrong in listOf(entry.copy(size = entry.size + 1), entry.copy(sha256 = "00".repeat(32)))) {
            try {
                repo.exportContent(s, wrong, ByteArrayOutputStream())
                fail("несовпадение с оглавлением должно останавливать выгрузку")
            } catch (e: IllegalStateException) {
                // ожидаемо
            }
        }
    }

    @Test fun exportFromClosedVaultIsRefused() {
        val (repo, s, _) = newVault()
        val entry = (repo.importFile(s, ByteArrayInputStream(photo(24)), ImportMeta("p.jpg", "image/jpeg"), null)
            as ImportResult.Added).entry
        s.close()
        try {
            repo.exportContent(s, entry, ByteArrayOutputStream())
            fail("из закрытого тайника выгружать нельзя")
        } catch (e: IllegalStateException) {
            // ожидаемо
        }
    }

    @Test fun sha256HelperMatchesJdk() {
        val data = photo(25, size = 200_000)
        assertEquals(sha256Hex(data), Sha256.of(ByteArrayInputStream(data)))
    }
}
