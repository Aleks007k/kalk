package com.pocketcalc.calculator.vault

import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Архив ZIP с файлами тайника — уже расшифрованными, чтобы их можно было
 * перенести куда угодно. Внутри архива файлы НЕ зашифрованы.
 *
 * Каждый файл расшифровывается на лету прямо в архив и проверяется: размер
 * и отпечаток SHA-256 должны совпасть с записанными при добавлении. Если не
 * совпали — исключение, архив недописан (вызывающий его удаляет).
 */
object ZipExport {

    /**
     * Пишет архив в [out] (не закрывает его). Файлы из папок тайника кладутся
     * в одноимённые папки архива; одинаковые имена получают « (2)», « (3)».
     * Фото, видео и звук уже сжаты — их кладём без сжатия (быстрее), заметки
     * и прочие файлы сжимаем. [onProgress] вызывается после каждого файла.
     */
    fun write(
        repository: VaultRepository,
        session: VaultSession,
        entries: List<VaultEntry>,
        folders: List<VaultFolder>,
        out: OutputStream,
        onProgress: (done: Int) -> Unit = {},
    ) {
        val folderDirs = folders.associate { it.id to safeName(it.name).ifEmpty { "folder" } + "/" }
        val used = HashSet<String>()
        val zip = ZipOutputStream(out)
        for ((i, entry) in entries.withIndex()) {
            val dir = entry.folderId?.let { folderDirs[it] } ?: ""
            val path = uniquePath(dir, safeName(entry.name).ifEmpty { "file" }, used)
            zip.setLevel(if (alreadyCompressed(entry)) Deflater.NO_COMPRESSION else Deflater.DEFAULT_COMPRESSION)
            val zipEntry = ZipEntry(path)
            zipEntry.time = if (entry.takenAt > 0) entry.takenAt else entry.addedAt
            zip.putNextEntry(zipEntry)
            repository.exportContent(session, entry, zip)
            zip.closeEntry()
            onProgress(i + 1)
        }
        zip.finish()
        zip.flush()
    }

    private fun alreadyCompressed(entry: VaultEntry): Boolean =
        entry.kind == EntryKind.PHOTO || entry.kind == EntryKind.VIDEO || entry.kind == EntryKind.AUDIO

    /** Имя без «/», «\» и служебных символов, чтобы не вышло лишних папок. */
    internal fun safeName(name: String): String {
        val cleaned = name.map { c -> if (c == '/' || c == '\\' || c.code < 0x20) '_' else c }.joinToString("").trim()
        return if (cleaned == "." || cleaned == "..") "_" else cleaned
    }

    /** «IMG.jpg», если такое имя ещё не занято, иначе «IMG (2).jpg» и т.д. */
    internal fun uniquePath(dir: String, name: String, used: MutableSet<String>): String {
        val dot = name.lastIndexOf('.')
        val (base, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var candidate = dir + name
        var n = 2
        while (!used.add(candidate.lowercase())) {
            candidate = "$dir$base ($n)$ext"
            n++
        }
        return candidate
    }
}
