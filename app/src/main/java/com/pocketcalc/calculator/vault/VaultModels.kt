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

        /** Тип по MIME: image/… → фото, video/… → видео, и т.д. */
        fun fromMime(mime: String): EntryKind = when {
            mime.startsWith("image/") -> PHOTO
            mime.startsWith("video/") -> VIDEO
            mime == "application/pdf" -> PDF
            mime == "text/plain" -> NOTE
            else -> OTHER
        }
    }
}

/**
 * Запись оглавления об одном спрятанном файле.
 *
 * Хранит исходное имя, тип, размер и даты — всё это лежит ТОЛЬКО в
 * зашифрованном оглавлении, а сам файл на диске называется случайным
 * [blobId], по которому ничего не понять.
 *
 * [sha256] — отпечаток содержимого: по нему тайник узнаёт уже добавленный
 * файл и не добавляет его второй раз. У записей старого формата он пустой.
 *
 * [origFolder] — папка, где лежал оригинал (например, `DCIM/Camera/`): туда
 * файл возвращается при восстановлении. Пусто — неизвестно.
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
    val sha256: String = "",
    val takenAt: Long = 0,
    val durationMs: Long = 0,
    val origFolder: String = "",
)

/**
 * Оглавление одного тайника: список записей и две пометки.
 *
 * [isDecoy] — это фальшивый тайник (его создал настоящий). В нём не
 * показывается настройка фальшивого PIN: иначе тот, кому его открыли,
 * мог бы через неё стереть настоящий тайник.
 * [hasDecoy] — у этого (настоящего) тайника задан фальшивый PIN.
 *
 * Пометки лежат внутри зашифрованного оглавления: без ключа их не прочитать.
 */
data class VaultIndex(
    val entries: List<VaultEntry>,
    val isDecoy: Boolean = false,
    val hasDecoy: Boolean = false,
) {

    fun add(entry: VaultEntry): VaultIndex = copy(entries = entries + entry)

    fun remove(entryId: String): VaultIndex = copy(entries = entries.filterNot { it.id == entryId })

    /** Та же запись (по id) с новыми данными; порядок записей не меняется. */
    fun replace(entry: VaultEntry): VaultIndex = copy(entries = entries.map { if (it.id == entry.id) entry else it })

    /** Все зашифрованные файлы этого тайника: сами файлы и их миниатюры. */
    fun blobIds(): Set<String> = entries.flatMap { listOfNotNull(it.blobId, it.thumbId) }.toSet()

    fun find(entryId: String): VaultEntry? = entries.firstOrNull { it.id == entryId }

    /** Запись с таким же содержимым, если она уже есть. */
    fun findBySha256(sha256: String): VaultEntry? =
        if (sha256.isEmpty()) null else entries.firstOrNull { it.sha256 == sha256 }

    companion object {
        /**
         * Версии формата:
         *  1 — первая (этап 2–3);
         *  2 — добавлены отпечаток содержимого, дата съёмки и длительность видео;
         *  3 — добавлена исходная папка файла (для восстановления);
         *  4 — пометки «фальшивый тайник» и «задан фальшивый PIN».
         * Старые версии читаются всегда: обновление приложения не должно ломать тайник.
         */
        private const val VERSION = 4

        private const val FLAG_DECOY = 1
        private const val FLAG_HAS_DECOY = 2

        fun empty(): VaultIndex = VaultIndex(emptyList())

        /** Превращает оглавление в байты (до шифрования). Всегда в новейшем формате. */
        fun serialize(index: VaultIndex): ByteArray {
            val bos = ByteArrayOutputStream()
            DataOutputStream(bos).use { out ->
                out.writeInt(VERSION)
                // версия 4
                out.writeInt(
                    (if (index.isDecoy) FLAG_DECOY else 0) or (if (index.hasDecoy) FLAG_HAS_DECOY else 0),
                )
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
                    // версия 2
                    out.writeUTF(e.sha256)
                    out.writeLong(e.takenAt)
                    out.writeLong(e.durationMs)
                    // версия 3
                    out.writeUTF(e.origFolder)
                }
            }
            return bos.toByteArray()
        }

        /** Восстанавливает оглавление из байтов (после расшифровки). Понимает версии 1–4. */
        fun deserialize(bytes: ByteArray): VaultIndex {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val version = input.readInt()
                require(version in 1..VERSION) { "неизвестная версия оглавления: $version" }
                val flags = if (version >= 4) input.readInt() else 0
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
                    var sha256 = ""
                    var takenAt = 0L
                    var durationMs = 0L
                    if (version >= 2) {
                        sha256 = input.readUTF()
                        takenAt = input.readLong()
                        durationMs = input.readLong()
                    }
                    val origFolder = if (version >= 3) input.readUTF() else ""
                    list.add(
                        VaultEntry(
                            id, name, mime, kind, size, addedAt, blobId, thumbId,
                            sha256, takenAt, durationMs, origFolder,
                        )
                    )
                }
                return VaultIndex(
                    entries = list,
                    isDecoy = flags and FLAG_DECOY != 0,
                    hasDecoy = flags and FLAG_HAS_DECOY != 0,
                )
            }
        }
    }
}
