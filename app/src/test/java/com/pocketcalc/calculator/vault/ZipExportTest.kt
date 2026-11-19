package com.pocketcalc.calculator.vault

import com.pocketcalc.calculator.crypto.KeyDerivation
import com.pocketcalc.calculator.crypto.SoftwareKeystoreGate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream

class ZipExportTest {

    private val fast = KeyDerivation.Params(memoryKiB = 1024, iterations = 1, parallelism = 1)
    private val gate = SoftwareKeystoreGate(ByteArray(32) { (it + 1).toByte() })

    private fun newVault(): Pair<VaultRepository, VaultSession> {
        val repo = VaultRepository(Files.createTempDirectory("zip").toFile(), fast, fast)
        return repo to repo.setup("48291375".toCharArray(), "4821093755126604".toCharArray(), gate)
    }

    private fun add(repo: VaultRepository, s: VaultSession, name: String, mime: String, data: ByteArray, folderId: String? = null) =
        (repo.importFile(s, ByteArrayInputStream(data), ImportMeta(name, mime), null, dedupe = false, folderId = folderId)
            as ImportResult.Added).entry

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                result[e.name] = zin.readBytes()
            }
        }
        return result
    }

    @Test fun archiveHoldsDecryptedFilesWithFoldersAndUniqueNames() {
        val (repo, s) = newVault()
        val folder = repo.createFolder(s, "Отпуск")
        val photo = ByteArray(300_000) { (it * 7 % 251).toByte() }
        val photo2 = ByteArray(1_000) { (it * 3 % 251).toByte() }
        val note = "Привет, заметка!".repeat(100).toByteArray()
        val video = ByteArray(2 * 1024 * 1024 + 17) { (it * 13 % 251).toByte() }
        val entries = listOf(
            add(repo, s, "IMG_1.jpg", "image/jpeg", photo),
            add(repo, s, "IMG_1.jpg", "image/jpeg", photo2),
            add(repo, s, "Заметка.txt", "text/plain", note),
            add(repo, s, "море.mp4", "video/mp4", video, folderId = folder.id),
        )
        val out = ByteArrayOutputStream()
        var progress = 0
        ZipExport.write(repo, s, entries, listOf(folder), out) { progress = it }

        assertEquals(4, progress)
        val files = readZip(out.toByteArray())
        assertEquals(listOf("IMG_1.jpg", "IMG_1 (2).jpg", "Заметка.txt", "Отпуск/море.mp4"), files.keys.toList())
        assertArrayEquals(photo, files["IMG_1.jpg"])
        assertArrayEquals(photo2, files["IMG_1 (2).jpg"])
        assertArrayEquals(note, files["Заметка.txt"])
        assertArrayEquals(video, files["Отпуск/море.mp4"])
    }

    @Test fun namesCannotEscapeIntoOtherFolders() {
        assertEquals("a_b_c.jpg", ZipExport.safeName("a/b\\c.jpg"))
        assertEquals("_", ZipExport.safeName(".."))
        val used = HashSet<String>()
        assertEquals("x.jpg", ZipExport.uniquePath("", "x.jpg", used))
        assertEquals("X (2).JPG", ZipExport.uniquePath("", "X.JPG", used))
        assertEquals("noext (2)", ZipExport.uniquePath("", "noext", used.apply { add("noext") }))
        assertTrue(used.contains("x (2).jpg"))
    }
}
