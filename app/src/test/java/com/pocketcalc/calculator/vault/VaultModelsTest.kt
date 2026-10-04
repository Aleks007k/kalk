package com.pocketcalc.calculator.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VaultModelsTest {

    private fun entry(i: Int, thumb: String? = null) = VaultEntry(
        id = "id$i", name = "файл $i.jpg", mime = "image/jpeg",
        kind = EntryKind.PHOTO, size = i.toLong(), addedAt = i.toLong() * 1000,
        blobId = "blob$i", thumbId = thumb,
    )

    private fun roundTrip(index: VaultIndex): VaultIndex =
        VaultIndex.deserialize(VaultIndex.serialize(index))

    @Test fun emptyRoundTrip() {
        assertEquals(0, roundTrip(VaultIndex.empty()).entries.size)
    }

    @Test fun entryWithThumbRoundTrip() {
        val idx = VaultIndex.empty().add(entry(1, thumb = "thumb1"))
        val back = roundTrip(idx)
        assertEquals("thumb1", back.find("id1")!!.thumbId)
    }

    @Test fun entryWithoutThumbRoundTrip() {
        val back = roundTrip(VaultIndex.empty().add(entry(1, thumb = null)))
        assertNull(back.find("id1")!!.thumbId)
    }

    @Test fun unicodeAndLongNamesRoundTrip() {
        val longName = "очень длинное имя 🔒 ".repeat(50) + ".pdf"
        val e = entry(2).copy(name = longName, kind = EntryKind.PDF, mime = "application/pdf")
        val back = roundTrip(VaultIndex.empty().add(e))
        assertEquals(longName, back.find("id2")!!.name)
        assertEquals(EntryKind.PDF, back.find("id2")!!.kind)
    }

    @Test fun manyEntriesRoundTrip() {
        var idx = VaultIndex.empty()
        for (i in 0 until 500) idx = idx.add(entry(i))
        val back = roundTrip(idx)
        assertEquals(500, back.entries.size)
        assertEquals("файл 499.jpg", back.find("id499")!!.name)
    }

    @Test fun addRemoveFind() {
        val idx = VaultIndex.empty().add(entry(1)).add(entry(2))
        assertEquals(2, idx.entries.size)
        val removed = idx.remove("id1")
        assertNull(removed.find("id1"))
        assertEquals(1, removed.entries.size)
    }

    @Test fun unknownKindBecomesOther() {
        assertEquals(EntryKind.OTHER, EntryKind.fromId(999))
        assertEquals(EntryKind.OTHER, EntryKind.fromId(-1))
    }

    @Test fun readsVersion1Index() {
        // Оглавление старого формата (версия 1, как у тайника из версии 0.3.0)
        // должно читаться новой версией приложения.
        val bos = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bos).use { out ->
            out.writeInt(1)            // версия
            out.writeInt(1)            // одна запись
            out.writeUTF("id1")
            out.writeUTF("старое.jpg")
            out.writeUTF("image/jpeg")
            out.writeInt(EntryKind.PHOTO.ordinal)
            out.writeLong(123)
            out.writeLong(456)
            out.writeUTF("blob1")
            out.writeBoolean(true)
            out.writeUTF("thumb1")
        }
        val index = VaultIndex.deserialize(bos.toByteArray())
        val e = index.find("id1")!!
        assertEquals("старое.jpg", e.name)
        assertEquals("thumb1", e.thumbId)
        assertEquals("", e.sha256)
        assertEquals(0L, e.takenAt)
    }

    @Test fun readsEmptyVersion1Index() {
        val bos = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bos).use { out ->
            out.writeInt(1)
            out.writeInt(0)
        }
        assertEquals(0, VaultIndex.deserialize(bos.toByteArray()).entries.size)
    }

    @Test fun version2FieldsRoundTrip() {
        val e = entry(9).copy(sha256 = "ab".repeat(32), takenAt = 1_700_000_000_000L, durationMs = 61_000)
        val back = roundTrip(VaultIndex.empty().add(e)).find("id9")!!
        assertEquals("ab".repeat(32), back.sha256)
        assertEquals(1_700_000_000_000L, back.takenAt)
        assertEquals(61_000L, back.durationMs)
    }

    @Test fun version3FolderRoundTrip() {
        val e = entry(11).copy(origFolder = "DCIM/Camera/")
        val back = roundTrip(VaultIndex.empty().add(e)).find("id11")!!
        assertEquals("DCIM/Camera/", back.origFolder)
    }

    @Test fun readsVersion2Index() {
        // Оглавление версии 2 (как у тайника из версии 0.4.0) читается без потерь,
        // а исходная папка у таких записей неизвестна.
        val bos = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bos).use { out ->
            out.writeInt(2)            // версия
            out.writeInt(1)            // одна запись
            out.writeUTF("id1")
            out.writeUTF("IMG_1.jpg")
            out.writeUTF("image/jpeg")
            out.writeInt(EntryKind.PHOTO.ordinal)
            out.writeLong(123)
            out.writeLong(456)
            out.writeUTF("blob1")
            out.writeBoolean(false)
            out.writeUTF("cd".repeat(32))
            out.writeLong(1_700_000_000_000L)
            out.writeLong(0)
        }
        val e = VaultIndex.deserialize(bos.toByteArray()).find("id1")!!
        assertEquals("IMG_1.jpg", e.name)
        assertEquals("cd".repeat(32), e.sha256)
        assertEquals(1_700_000_000_000L, e.takenAt)
        assertEquals("", e.origFolder)
    }

    @Test fun replaceKeepsOrder() {
        val idx = VaultIndex.empty().add(entry(1)).add(entry(2)).add(entry(3))
        val replaced = idx.replace(entry(2).copy(name = "новое.jpg", size = 99))
        assertEquals(listOf("id1", "id2", "id3"), replaced.entries.map { it.id })
        assertEquals("новое.jpg", replaced.find("id2")!!.name)
        assertEquals(99L, replaced.find("id2")!!.size)
    }

    @Test fun version4FlagsRoundTrip() {
        val decoy = roundTrip(VaultIndex(listOf(entry(1)), isDecoy = true))
        assertEquals(true, decoy.isDecoy)
        assertEquals(false, decoy.hasDecoy)
        val real = roundTrip(VaultIndex(emptyList(), hasDecoy = true))
        assertEquals(false, real.isDecoy)
        assertEquals(true, real.hasDecoy)
        // Пометки не теряются при изменении списка.
        assertEquals(true, decoy.add(entry(2)).remove("id1").isDecoy)
    }

    @Test fun olderIndexesHaveNoFlags() {
        val bos = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bos).use { out ->
            out.writeInt(3)
            out.writeInt(0)
        }
        val index = VaultIndex.deserialize(bos.toByteArray())
        assertEquals(false, index.isDecoy)
        assertEquals(false, index.hasDecoy)
    }

    @Test fun blobIdsIncludeThumbnails() {
        val idx = VaultIndex.empty().add(entry(1, thumb = "t1")).add(entry(2))
        assertEquals(setOf("blob1", "t1", "blob2"), idx.blobIds())
    }

    @Test fun findBySha256() {
        val idx = VaultIndex.empty()
            .add(entry(1).copy(sha256 = "aa"))
            .add(entry(2).copy(sha256 = "bb"))
        assertEquals("id2", idx.findBySha256("bb")!!.id)
        assertNull(idx.findBySha256("cc"))
        assertNull("пустой отпечаток не совпадает ни с чем", idx.findBySha256(""))
    }

    @Test fun kindFromMime() {
        assertEquals(EntryKind.PHOTO, EntryKind.fromMime("image/heic"))
        assertEquals(EntryKind.VIDEO, EntryKind.fromMime("video/mp4"))
        assertEquals(EntryKind.PDF, EntryKind.fromMime("application/pdf"))
        assertEquals(EntryKind.NOTE, EntryKind.fromMime("text/plain"))
        assertEquals(EntryKind.OTHER, EntryKind.fromMime("application/zip"))
    }

    @Test fun sizesAndTimestampsPreserved() {
        val e = entry(7).copy(size = 9_999_999_999L, addedAt = 1_700_000_000_000L)
        val back = roundTrip(VaultIndex.empty().add(e)).find("id7")!!
        assertEquals(9_999_999_999L, back.size)
        assertEquals(1_700_000_000_000L, back.addedAt)
    }
}
