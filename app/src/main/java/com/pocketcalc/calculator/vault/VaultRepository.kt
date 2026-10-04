package com.pocketcalc.calculator.vault

import com.pocketcalc.calculator.crypto.KeyDerivation
import com.pocketcalc.calculator.crypto.KeystoreGate
import com.pocketcalc.calculator.crypto.SoftwareKeystoreGate
import com.pocketcalc.calculator.crypto.VaultKeyManager
import java.io.ByteArrayInputStream
import java.io.File
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.SeekableByteChannel
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture

/** Размер куска при выгрузке файла из тайника. */
private const val EXPORT_BUFFER = 64 * 1024

/** Ячейка, в которой лежит тайник. Какая из них настоящая — нигде не записано. */
enum class SlotPos { A, B }

/** Что известно о добавляемом файле. */
data class ImportMeta(
    val name: String,
    val mime: String,
    /** Ожидаемый размер, если известен. Если прочитано иначе — добавление отменяется. */
    val size: Long? = null,
    /** Когда снято (мс с 1970), 0 — неизвестно. */
    val takenAt: Long = 0,
    /** Длительность видео в мс, 0 — не видео или неизвестно. */
    val durationMs: Long = 0,
    /** Папка, где лежал оригинал (например, "DCIM/Camera/"), пусто — неизвестно. */
    val origFolder: String = "",
)

/** Результат добавления файла. */
sealed interface ImportResult {
    /** Файл зашифрован, проверен и записан в оглавление. */
    data class Added(val entry: VaultEntry) : ImportResult

    /** Такой файл (то же содержимое) уже есть в тайнике — второй раз не добавлен. */
    data class AlreadyThere(val entry: VaultEntry) : ImportResult
}

/**
 * Открытый тайник. Держит мастер-ключ в памяти, пока тайник открыт.
 * [close] затирает ключ нулями — после этого тайник снова заперт.
 *
 * Тайник могут закрыть в любой момент (пользователь вышел из приложения),
 * в том числе посреди фоновой операции. Поэтому долгие операции берут
 * [copyKey] — копию, которую закрытие не испортит, — и сами затирают её.
 */
class VaultSession internal constructor(val slot: SlotPos, masterKey: ByteArray) {
    private var key: ByteArray? = masterKey

    val isOpen: Boolean
        @Synchronized get() = key != null

    /** Мастер-ключ открытого тайника — только для коротких операций. Не изменяйте. */
    val masterKey: ByteArray
        @Synchronized get() = key ?: throw IllegalStateException("тайник закрыт")

    /** Копия мастер-ключа для долгой операции. Вызывающий обязан затереть её. */
    @Synchronized
    fun copyKey(): ByteArray = (key ?: throw IllegalStateException("тайник закрыт")).copyOf()

    @Synchronized
    fun close() {
        key?.fill(0)
        key = null
    }
}

/**
 * Тайники на диске: два слота (A и B) и общее хранилище файлов.
 *
 * При первой настройке настоящий тайник случайно попадает в слот A или B,
 * а во второй слот кладётся «наполнитель», неотличимый от настоящего тайника
 * (со своим пустым оглавлением). Поэтому ни по положению, ни по числу файлов
 * нельзя понять, какой слот настоящий и задан ли фальшивый PIN.
 *
 * Чистая логика без Android: покрыта JVM-тестами. Ворота защищённого чипа
 * ([KeystoreGate]) передаются снаружи.
 */
class VaultRepository(
    private val dir: File,
    private val pinParams: KeyDerivation.Params = KeyDerivation.PIN_DEFAULT,
    private val recoveryParams: KeyDerivation.Params = KeyDerivation.RECOVERY_DEFAULT,
    private val rng: SecureRandom = SecureRandom(),
) {

    enum class State { NEEDS_SETUP, READY }

    init {
        dir.mkdirs()
    }

    val storage = VaultStorage(dir)
    private val tmpDir = File(dir, "tmp")

    private fun slotFile(pos: SlotPos) = File(dir, "slot_${pos.name}.bin")

    /**
     * Нужна ли первичная настройка.
     *
     * Если найден только один слот — значит, настройку прервали на середине.
     * Если при этом нет ни одного файла, половинчатый слот удаляется и настройка
     * начинается заново. Если же файлы есть — ничего не трогаем.
     */
    fun state(): State {
        val a = slotFile(SlotPos.A).exists()
        val b = slotFile(SlotPos.B).exists()
        return when {
            a && b -> State.READY
            !a && !b -> State.NEEDS_SETUP
            storage.hasBlobs() -> State.READY
            else -> {
                slotFile(SlotPos.A).delete()
                slotFile(SlotPos.B).delete()
                State.NEEDS_SETUP
            }
        }
    }

    /**
     * Первичная настройка: создаёт настоящий тайник под [pin] и [recoveryCode]
     * и «наполнитель» во втором слоте. Возвращает открытый настоящий тайник.
     */
    fun setup(pin: CharArray, recoveryCode: CharArray, gate: KeystoreGate): VaultSession {
        check(state() == State.NEEDS_SETUP) { "тайник уже настроен" }
        val realPos = if (rng.nextBoolean()) SlotPos.A else SlotPos.B

        // Настоящий слот и наполнитель создаются параллельно: это самая долгая часть.
        val filler = CompletableFuture.supplyAsync { createFiller() }
        val (realSlot, realMaster) = VaultKeyManager.createSlot(pin, recoveryCode, gate, pinParams, recoveryParams)
        val (fillerSlot, fillerMaster) = filler.join()

        try {
            storage.saveIndex(realMaster, VaultIndex.empty())
            storage.saveIndex(fillerMaster, VaultIndex.empty())
            writeSlot(other(realPos), fillerSlot)
            writeSlot(realPos, realSlot)
        } finally {
            fillerMaster.fill(0)
        }
        return VaultSession(realPos, realMaster)
    }

    /** Открывает тайник по PIN. Проверяет оба слота всегда. null — PIN не подошёл. */
    fun unlockWithPin(pin: CharArray, gate: KeystoreGate): VaultSession? =
        pick(forBothSlots { slot -> VaultKeyManager.openWithPin(slot, pin, gate) })

    /** Открывает тайник по коду восстановления. Проверяет оба слота всегда. */
    fun unlockWithRecovery(code: CharArray): VaultSession? =
        pick(forBothSlots { slot -> VaultKeyManager.openWithRecovery(slot, code) })

    /** Меняет PIN открытого тайника. Мастер-ключ и код восстановления не меняются. */
    fun changePin(session: VaultSession, newPin: CharArray, gate: KeystoreGate) {
        // Копия ключа: если тайник закроют посреди операции, копия останется целой.
        val master = session.copyKey()
        try {
            val slot = readSlot(session.slot) ?: error("слот не найден")
            val updated = VaultKeyManager.changePin(slot, master, newPin, gate, pinParams)
            writeSlot(session.slot, updated)
        } finally {
            master.fill(0)
        }
    }

    /** Удаляет мусор от прерванных операций. Вызывать при запуске приложения. */
    fun cleanupTmp() = storage.cleanupTmp()

    // --- Файлы в тайнике --------------------------------------------------

    /** Все изменения оглавления идут по одному: «прочитать → изменить → сохранить». */
    private val indexLock = Any()

    /** Оглавление открытого тайника. null — оглавление повреждено. */
    fun loadIndex(session: VaultSession): VaultIndex? = withKeyCopy(session) { key ->
        storage.loadIndex(key)
    }

    /**
     * Добавляет файл в тайник.
     *
     * Файл шифруется и сразу расшифровывается для проверки (размер и отпечаток
     * SHA-256 должны совпасть с прочитанным). Если в тайнике уже есть файл
     * с таким же содержимым, второй раз он не добавляется.
     * [thumbnailJpeg] — миниатюра для сетки (шифруется отдельным файлом).
     */
    fun importFile(
        session: VaultSession,
        input: InputStream,
        meta: ImportMeta,
        thumbnailJpeg: ByteArray?,
        /** false — не искать такой же файл в тайнике (новая заметка — всегда новая запись). */
        dedupe: Boolean = true,
    ): ImportResult = withKeyCopy(session) { key ->
        val stored = storage.storeBlob(key, input, meta.size)
        var thumbId: String? = null
        try {
            synchronized(indexLock) {
                val index = storage.loadIndex(key) ?: error("оглавление повреждено")
                val existing = if (dedupe) index.findBySha256(stored.sha256) else null
                if (existing != null) {
                    storage.deleteBlob(stored.id)
                    return@withKeyCopy ImportResult.AlreadyThere(existing)
                }
                thumbId = thumbnailJpeg?.let { storage.addBlob(key, ByteArrayInputStream(it)) }
                val entry = VaultEntry(
                    id = AtomicFiles.randomHex(16),
                    name = meta.name,
                    mime = meta.mime,
                    kind = EntryKind.fromMime(meta.mime),
                    size = stored.size,
                    addedAt = System.currentTimeMillis(),
                    blobId = stored.id,
                    thumbId = thumbId,
                    sha256 = stored.sha256,
                    takenAt = meta.takenAt,
                    durationMs = meta.durationMs,
                    origFolder = meta.origFolder,
                )
                storage.saveIndex(key, index.add(entry))
                ImportResult.Added(entry)
            }
        } catch (e: Exception) {
            // Запись в оглавление не удалась — убираем уже зашифрованные файлы.
            storage.deleteBlob(stored.id)
            thumbId?.let { storage.deleteBlob(it) }
            throw e
        }
    }

    /**
     * Заменяет содержимое файла (например, после правки заметки). Новое
     * содержимое шифруется и проверяется так же, как при добавлении; запись
     * в оглавлении сохраняет id, имя и даты. Старый зашифрованный файл
     * удаляется только после того, как оглавление сохранено.
     */
    fun replaceContent(
        session: VaultSession,
        entryId: String,
        input: InputStream,
        expectedSize: Long? = null,
    ): VaultEntry = withKeyCopy(session) { key ->
        val stored = storage.storeBlob(key, input, expectedSize)
        val (updated, oldBlobId) = try {
            synchronized(indexLock) {
                val index = storage.loadIndex(key) ?: error("оглавление повреждено")
                val old = index.find(entryId) ?: error("файл не найден в тайнике")
                val updated = old.copy(size = stored.size, blobId = stored.id, sha256 = stored.sha256)
                storage.saveIndex(key, index.replace(updated))
                updated to old.blobId
            }
        } catch (e: Exception) {
            // Оглавление не изменилось — новый зашифрованный файл не нужен.
            storage.deleteBlob(stored.id)
            throw e
        }
        // Сбой здесь оставит лишь бесполезный зашифрованный «мусор».
        storage.deleteBlob(oldBlobId)
        updated
    }

    /** Миниатюра записи (JPEG) или null, если её нет. */
    fun readThumbnail(session: VaultSession, entry: VaultEntry): ByteArray? {
        val thumbId = entry.thumbId ?: return null
        return withKeyCopy(session) { key ->
            try {
                storage.openBlob(key, thumbId).use { it.readBytes() }
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Поток с расшифрованным содержимым файла (для просмотра). Расшифровка идёт
     * по мере чтения, на диск ничего не пишется. Закрывает поток вызывающий.
     */
    fun openContent(session: VaultSession, entry: VaultEntry): InputStream =
        withKeyCopy(session) { key -> storage.openBlob(key, entry.blobId) }

    /**
     * Расшифровка с произвольным доступом (видео с перемоткой): на диск
     * ничего не пишется. Закрывает канал вызывающий.
     */
    fun openSeekable(session: VaultSession, entry: VaultEntry): SeekableByteChannel =
        withKeyCopy(session) { key -> storage.openBlobSeekable(key, entry.blobId) }

    /**
     * Весь файл целиком в память (для показа фото). Файлы больше [maxBytes]
     * не читаются — их показ занял бы слишком много памяти.
     */
    fun readContent(session: VaultSession, entry: VaultEntry, maxBytes: Long): ByteArray {
        require(entry.size in 0..maxBytes) { "файл слишком большой для показа: ${entry.size} байт" }
        val bytes = ByteArray(entry.size.toInt())
        openContent(session, entry).use { input ->
            var off = 0
            while (off < bytes.size) {
                val n = input.read(bytes, off, bytes.size - off)
                if (n < 0) throw EOFException("файл короче, чем записано в оглавлении")
                off += n
            }
            check(input.read() < 0) { "файл длиннее, чем записано в оглавлении" }
        }
        return bytes
    }

    /**
     * Расшифровывает файл в [out] (например, в новый файл в Галерее) и проверяет
     * его: размер и отпечаток SHA-256 должны совпасть с записанными при
     * добавлении. Возвращает отпечаток записанного, чтобы вызывающий сверил с
     * ним то, что легло на диск. При несовпадении бросает исключение — тогда
     * файл из тайника удалять нельзя.
     */
    fun exportContent(session: VaultSession, entry: VaultEntry, out: OutputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        openContent(session, entry).use { input ->
            val buf = ByteArray(EXPORT_BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                out.write(buf, 0, n)
                total += n
            }
        }
        out.flush()
        val sha = Sha256.hex(digest.digest())
        check(total == entry.size) { "проверка не прошла: $total байт вместо ${entry.size}" }
        check(entry.sha256.isEmpty() || sha == entry.sha256) { "проверка не прошла: отпечаток не совпал" }
        return sha
    }

    /**
     * Удаляет файл из тайника. Сначала запись исчезает из оглавления, потом
     * удаляются зашифрованные файлы: при сбое между шагами останется лишь
     * бесполезный зашифрованный «мусор», но не битая запись.
     */
    fun deleteEntry(session: VaultSession, entryId: String): Boolean = withKeyCopy(session) { key ->
        synchronized(indexLock) {
            val index = storage.loadIndex(key) ?: return@withKeyCopy false
            val entry = index.find(entryId) ?: return@withKeyCopy false
            storage.saveIndex(key, index.remove(entryId))
            storage.deleteBlob(entry.blobId)
            entry.thumbId?.let { storage.deleteBlob(it) }
            true
        }
    }

    /**
     * Выполняет [block] с копией мастер-ключа и затирает копию после.
     * Если тайник закроют посреди операции, копия останется целой.
     */
    private inline fun <T> withKeyCopy(session: VaultSession, block: (ByteArray) -> T): T {
        val key = session.copyKey()
        try {
            return block(key)
        } finally {
            key.fill(0)
        }
    }

    // --- Внутреннее -------------------------------------------------------

    private fun createFiller(): Pair<VaultKeyManager.Slot, ByteArray> {
        val pin = CharArray(SecretInput.PIN_MAX) { '0' + rng.nextInt(10) }
        val code = CharArray(SecretInput.RECOVERY_LENGTH) { '0' + rng.nextInt(10) }
        val gate = SoftwareKeystoreGate(ByteArray(32).also { rng.nextBytes(it) })
        return try {
            VaultKeyManager.createSlot(pin, code, gate, pinParams, recoveryParams)
        } finally {
            pin.fill('0')
            code.fill('0')
        }
    }

    /**
     * Выполняет [op] для обоих слотов параллельно и дожидается обоих результатов.
     * Время ответа не зависит от того, какой слот подошёл (и подошёл ли вообще).
     */
    private fun forBothSlots(op: (VaultKeyManager.Slot) -> ByteArray?): Pair<ByteArray?, ByteArray?> {
        val slotA = readSlot(SlotPos.A)
        val slotB = readSlot(SlotPos.B)
        val futureB = CompletableFuture.supplyAsync { slotB?.let { safely { op(it) } } }
        val a = slotA?.let { safely { op(it) } }
        val b = futureB.join()
        return a to b
    }

    private fun pick(results: Pair<ByteArray?, ByteArray?>): VaultSession? {
        val (a, b) = results
        return when {
            a != null -> {
                b?.fill(0)
                VaultSession(SlotPos.A, a)
            }
            b != null -> VaultSession(SlotPos.B, b)
            else -> null
        }
    }

    private inline fun safely(block: () -> ByteArray?): ByteArray? =
        try {
            block()
        } catch (e: Exception) {
            null
        }

    private fun readSlot(pos: SlotPos): VaultKeyManager.Slot? {
        val f = slotFile(pos)
        return if (f.exists()) VaultKeyManager.Slot(f.readBytes()) else null
    }

    private fun writeSlot(pos: SlotPos, slot: VaultKeyManager.Slot) {
        AtomicFiles.writeBytes(slotFile(pos), tmpDir, slot.bytes)
    }

    private fun other(pos: SlotPos) = if (pos == SlotPos.A) SlotPos.B else SlotPos.A
}
