package com.pocketcalc.calculator.vault

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Тип спрятанного файла — определяет, как его показывать.
 * В оглавлении хранится номер типа, поэтому новые типы — только в конец.
 */
enum class EntryKind {
    PHOTO, VIDEO, PDF, NOTE, OTHER,

    /** Звук: AAC, MP3, M4A, OGG/Opus, WAV, FLAC, AMR (с версии 0.11.0). */
    AUDIO;

    companion object {
        /** Расширения звуковых файлов — когда MIME ничего не говорит. */
        private val AUDIO_EXTENSIONS = setOf("aac", "m4a", "mp3", "ogg", "oga", "opus", "wav", "flac", "amr", "3ga", "mka")

        fun fromId(id: Int): EntryKind = entries.getOrElse(id) { OTHER }

        /** Тип по MIME: image/… → фото, video/… → видео, audio/… → звук и т.д. */
        fun fromMime(mime: String): EntryKind = when {
            mime.startsWith("image/") -> PHOTO
            mime.startsWith("video/") -> VIDEO
            mime.startsWith("audio/") -> AUDIO
            mime == "application/pdf" -> PDF
            mime == "text/plain" -> NOTE
            else -> OTHER
        }

        /**
         * Тип по MIME, а если он ничего не говорит (например,
         * «application/octet-stream»), — по расширению: «запись.aac» → звук.
         */
        fun of(mime: String, name: String): EntryKind {
            val byMime = fromMime(mime)
            if (byMime != OTHER) return byMime
            val ext = name.substringAfterLast('.', "").lowercase()
            return if (ext in AUDIO_EXTENSIONS) AUDIO else OTHER
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
 *
 * [folderId] — папка тайника, в которой лежит файл (см. [VaultFolder]);
 * null — файл в общей сетке.
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
    val folderId: String? = null,
)

/** Папка в разделе «Фото и видео». Название — только в зашифрованном оглавлении. */
data class VaultFolder(
    val id: String,
    val name: String,
    val createdAt: Long,
) {
    companion object {
        /** Самое длинное название папки. */
        const val MAX_NAME = 40

        /** Название в одну строку, без лишних пробелов и не длиннее [MAX_NAME]. */
        fun cleanName(name: String): String {
            var s = name.replace(Regex("\\s+"), " ").trim()
            if (s.length > MAX_NAME) {
                s = s.take(MAX_NAME)
                // Не разрезаем пару символов (например, эмодзи) пополам.
                if (s.last().isHighSurrogate()) s = s.dropLast(1)
            }
            return s.trim()
        }
    }
}

/**
 * Оглавление одного тайника: список записей, папки и две пометки.
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
    val folders: List<VaultFolder> = emptyList(),
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

    fun findFolder(folderId: String): VaultFolder? = folders.firstOrNull { it.id == folderId }

    fun addFolder(folder: VaultFolder): VaultIndex = copy(folders = folders + folder)

    fun renameFolder(folderId: String, name: String): VaultIndex =
        copy(folders = folders.map { if (it.id == folderId) it.copy(name = name) else it })

    /** Убирает папку. Файлы из неё не удаляются — они возвращаются в общую сетку. */
    fun removeFolder(folderId: String): VaultIndex = copy(
        folders = folders.filterNot { it.id == folderId },
        entries = entries.map { if (it.folderId == folderId) it.copy(folderId = null) else it },
    )

    /** Переносит запись в папку ([folderId] = null — в общую сетку). */
    fun moveEntry(entryId: String, folderId: String?): VaultIndex =
        copy(entries = entries.map { if (it.id == entryId) it.copy(folderId = folderId) else it })

    companion object {
        /**
         * Версии формата:
         *  1 — первая (этап 2–3);
         *  2 — добавлены отпечаток содержимого, дата съёмки и длительность видео;
         *  3 — добавлена исходная папка файла (для восстановления);
         *  4 — пометки «фальшивый тайник» и «задан фальшивый PIN»;
         *  5 — папки: их список и папка у каждой записи.
         * Старые версии читаются всегда: обновление приложения не должно ломать тайник.
         */
        private const val VERSION = 5

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
                    // версия 5 (пусто — общая сетка)
                    out.writeUTF(e.folderId ?: "")
                }
                // версия 5
                out.writeInt(index.folders.size)
                for (f in index.folders) {
                    out.writeUTF(f.id)
                    out.writeUTF(f.name)
                    out.writeLong(f.createdAt)
                }
            }
            return bos.toByteArray()
        }

        /** Восстанавливает оглавление из байтов (после расшифровки). Понимает версии 1–5. */
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
                    // Звук, добавленный до версии 0.11.0, был «прочим файлом» — теперь он слушается.
                    val kind = EntryKind.fromId(input.readInt()).let {
                        if (it == EntryKind.OTHER && EntryKind.of(mime, name) == EntryKind.AUDIO) EntryKind.AUDIO else it
                    }
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
                    val folderId = if (version >= 5) input.readUTF().ifEmpty { null } else null
                    list.add(
                        VaultEntry(
                            id, name, mime, kind, size, addedAt, blobId, thumbId,
                            sha256, takenAt, durationMs, origFolder, folderId,
                        )
                    )
                }
                val folders = ArrayList<VaultFolder>()
                if (version >= 5) {
                    repeat(input.readInt()) {
                        folders.add(VaultFolder(input.readUTF(), input.readUTF(), input.readLong()))
                    }
                }
                // Запись из несуществующей папки показываем в общей сетке, а не теряем.
                val folderIds = folders.mapTo(HashSet()) { it.id }
                return VaultIndex(
                    entries = list.map { if (it.folderId != null && it.folderId !in folderIds) it.copy(folderId = null) else it },
                    isDecoy = flags and FLAG_DECOY != 0,
                    hasDecoy = flags and FLAG_HAS_DECOY != 0,
                    folders = folders,
                )
            }
        }
    }
}
