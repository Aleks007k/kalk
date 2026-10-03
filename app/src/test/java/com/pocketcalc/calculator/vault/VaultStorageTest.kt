package com.pocketcalc.calculator.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class VaultStorageTest {

    private val master = ByteArray(32) { (it + 3).toByte() }
    private fun newStorage(): Pair<VaultStorage, File> {
        val dir = Files.createTempDirectory("vault").toFile()
        return VaultStorage(dir) to dir
    }

    private fun entry(id: String, blobId: String) = VaultEntry(
        id = id, name = "фото $id.jpg", mime = "image/jpeg",
        kind = EntryKind.PHOTO, size = 123, addedAt = 1000L, blobId = blobId, thumbId = null,
    )

    @Test fun indexRoundTrip() {
        val (s, _) = newStorage()
        val index = VaultIndex.empty().add(entry("a", "blobA")).add(entry("b", "blobB"))
        s.saveIndex(master, index)
        val loaded = s.loadIndex(master)
        assertNotNull(loaded)
        assertEquals(2, loaded!!.entries.size)
        assertEquals("фото a.jpg", loaded.find("a")!!.name)
    }

    @Test fun loadIndexEmptyWhenNoFile() {
        val (s, _) = newStorage()
        assertEquals(0, s.loadIndex(master)!!.entries.size)
    }

    @Test fun wrongMasterKeyFailsToLoadIndex() {
        val (s, _) = newStorage()
        s.saveIndex(master, VaultIndex.empty().add(entry("a", "blobA")))
        val wrong = ByteArray(32) { (it + 9).toByte() }
        assertNull(s.loadIndex(wrong))
    }

    @Test fun saveIndexTwiceLoadsLatest() {
        val (s, _) = newStorage()
        s.saveIndex(master, VaultIndex.empty().add(entry("a", "blobA")))
        s.saveIndex(master, VaultIndex.empty().add(entry("b", "blobB")).add(entry("c", "blobC")))
        val loaded = s.loadIndex(master)!!
        assertEquals(2, loaded.entries.size)
        assertNull(loaded.find("a"))
        assertNotNull(loaded.find("c"))
    }

    @Test fun blobRoundTripSmall() {
        val (s, _) = newStorage()
        val data = "Привет, тайник".toByteArray()
        val id = s.addBlob(master, ByteArrayInputStream(data), data.size.toLong())
        val out = ByteArrayOutputStream()
        s.openBlob(master, id).use { it.copyTo(out) }
        assertArrayEquals(data, out.toByteArray())
    }

    @Test fun blobRoundTripLargeMultiSegment() {
        val (s, _) = newStorage()
        val data = ByteArray(3 * 1024 * 1024 + 777) { (it % 251).toByte() }
        val id = s.addBlob(master, ByteArrayInputStream(data), data.size.toLong())
        val out = ByteArrayOutputStream()
        s.openBlob(master, id).use { it.copyTo(out) }
        assertArrayEquals(data, out.toByteArray())
    }

    @Test fun addBlobVerifyRejectsWrongExpectedSizeAndCleansTmp() {
        val (s, dir) = newStorage()
        val data = ByteArray(1000) { 7 }
        var threw = false
        try {
            s.addBlob(master, ByteArrayInputStream(data), expectedSize = 9999)
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("проверка размера должна была отклонить файл", threw)
        // Мусор в tmp не остаётся.
        val tmpFiles = File(dir, "tmp").listFiles()?.size ?: 0
        assertEquals("tmp не должен содержать мусора", 0, tmpFiles)
    }

    @Test fun deleteBlobRemovesFile() {
        val (s, _) = newStorage()
        val id = s.addBlob(master, ByteArrayInputStream("x".toByteArray()))
        assertTrue(s.blobExists(id))
        assertTrue(s.deleteBlob(id))
        assertFalse(s.blobExists(id))
    }

    @Test fun cleanupTmpRemovesStrayFilesKeepsIndex() {
        val (s, dir) = newStorage()
        s.saveIndex(master, VaultIndex.empty().add(entry("a", "blobA")))
        // Имитируем оборванную операцию: мусорный файл в tmp/.
        val stray = File(File(dir, "tmp"), "blob-interrupted")
        stray.writeBytes(ByteArray(10))
        assertTrue(stray.exists())
        s.cleanupTmp()
        assertFalse("tmp должен быть очищен", stray.exists())
        // Оглавление цело.
        assertEquals(1, s.loadIndex(master)!!.entries.size)
    }

    @Test fun indexSurvivesReopeningStorage() {
        val (s, dir) = newStorage()
        s.saveIndex(master, VaultIndex.empty().add(entry("a", "blobA")))
        // Новый экземпляр на той же папке — данные на месте.
        val s2 = VaultStorage(dir)
        assertEquals(1, s2.loadIndex(master)!!.entries.size)
    }
}
