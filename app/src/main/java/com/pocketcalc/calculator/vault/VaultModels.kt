package com.pocketcalc.calculator.vault

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Тип спрятанного файла — определяет, как его показывать. */
enum class EntryKind {
    PHOTO, VIDEO, PDF, NOTE, OTHER;

    companion object {
        fun fromId(id: Int): EntryKind = entries.getOrElse(id) { OTHER }
    }
}

/**
 * Запись оглавления об одном спрятанном файле.
 *
 * Хранит исходное имя, тип и размер — всё это лежит ТОЛЬКО в зашифрованном
 * оглавлении, а сам файл на диске называется случайным [blobId], по которому
 * ничего не понять.
 */
data class VaultEntry(
    val id: String,
    val name: String,
    val mime: String,
    val kind: EntryKind,
    val size: Long,
    val addedAt: Long,
    val blobId: String,
    val thumbId: String?,
)

/** Оглавление одного тайника: список записей. */
data class VaultIndex(val entries: List<VaultEntry>) {

    fun add(entry: VaultEntry): VaultIndex = VaultIndex(entries + entry)

    fun remove(entryId: String): VaultIndex = VaultIndex(entries.filterNot { it.id == entryId })

    fun find(entryId: String): VaultEntry? = entries.firstOrNull { it.id == entryId }

    companion object {
        private const val VERSION = 1

        fun empty(): VaultIndex = VaultIndex(emptyList())

        /** Превращает оглавление в байты (до шифрования). */
        fun serialize(index: VaultIndex): ByteArray {
            val bos = ByteArrayOutputStream()
            DataOutputStream(bos).use { out ->
                out.writeInt(VERSION)
                out.writeInt(index.entries.size)
                for (e in index.entries) {
                    out.writeUTF(e.id)
                    out.writeUTF(e.name)
                    out.writeUTF(e.mime)
                    out.writeInt(e.kind.ordinal)
                    out.writeLong(e.size)
                    out.writeLong(e.addedAt)
                    out.writeUTF(e.blobId)
                    out.writeBoolean(e.thumbId != null)
                    if (e.thumbId != null) out.writeUTF(e.thumbId)
                }
            }
            return bos.toByteArray()
        }

        /** Восстанавливает оглавление из байтов (после расшифровки). */
        fun deserialize(bytes: ByteArray): VaultIndex {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val version = input.readInt()
                require(version == VERSION) { "неизвестная версия оглавления: $version" }
                val count = input.readInt()
                val list = ArrayList<VaultEntry>(count)
                repeat(count) {
                    val id = input.readUTF()
                    val name = input.readUTF()
                    val mime = input.readUTF()
                    val kind = EntryKind.fromId(input.readInt())
                    val size = input.readLong()
                    val addedAt = input.readLong()
                    val blobId = input.readUTF()
                    val hasThumb = input.readBoolean()
                    val thumbId = if (hasThumb) input.readUTF() else null
                    list.add(VaultEntry(id, name, mime, kind, size, addedAt, blobId, thumbId))
                }
                return VaultIndex(list)
            }
        }
    }
}
