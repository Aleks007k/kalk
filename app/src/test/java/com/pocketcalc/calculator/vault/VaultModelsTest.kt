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

    @Test fun sizesAndTimestampsPreserved() {
        val e = entry(7).copy(size = 9_999_999_999L, addedAt = 1_700_000_000_000L)
        val back = roundTrip(VaultIndex.empty().add(e)).find("id7")!!
        assertEquals(9_999_999_999L, back.size)
        assertEquals(1_700_000_000_000L, back.addedAt)
    }
}
