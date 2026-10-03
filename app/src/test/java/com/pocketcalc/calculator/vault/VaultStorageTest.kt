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

    @Test fun differentMastersHaveSeparateIndexes() {
        // У каждого тайника своё оглавление: чужой ключ видит пустой тайник,
        // а не чужие записи.
        val (s, _) = newStorage()
        s.saveIndex(master, VaultIndex.empty().add(entry("a", "blobA")))
        val other = ByteArray(32) { (it + 9).toByte() }
        assertEquals(0, s.loadIndex(other)!!.entries.size)
        s.saveIndex(other, VaultIndex.empty().add(entry("x", "blobX")).add(entry("y", "blobY")))
        assertEquals(1, s.loadIndex(master)!!.entries.size)
        assertEquals(2, s.loadIndex(other)!!.entries.size)
    }

    @Test fun indexFileNameDoesNotRevealOwner() {
        // В папке нет файла с «говорящим» именем — только index_<случайный вид>.bin.
        val (s, dir) = newStorage()
        s.saveIndex(master, VaultIndex.empty())
        val names = dir.listFiles()!!.map { it.name }.filter { it.startsWith("index") }
        assertEquals(1, names.size)
        assertTrue(names[0].matches(Regex("index_[0-9a-f]{32}\\.bin")))
    }

    @Test fun corruptedIndexReturnsNull() {
        val (s, dir) = newStorage()
        s.saveIndex(master, VaultIndex.empty().add(entry("a", "blobA")))
        val indexFile = dir.listFiles()!!.first { it.name.startsWith("index_") }
        val bytes = indexFile.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
        indexFile.writeBytes(bytes)
        assertNull(s.loadIndex(master))
    }

    @Test fun storeBlobReportsSizeAndSha256() {
        val (s, _) = newStorage()
        val data = ByteArray(1_500_000) { (it % 241).toByte() }
        val stored = s.storeBlob(master, ByteArrayInputStream(data))
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }
        assertEquals(data.size.toLong(), stored.size)
        assertEquals(expected, stored.sha256)
        assertTrue(s.blobExists(stored.id))
    }

    @Test fun hasBlobsReflectsContent() {
        val (s, _) = newStorage()
        assertFalse(s.hasBlobs())
        val id = s.addBlob(master, ByteArrayInputStream("x".toByteArray()))
        assertTrue(s.hasBlobs())
        s.deleteBlob(id)
        assertFalse(s.hasBlobs())
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

    @Test fun emptyBlobRoundTrip() {
        val (s, _) = newStorage()
        val id = s.addBlob(master, ByteArrayInputStream(ByteArray(0)), 0)
        val out = ByteArrayOutputStream()
        s.openBlob(master, id).use { it.copyTo(out) }
        assertEquals(0, out.size())
    }

    @Test fun openNonexistentBlobThrows() {
        val (s, _) = newStorage()
        var threw = false
        try {
            s.openBlob(master, "doesnotexist").use { it.readBytes() }
        } catch (e: Exception) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test fun blobBoundToItsId() {
        // Blob зашифрован с привязкой к своему id. Переименованный файl не читается.
        val (s, dir) = newStorage()
        val data = ByteArray(2000) { (it % 255).toByte() }
        val id = s.addBlob(master, ByteArrayInputStream(data))
        val blobs = File(dir, "blobs")
        val renamed = "ffffffffffffffffffffffffffffffff"
        File(blobs, id).copyTo(File(blobs, renamed))
        var threw = false
        try {
            s.openBlob(master, renamed).use { it.readBytes() }
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("blob не должен читаться под чужим id", threw)
    }

    @Test fun indexReflectsAddAndRemove() {
        val (s, _) = newStorage()
        s.saveIndex(master, VaultIndex.empty().add(entry("a", "blobA")))
        val afterAdd = s.loadIndex(master)!!.add(entry("b", "blobB"))
        s.saveIndex(master, afterAdd)
        assertEquals(2, s.loadIndex(master)!!.entries.size)
        val afterRemove = s.loadIndex(master)!!.remove("a")
        s.saveIndex(master, afterRemove)
        val loaded = s.loadIndex(master)!!
        assertEquals(1, loaded.entries.size)
        assertNull(loaded.find("a"))
    }
}
