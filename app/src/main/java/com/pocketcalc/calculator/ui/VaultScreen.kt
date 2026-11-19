package com.pocketcalc.calculator.ui

import android.app.Activity
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.LruCache
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketcalc.calculator.CalcApp
import com.pocketcalc.calculator.R
import com.pocketcalc.calculator.media.ImportProblem
import com.pocketcalc.calculator.media.ImportSummary
import com.pocketcalc.calculator.media.Importer
import com.pocketcalc.calculator.media.MediaGallery
import com.pocketcalc.calculator.media.MediaItem
import com.pocketcalc.calculator.media.Restorer
import com.pocketcalc.calculator.ui.theme.CalcPalette
import com.pocketcalc.calculator.ui.theme.calcPalette
import com.pocketcalc.calculator.vault.EntryKind
import com.pocketcalc.calculator.vault.ImportMeta
import com.pocketcalc.calculator.vault.ImportResult
import com.pocketcalc.calculator.vault.TextCodec
import com.pocketcalc.calculator.vault.VaultEntry
import com.pocketcalc.calculator.vault.VaultFolder
import com.pocketcalc.calculator.vault.VaultSession
import com.pocketcalc.calculator.vault.ZipExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException
import java.text.Collator
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Сколько неудавшихся файлов перечислять в итоге добавления (остальные — числом). */
private const val MAX_FAILURE_LINES = 5

/** Раздел тайника: фото и видео — сеткой, остальное (PDF, заметки, документы) — списком. */
private enum class Section { MEDIA, FILES }

private val VaultEntry.isMedia: Boolean
    get() = kind == EntryKind.PHOTO || kind == EntryKind.VIDEO

/** Заметка, открытая в редакторе. [token] отличает одно открытие от другого. */
private data class NoteEdit(val title: String, val text: String, val token: Long = System.nanoTime())

/** Что сейчас происходит на экране тайника. */
private sealed interface VaultFlow {
    data object Browse : VaultFlow
    data object Picking : VaultFlow
    data class Importing(val done: Int, val total: Int) : VaultFlow
    /** Возврат файлов в Галерею: [done] из [total] уже готово. */
    data class Restoring(val done: Int, val total: Int) : VaultFlow
    /** Сохранение выбранных файлов в архив: [done] из [total]. */
    data class Exporting(val done: Int, val total: Int) : VaultFlow
}

/**
 * Экран открытого тайника. Одинаков для настоящего и фальшивого тайника.
 * Заголовка нет — со стороны экран похож на обычную галерею.
 * «Назад» и «Закрыть» запирают тайник.
 */
@Composable
fun VaultScreen(app: CalcApp, onLock: () -> Unit) {
    SecureWindow()
    val session = AppController.session
    if (session == null) {
        LaunchedEffect(Unit) { onLock() }
        return
    }
    VaultContent(app, session, onLock)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultContent(app: CalcApp, session: VaultSession, onLock: () -> Unit) {
    val palette = calcPalette(isSystemInDarkTheme())
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    var flow by remember { mutableStateOf<VaultFlow>(VaultFlow.Browse) }
    var entries by remember { mutableStateOf<List<VaultEntry>?>(null) }
    var damaged by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var pickerKey by remember { mutableStateOf(0) }

    var summary by remember { mutableStateOf<ImportSummary?>(null) }
    var originalsDeleted by remember { mutableStateOf<Boolean?>(null) }
    var docsDeleteFailed by remember { mutableStateOf(0) }
    var askDeleteDocs by remember { mutableStateOf<List<Uri>?>(null) }
    var showSummary by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<VaultEntry?>(null) }
    /** Открытая в редакторе заметка (null — редактор закрыт). */
    var editing by remember { mutableStateOf<NoteEdit?>(null) }
    /** Запись редактируемой заметки; null — новая, ещё ни разу не сохранённая. */
    var editingNoteId by remember { mutableStateOf<String?>(null) }
    /** Открытый на весь экран файл (null — показана сетка). */
    var viewingId by remember { mutableStateOf<String?>(null) }
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    var visibleAtOpen by remember { mutableStateOf(IntRange.EMPTY) }
    var section by remember { mutableStateOf(Section.MEDIA) }
    var toDelete by remember { mutableStateOf<VaultEntry?>(null) }
    var noPermission by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    /** Папки раздела «Фото и видео», по алфавиту. */
    var folders by remember { mutableStateOf<List<VaultFolder>>(emptyList()) }
    /** Открытая папка (null — общая сетка). */
    var openFolderId by remember { mutableStateOf<String?>(null) }
    var folderActionsFor by remember { mutableStateOf<VaultFolder?>(null) }
    var renamingFolder by remember { mutableStateOf<VaultFolder?>(null) }
    var deletingFolder by remember { mutableStateOf<VaultFolder?>(null) }
    var restoringFolder by remember { mutableStateOf<VaultFolder?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    /** Файл, который переедет в папку, создаваемую из окна «Переместить». */
    var moveAfterCreate by remember { mutableStateOf<List<VaultEntry>?>(null) }
    /** Файлы, для которых открыто окно «Переместить в…» (один или выбранные). */
    var moveTargets by remember { mutableStateOf<List<VaultEntry>?>(null) }
    /** Отмеченные файлы; пусто — обычный режим, иначе — режим выделения. */
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var askBulkDelete by remember { mutableStateOf(false) }
    var askBulkRestore by remember { mutableStateOf(false) }
    var askArchive by remember { mutableStateOf(false) }
    /** Файлы, которые ждут, пока пользователь выберет, куда сохранить архив. */
    var pendingArchive by remember { mutableStateOf<List<VaultEntry>?>(null) }
    /** Новая папка, к которой надо прокрутить сетку, когда она появится. */
    var scrollToFolderId by remember { mutableStateOf<String?>(null) }
    // У каждой открытой папки своя прокрутка, у общей сетки — своя.
    val folderGridState = remember(openFolderId) { LazyGridState() }

    var columns by remember { mutableIntStateOf(UiPrefs.gridColumns(context)) }
    fun changeColumns(step: Int) {
        val next = (columns + step).coerceIn(UiPrefs.MIN_COLUMNS, UiPrefs.MAX_COLUMNS)
        if (next != columns) {
            columns = next
            UiPrefs.setGridColumns(context, next)
        }
    }

    val thumbCache = remember { thumbnailCache(48 * 1024 * 1024) }

    // Фото и видео — отдельно от документов и заметок.
    val mediaEntries = remember(entries) { entries?.filter { it.isMedia } }
    val fileEntries = remember(entries) { entries?.filterNot { it.isMedia } }
    // В сетке — фото и видео открытой папки или, если папка не открыта, те, что вне папок.
    val openFolder = folders.firstOrNull { it.id == openFolderId }
    val gridEntries = remember(mediaEntries, openFolder?.id) {
        mediaEntries?.filter { it.folderId == openFolder?.id }
    }
    val folderCounts = remember(mediaEntries) {
        mediaEntries.orEmpty().groupingBy { it.folderId }.eachCount()
    }
    val sectionEntries = if (section == Section.MEDIA) gridEntries else fileEntries
    val activeGridState = if (openFolder != null) folderGridState else gridState

    val selecting = selectedIds.isNotEmpty()
    val selectedEntries = remember(sectionEntries, selectedIds) {
        sectionEntries.orEmpty().filter { it.id in selectedIds }
    }
    fun toggleSelected(entry: VaultEntry) {
        selectedIds = if (entry.id in selectedIds) selectedIds - entry.id else selectedIds + entry.id
    }
    // Другой раздел или папка — выделение снимается.
    LaunchedEffect(section, openFolderId) { selectedIds = emptySet() }
    // Файлы удалили или вернули — убираем их из выделения.
    LaunchedEffect(entries) {
        val present = entries.orEmpty().mapTo(HashSet()) { it.id }
        if (!present.containsAll(selectedIds)) selectedIds = selectedIds.intersect(present)
    }

    fun openViewer(entry: VaultEntry) {
        val visible = if (section == Section.MEDIA) {
            activeGridState.layoutInfo.visibleItemsInfo.map { it.index }
        } else {
            listState.layoutInfo.visibleItemsInfo.map { it.index }
        }
        visibleAtOpen = if (visible.isEmpty()) IntRange.EMPTY else visible.first()..visible.last()
        viewingId = entry.id
    }

    /** Закрыть просмотр; если листали далеко — прокрутить раздел к последнему файлу. */
    fun closeViewer(lastEntryId: String?) {
        viewingId = null
        val index = sectionEntries?.indexOfFirst { it.id == lastEntryId } ?: -1
        if (index >= 0 && index !in visibleAtOpen) {
            scope.launch {
                if (section == Section.MEDIA) activeGridState.scrollToItem(index) else listState.scrollToItem(index)
            }
        }
    }

    BackHandler {
        when (flow) {
            is VaultFlow.Picking -> flow = VaultFlow.Browse
            // во время шифрования или возврата «Назад» не прерывает работу
            is VaultFlow.Importing, is VaultFlow.Restoring, is VaultFlow.Exporting -> Unit
            // из «Файлов» или из папки — сначала к фото, потом уже закрыть тайник
            is VaultFlow.Browse -> when {
                // сначала снимаем выделение
                selectedIds.isNotEmpty() -> selectedIds = emptySet()
                section == Section.FILES -> section = Section.MEDIA
                openFolderId != null -> openFolderId = null
                else -> onLock()
            }
        }
    }

    LaunchedEffect(reload) {
        val index = withContext(Dispatchers.IO) {
            try {
                app.repository.loadIndex(session)
            } catch (e: Exception) {
                null
            }
        }
        damaged = index == null && session.isOpen
        entries = index?.entries?.sortedByDescending { if (it.takenAt > 0) it.takenAt else it.addedAt }
        folders = index?.folders?.sortedWith(compareBy<VaultFolder, String>(Collator.getInstance()) { it.name }).orEmpty()
    }

    LaunchedEffect(folders) {
        // Открытой папки больше нет — возвращаемся в общую сетку.
        if (openFolderId != null && folders.none { it.id == openFolderId }) openFolderId = null
        // Новая папка — внизу, под фото: прокручиваем к ней.
        val target = scrollToFolderId
        val i = folders.indexOfFirst { it.id == target }
        if (target != null && i >= 0 && openFolderId == null) {
            scrollToFolderId = null
            // фото и видео, затем подпись «Папки», затем сами папки
            gridState.scrollToItem((gridEntries?.size ?: 0) + 1 + i)
        }
    }

    // --- Удаление оригиналов фото/видео (системное окно подтверждения) ---
    val deleteMediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        AppController.endExternalActivity()
        originalsDeleted = result.resultCode == Activity.RESULT_OK
        showSummary = true
    }

    fun finishMediaImport(s: ImportSummary) {
        summary = s
        if (s.safeToDelete.isEmpty()) {
            originalsDeleted = null
            showSummary = true
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val request = MediaStore.createDeleteRequest(context.contentResolver, s.safeToDelete)
                AppController.beginExternalActivity()
                deleteMediaLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
            } catch (e: Exception) {
                AppController.endExternalActivity()
                originalsDeleted = false
                showSummary = true
            }
        } else {
            // Android 10 и старше: пробуем удалить напрямую.
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    s.safeToDelete.all { uri ->
                        try {
                            context.contentResolver.delete(uri, null, null) > 0
                        } catch (e: Exception) {
                            false
                        }
                    }
                }
                originalsDeleted = ok
                showSummary = true
            }
        }
    }

    fun importMedia(items: List<MediaItem>) {
        // Пока выбирали фото, папка оставалась открытой: добавляем прямо в неё.
        val target = openFolderId
        flow = VaultFlow.Importing(0, items.size)
        view.keepScreenOn = true
        scope.launch {
            val s = try {
                Importer.importMedia(context, app.repository, session, items, folderId = target) { done, total ->
                    flow = VaultFlow.Importing(done, total)
                }
            } finally {
                view.keepScreenOn = false
                flow = VaultFlow.Browse
                reload++
            }
            finishMediaImport(s)
        }
    }

    fun importDocs(uris: List<Uri>) {
        flow = VaultFlow.Importing(0, uris.size)
        view.keepScreenOn = true
        scope.launch {
            val s = try {
                Importer.importDocuments(context, app.repository, session, uris) { done, total ->
                    flow = VaultFlow.Importing(done, total)
                }
            } finally {
                view.keepScreenOn = false
                flow = VaultFlow.Browse
                reload++
            }
            summary = s
            if (s.safeToDelete.isNotEmpty()) {
                askDeleteDocs = s.safeToDelete
            } else {
                originalsDeleted = null
                showSummary = true
            }
        }
    }

    // --- Заметки ---
    val defaultNoteName = stringResource(
        R.string.note_default_name,
        SimpleDateFormat("dd.MM.yyyy HH-mm", Locale.getDefault()).format(Date()),
    )

    fun openNewNote() {
        editingNoteId = null
        editing = NoteEdit(title = defaultNoteName, text = "")
    }

    /**
     * Шифрует и записывает текст заметки. Новая заметка появляется в тайнике
     * при первом сохранении (пустая — не создаётся), дальше её содержимое
     * заменяется. Возвращает false, если записать не удалось.
     */
    suspend fun saveNote(text: String): Boolean {
        val id = editingNoteId
        return try {
            val savedId = withContext(Dispatchers.IO) {
                val bytes = TextCodec.encode(text)
                if (id == null) {
                    if (text.isBlank()) return@withContext null
                    val name = (TextCodec.titleOf(text) ?: defaultNoteName) + ".txt"
                    val meta = ImportMeta(name = name, mime = "text/plain", size = bytes.size.toLong())
                    val result = app.repository.importFile(
                        session, ByteArrayInputStream(bytes), meta, thumbnailJpeg = null, dedupe = false,
                    )
                    (result as ImportResult.Added).entry.id
                } else {
                    app.repository.replaceContent(session, id, ByteArrayInputStream(bytes), bytes.size.toLong()).id
                }
            }
            if (savedId != null) {
                editingNoteId = savedId
                reload++
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    // --- Возврат файла из тайника в память телефона ---
    fun restore(entry: VaultEntry) {
        flow = VaultFlow.Restoring(0, 1)
        view.keepScreenOn = true
        scope.launch {
            val message = try {
                val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Restorer.restore(context, app.repository, session, entry)
                } else {
                    throw UnsupportedOperationException("нужен Android 10 или новее")
                }
                if (result.removedFromVault) {
                    context.getString(R.string.restore_done, result.folder)
                } else {
                    context.getString(R.string.restore_done_copy_kept, result.folder)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                context.getString(R.string.restore_failed)
            } finally {
                view.keepScreenOn = false
                flow = VaultFlow.Browse
                thumbCache.remove(entry.id)
                reload++
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    // --- Папки ---
    val msgFailed = stringResource(R.string.action_failed)

    fun moveEntries(files: List<VaultEntry>, target: VaultFolder?) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    app.repository.moveEntries(session, files.map { it.id }, target?.id) > 0
                } catch (e: Exception) {
                    false
                }
            }
            if (ok) selectedIds = emptySet()
            val where = target?.name ?: context.getString(R.string.section_media)
            Toast.makeText(
                context,
                if (ok) context.getString(R.string.moved_to, where) else msgFailed,
                Toast.LENGTH_SHORT,
            ).show()
            reload++
        }
    }

    /** Новая папка; [thenMove] — файлы, которые сразу переезжают в неё. */
    fun createFolder(name: String, thenMove: List<VaultEntry>?) {
        scope.launch {
            val folder = withContext(Dispatchers.IO) {
                try {
                    app.repository.createFolder(session, name)
                } catch (e: Exception) {
                    null
                }
            }
            when {
                folder == null -> Toast.makeText(context, msgFailed, Toast.LENGTH_SHORT).show()
                thenMove != null -> moveEntries(thenMove, folder)
                else -> {
                    if (openFolderId == null) scrollToFolderId = folder.id
                    reload++
                }
            }
        }
    }

    fun renameFolder(folder: VaultFolder, name: String) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    app.repository.renameFolder(session, folder.id, name)
                } catch (e: Exception) {
                    false
                }
            }
            if (!ok) Toast.makeText(context, msgFailed, Toast.LENGTH_SHORT).show()
            reload++
        }
    }

    /** Удаляет папку; файлы из неё возвращаются в общую сетку. */
    fun deleteFolder(folder: VaultFolder) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    app.repository.deleteFolder(session, folder.id)
                } catch (e: Exception) {
                    false
                }
            }
            if (!ok) Toast.makeText(context, msgFailed, Toast.LENGTH_SHORT).show()
            reload++
        }
    }

    /**
     * Возвращает файлы в Галерею — каждый туда, откуда он был добавлен. Если
     * задана [folder] и она опустела, папка удаляется. Файл, который вернуть
     * не удалось, остаётся в тайнике.
     */
    fun restoreMany(files: List<VaultEntry>, folder: VaultFolder?) {
        flow = VaultFlow.Restoring(0, files.size)
        view.keepScreenOn = true
        scope.launch {
            var restored = 0
            var removed = 0
            try {
                for ((i, entry) in files.withIndex()) {
                    if (!session.isOpen) break
                    flow = VaultFlow.Restoring(i, files.size)
                    try {
                        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            Restorer.restore(context, app.repository, session, entry)
                        } else {
                            throw UnsupportedOperationException("нужен Android 10 или новее")
                        }
                        restored++
                        if (result.removedFromVault) removed++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // файл остаётся в папке
                    }
                    thumbCache.remove(entry.id)
                }
                if (folder != null && removed == files.size) {
                    withContext(Dispatchers.IO) {
                        try {
                            app.repository.deleteFolder(session, folder.id)
                        } catch (e: Exception) {
                            false
                        }
                    }
                }
            } finally {
                view.keepScreenOn = false
                flow = VaultFlow.Browse
                reload++
            }
            val message = when {
                folder != null && removed == files.size -> context.getString(R.string.folder_restored, restored)
                folder != null -> context.getString(R.string.folder_restored_partly, restored, files.size)
                removed == files.size -> context.getString(R.string.bulk_restored, restored)
                else -> context.getString(R.string.bulk_restored_partly, restored, files.size)
            }
            selectedIds = emptySet()
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    fun restoreFolder(folder: VaultFolder) =
        restoreMany(entries.orEmpty().filter { it.folderId == folder.id }, folder)

    /** Удаляет выбранные файлы из тайника навсегда. */
    fun deleteMany(files: List<VaultEntry>) {
        scope.launch {
            val deleted = withContext(Dispatchers.IO) {
                try {
                    app.repository.deleteEntries(session, files.map { it.id })
                } catch (e: Exception) {
                    -1
                }
            }
            files.forEach { thumbCache.remove(it.id) }
            selectedIds = emptySet()
            reload++
            Toast.makeText(
                context,
                if (deleted >= 0) context.getString(R.string.bulk_deleted, deleted) else msgFailed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /**
     * Сохраняет [files] одним ZIP-архивом в [uri] (место выбрал пользователь).
     * Внутри архива файлы расшифрованы. При сбое недописанный архив удаляется.
     */
    fun exportArchive(uri: Uri, files: List<VaultEntry>) {
        val folderList = folders
        flow = VaultFlow.Exporting(0, files.size)
        view.keepScreenOn = true
        scope.launch {
            var ok = false
            try {
                ok = withContext(Dispatchers.IO) {
                    try {
                        val out = context.contentResolver.openOutputStream(uri, "w")
                            ?: throw IOException("не удалось открыть файл для записи")
                        out.use { stream ->
                            ZipExport.write(app.repository, session, files, folderList, stream) { done ->
                                flow = VaultFlow.Exporting(done, files.size)
                            }
                        }
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Недописанный архив не оставляем.
                        try {
                            DocumentsContract.deleteDocument(context.contentResolver, uri)
                        } catch (cleanup: Exception) {
                            // не вышло — пусть удалит пользователь
                        }
                        false
                    }
                }
            } finally {
                view.keepScreenOn = false
                flow = VaultFlow.Browse
            }
            if (ok) selectedIds = emptySet()
            Toast.makeText(
                context,
                if (ok) context.getString(R.string.archive_saved, files.size) else context.getString(R.string.archive_failed),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    // --- Куда сохранить архив (системное окно) ---
    val archiveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        AppController.endExternalActivity()
        val files = pendingArchive
        pendingArchive = null
        if (uri != null && !files.isNullOrEmpty()) exportArchive(uri, files)
    }
    val archivePrefix = stringResource(R.string.archive_file_prefix)

    fun chooseArchivePlace(files: List<VaultEntry>) {
        pendingArchive = files
        val name = archivePrefix + "_" + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date()) + ".zip"
        try {
            AppController.beginExternalActivity()
            archiveLauncher.launch(name)
        } catch (e: Exception) {
            AppController.endExternalActivity()
            pendingArchive = null
        }
    }

    // --- Доступ к фото и видео ---
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        AppController.endExternalActivity()
        if (MediaGallery.hasAccess(context)) {
            pickerKey++
            flow = VaultFlow.Picking
        } else {
            noPermission = true
        }
    }

    fun requestMediaAccess() {
        try {
            AppController.beginExternalActivity()
            permissionLauncher.launch(MediaGallery.permissionsToRequest())
        } catch (e: Exception) {
            AppController.endExternalActivity()
        }
    }

    fun openMediaPicker() {
        if (MediaGallery.hasAccess(context)) {
            pickerKey++
            flow = VaultFlow.Picking
        } else {
            requestMediaAccess()
        }
    }

    // --- Документы (системное окно выбора файлов) ---
    val docsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        AppController.endExternalActivity()
        if (uris.isNotEmpty()) importDocs(uris)
    }

    fun openDocsPicker() {
        try {
            AppController.beginExternalActivity()
            docsLauncher.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            AppController.endExternalActivity()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        val viewerEntries = sectionEntries
        val openId = viewingId
        if (flow is VaultFlow.Picking) {
            key(pickerKey) {
                MediaPickerScreen(
                    columns = columns,
                    onColumnsStep = ::changeColumns,
                    onCancel = { flow = VaultFlow.Browse },
                    onConfirm = { items -> importMedia(items) },
                    onRequestMoreAccess = { requestMediaAccess() },
                )
            }
        } else if (openId != null && viewerEntries != null) {
            ViewerScreen(
                entries = viewerEntries,
                startEntryId = openId,
                repository = app.repository,
                session = session,
                thumbCache = thumbCache,
                // Пока идёт возврат файла, «Назад» не закрывает просмотр.
                canClose = flow is VaultFlow.Browse,
                docTmpDir = app.docTmpDir,
                onClose = ::closeViewer,
                onActions = { entry -> actionsFor = entry },
                onEditNote = { entry, text ->
                    editingNoteId = entry.id
                    editing = NoteEdit(title = entry.name, text = text)
                },
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val folderHere = if (section == Section.MEDIA) openFolder else null
                    if (selecting) {
                        // Режим выделения: крестик, «Выбрано: N», «Выбрать все».
                        RoundIconButton(onClick = { selectedIds = emptySet() }, size = 44.dp) {
                            CloseIcon(modifier = Modifier.size(22.dp), color = palette.displayPrimary)
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.selection_count, selectedIds.size),
                            color = palette.displayPrimary,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        val all = sectionEntries.orEmpty()
                        val allSelected = all.isNotEmpty() && selectedEntries.size == all.size
                        TextButton(onClick = {
                            selectedIds = if (allSelected) emptySet() else all.mapTo(HashSet()) { it.id }
                        }) {
                            Text(
                                text = stringResource(if (allSelected) R.string.select_none else R.string.select_all),
                                color = palette.opBg,
                                fontSize = 16.sp,
                            )
                        }
                    } else if (folderHere != null) {
                        // В папке: стрелка назад и название вместо переключателя разделов.
                        RoundIconButton(onClick = { openFolderId = null }, size = 44.dp) {
                            BackArrowIcon(modifier = Modifier.size(26.dp), color = palette.displayPrimary)
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = folderHere.name,
                            color = palette.displayPrimary,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        SectionSwitch(
                            selected = section,
                            palette = palette,
                            onSelect = { section = it },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (!selecting) {
                        Spacer(Modifier.width(4.dp))
                        RoundIconButton(onClick = { showSettings = true }, size = 44.dp) {
                            GearIcon(modifier = Modifier.size(24.dp), color = palette.displaySecondary)
                        }
                        TextButton(onClick = onLock) {
                            Text(text = stringResource(R.string.vault_lock), color = palette.opBg, fontSize = 17.sp)
                        }
                    }
                }

                if (section == Section.MEDIA) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .pinchToChangeColumns(::changeColumns),
                    ) {
                        val list = gridEntries
                        // Папки — внизу общей сетки, под всеми фото и видео.
                        val showFolders = openFolder == null && folders.isNotEmpty()
                        when {
                            damaged -> CenterNote(
                                title = stringResource(R.string.vault_damaged),
                                text = null,
                                palette = palette,
                            )
                            list == null -> CircularProgressIndicator(
                                color = palette.opBg,
                                modifier = Modifier.align(Alignment.Center),
                            )
                            list.isEmpty() && !showFolders -> if (openFolder != null) {
                                CenterNote(
                                    title = stringResource(R.string.folder_empty_title),
                                    text = stringResource(R.string.folder_empty_text),
                                    palette = palette,
                                )
                            } else {
                                CenterNote(
                                    title = stringResource(R.string.vault_empty_title),
                                    text = stringResource(R.string.vault_empty_text),
                                    palette = palette,
                                )
                            }
                            else -> LazyVerticalGrid(
                                columns = GridCells.Fixed(columns),
                                state = activeGridState,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                items(list, key = { it.id }) { entry ->
                                    VaultCell(
                                        entry = entry,
                                        palette = palette,
                                        cache = thumbCache,
                                        compact = columns >= 5,
                                        loadThumbnail = {
                                            try {
                                                app.repository.readThumbnail(session, entry)
                                            } catch (e: Exception) {
                                                null
                                            }
                                        },
                                        selected = entry.id in selectedIds,
                                        onClick = { if (selecting) toggleSelected(entry) else openViewer(entry) },
                                        onLongClick = { toggleSelected(entry) },
                                    )
                                }
                                if (showFolders) {
                                    item(key = "folders-header", span = { GridItemSpan(maxLineSpan) }) {
                                        FoldersHeader(palette)
                                    }
                                    items(
                                        folders,
                                        key = { "folder-" + it.id },
                                        span = { GridItemSpan(maxLineSpan) },
                                    ) { folder ->
                                        FolderRow(
                                            folder = folder,
                                            count = folderCounts[folder.id] ?: 0,
                                            palette = palette,
                                            // Пока выбираются файлы, папки не открываются.
                                            onClick = { if (!selecting) openFolderId = folder.id },
                                            onLongClick = { if (!selecting) folderActionsFor = folder },
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        val list = fileEntries
                        when {
                            damaged -> CenterNote(
                                title = stringResource(R.string.vault_damaged),
                                text = null,
                                palette = palette,
                            )
                            list == null -> CircularProgressIndicator(
                                color = palette.opBg,
                                modifier = Modifier.align(Alignment.Center),
                            )
                            list.isEmpty() -> CenterNote(
                                title = stringResource(R.string.files_empty_title),
                                text = stringResource(R.string.files_empty_text),
                                palette = palette,
                            )
                            else -> LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(vertical = 4.dp),
                            ) {
                                items(list.size, key = { list[it].id }) { i ->
                                    val entry = list[i]
                                    FileRow(
                                        entry = entry,
                                        palette = palette,
                                        cache = thumbCache,
                                        loadThumbnail = {
                                            try {
                                                app.repository.readThumbnail(session, entry)
                                            } catch (e: Exception) {
                                                null
                                            }
                                        },
                                        selected = entry.id in selectedIds,
                                        onClick = { if (selecting) toggleSelected(entry) else openViewer(entry) },
                                        onLongClick = { toggleSelected(entry) },
                                    )
                                }
                            }
                        }
                    }
                }

                if (selecting) {
                    SelectionBar(
                        count = selectedEntries.size,
                        // Папки есть только у фото и видео.
                        canMove = selectedEntries.all { it.isMedia },
                        canRestore = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                        palette = palette,
                        onArchive = { askArchive = true },
                        onMove = { moveTargets = selectedEntries },
                        onRestore = { askBulkRestore = true },
                        onDelete = { askBulkDelete = true },
                    )
                } else Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                ) {
                    // Кнопки добавляют в тот раздел, который открыт.
                    if (section == Section.MEDIA) {
                        PrimaryButton(
                            text = stringResource(R.string.vault_add_media),
                            palette = palette,
                            modifier = Modifier.weight(1f),
                        ) { openMediaPicker() }
                        // Папки создаются только в общей сетке: папок внутри папок нет.
                        if (openFolder == null) {
                            Spacer(Modifier.width(8.dp))
                            PrimaryButton(
                                text = stringResource(R.string.folder_new_button),
                                palette = palette,
                            ) { creatingFolder = true }
                        }
                    } else {
                        PrimaryButton(
                            text = stringResource(R.string.vault_add_files),
                            palette = palette,
                            modifier = Modifier.weight(1f),
                        ) { openDocsPicker() }
                        Spacer(Modifier.width(8.dp))
                        PrimaryButton(
                            text = stringResource(R.string.note_new),
                            palette = palette,
                            modifier = Modifier.weight(1f),
                        ) { openNewNote() }
                    }
                }
            }
        }

        // Настройки — поверх сетки (её состояние сохраняется).
        if (showSettings) {
            FullScreenLayer {
                SettingsScreen(
                    app = app,
                    session = session,
                    palette = palette,
                    onClose = { showSettings = false },
                )
            }
        }

        // Редактор заметки — поверх сетки или просмотра (их состояние сохраняется).
        val edit = editing
        if (edit != null) {
            key(edit.token) {
                FullScreenLayer {
                    NoteEditorScreen(
                        title = edit.title,
                        initialText = edit.text,
                        palette = palette,
                        onSave = { text -> saveNote(text) },
                        onClose = {
                            editing = null
                            editingNoteId = null
                        },
                    )
                }
            }
        }

        val current = flow
        if (current is VaultFlow.Importing || current is VaultFlow.Restoring || current is VaultFlow.Exporting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC000000))
                    .clickable(onClick = {}),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = palette.opBg)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = if (current is VaultFlow.Importing) {
                            stringResource(R.string.import_progress, current.done, current.total)
                        } else if (current is VaultFlow.Exporting) {
                            stringResource(
                                R.string.archive_progress,
                                (current.done + 1).coerceAtMost(current.total),
                                current.total,
                            )
                        } else if (current is VaultFlow.Restoring && current.total > 1) {
                            stringResource(
                                R.string.restore_progress_many,
                                (current.done + 1).coerceAtMost(current.total),
                                current.total,
                            )
                        } else {
                            stringResource(R.string.restore_progress)
                        },
                        color = Color.White,
                        fontSize = 18.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.import_keep_open),
                        color = Color(0xCCFFFFFF),
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }

    // --- Диалоги ---

    val acting = actionsFor
    if (acting != null) {
        AlertDialog(
            onDismissRequest = { actionsFor = null },
            title = {
                Text(text = acting.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        DialogAction(text = stringResource(R.string.entry_restore), color = palette.opBg) {
                            actionsFor = null
                            restore(acting)
                        }
                    }
                    if (acting.isMedia) {
                        DialogAction(text = stringResource(R.string.entry_move), color = palette.opBg) {
                            actionsFor = null
                            moveTargets = listOf(acting)
                        }
                    }
                    DialogAction(text = stringResource(R.string.vault_delete_confirm), color = palette.error) {
                        actionsFor = null
                        toDelete = acting
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionsFor = null }) {
                    Text(text = stringResource(R.string.dialog_cancel))
                }
            },
        )
    }

    val deleting = toDelete
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(text = stringResource(R.string.vault_delete_title)) },
            text = { Text(text = stringResource(R.string.vault_delete_text, deleting.name)) },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            try {
                                app.repository.deleteEntry(session, deleting.id)
                            } catch (e: Exception) {
                                false
                            }
                        }
                        thumbCache.remove(deleting.id)
                        reload++
                    }
                }) {
                    Text(text = stringResource(R.string.vault_delete_confirm), color = palette.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) {
                    Text(text = stringResource(R.string.dialog_cancel))
                }
            },
        )
    }

    val docsToDelete = askDeleteDocs
    if (docsToDelete != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(text = stringResource(R.string.docs_delete_title)) },
            text = { Text(text = stringResource(R.string.docs_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    askDeleteDocs = null
                    scope.launch {
                        val failed = Importer.deleteDocuments(context, docsToDelete)
                        docsDeleteFailed = failed
                        originalsDeleted = failed == 0
                        showSummary = true
                    }
                }) {
                    Text(text = stringResource(R.string.docs_delete_confirm), color = palette.error)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    askDeleteDocs = null
                    originalsDeleted = false
                    showSummary = true
                }) {
                    Text(text = stringResource(R.string.docs_delete_keep))
                }
            },
        )
    }

    val s = summary
    if (showSummary && s != null) {
        val addedLine = stringResource(R.string.import_added, s.added)
        val duplicatesLine = if (s.duplicates > 0) stringResource(R.string.import_duplicates, s.duplicates) else null
        val failedLine = if (s.failed > 0) stringResource(R.string.import_failed, s.failed) else null
        // Какие файлы не добавились и почему — чтобы было понятно, что делать.
        val failureLines = s.failures.take(MAX_FAILURE_LINES).map { f ->
            val reason = when (f.problem) {
                ImportProblem.OPEN -> stringResource(R.string.import_reason_open)
                ImportProblem.CHANGED -> stringResource(R.string.import_reason_changed)
                ImportProblem.NO_SPACE -> stringResource(R.string.import_reason_space)
                ImportProblem.VERIFY -> stringResource(R.string.import_reason_verify)
                ImportProblem.OTHER -> stringResource(R.string.import_reason_other, f.code)
            }
            stringResource(R.string.import_failed_line, f.name, reason)
        }
        val moreFailedLine = if (s.failed > MAX_FAILURE_LINES) {
            stringResource(R.string.import_failed_more, s.failed - MAX_FAILURE_LINES)
        } else {
            null
        }
        val originalsLine = when (originalsDeleted) {
            true -> stringResource(R.string.import_originals_deleted)
            false -> stringResource(R.string.import_originals_kept)
            null -> null
        }
        val docsFailedLine = if (docsDeleteFailed > 0) {
            stringResource(R.string.docs_delete_failed, docsDeleteFailed)
        } else {
            null
        }
        val lines = listOfNotNull(addedLine, duplicatesLine, failedLine) + failureLines +
            listOfNotNull(moreFailedLine, originalsLine, docsFailedLine)
        AlertDialog(
            onDismissRequest = {},
            title = { Text(text = stringResource(R.string.import_done_title)) },
            text = { Text(text = lines.joinToString("\n")) },
            confirmButton = {
                TextButton(onClick = {
                    showSummary = false
                    summary = null
                    originalsDeleted = null
                    docsDeleteFailed = 0
                }) {
                    Text(text = stringResource(R.string.import_ok))
                }
            },
        )
    }

    if (noPermission) {
        AlertDialog(
            onDismissRequest = { noPermission = false },
            text = { Text(text = stringResource(R.string.permission_denied)) },
            confirmButton = {
                TextButton(onClick = { noPermission = false }) {
                    Text(text = stringResource(R.string.import_ok))
                }
            },
        )
    }

    // --- Папки ---

    val moving = moveTargets
    if (moving != null) {
        MoveDialog(
            // Все переносимые файлы лежат в одном месте — там, где их выбрали.
            currentFolderId = moving.firstOrNull()?.folderId,
            folders = folders,
            palette = palette,
            onMove = { target ->
                moveTargets = null
                moveEntries(moving, target)
            },
            onNewFolder = {
                moveTargets = null
                moveAfterCreate = moving
                creatingFolder = true
            },
            onDismiss = { moveTargets = null },
        )
    }

    // --- Выбранные файлы ---

    if (askArchive) {
        val files = selectedEntries
        AlertDialog(
            onDismissRequest = { askArchive = false },
            title = { Text(text = stringResource(R.string.archive_confirm_title)) },
            text = { Text(text = stringResource(R.string.archive_confirm_text, files.size)) },
            confirmButton = {
                TextButton(onClick = {
                    askArchive = false
                    chooseArchivePlace(files)
                }) {
                    Text(text = stringResource(R.string.archive_choose_place), color = palette.opBg)
                }
            },
            dismissButton = {
                TextButton(onClick = { askArchive = false }) {
                    Text(text = stringResource(R.string.dialog_cancel))
                }
            },
        )
    }

    if (askBulkRestore) {
        val files = selectedEntries
        AlertDialog(
            onDismissRequest = { askBulkRestore = false },
            title = { Text(text = stringResource(R.string.bulk_restore_title, files.size)) },
            text = { Text(text = stringResource(R.string.bulk_restore_text)) },
            confirmButton = {
                TextButton(onClick = {
                    askBulkRestore = false
                    restoreMany(files, folder = null)
                }) {
                    Text(text = stringResource(R.string.entry_restore), color = palette.opBg)
                }
            },
            dismissButton = {
                TextButton(onClick = { askBulkRestore = false }) {
                    Text(text = stringResource(R.string.dialog_cancel))
                }
            },
        )
    }

    if (askBulkDelete) {
        val files = selectedEntries
        AlertDialog(
            onDismissRequest = { askBulkDelete = false },
            title = { Text(text = stringResource(R.string.bulk_delete_title, files.size)) },
            text = { Text(text = stringResource(R.string.bulk_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    askBulkDelete = false
                    deleteMany(files)
                }) {
                    Text(text = stringResource(R.string.vault_delete_confirm), color = palette.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { askBulkDelete = false }) {
                    Text(text = stringResource(R.string.dialog_cancel))
                }
            },
        )
    }

    if (creatingFolder) {
        FolderNameDialog(
            title = stringResource(R.string.folder_new_title),
            confirmText = stringResource(R.string.folder_create),
            initialName = "",
            takenNames = folders.mapTo(HashSet()) { it.name.lowercase() },
            palette = palette,
            onConfirm = { name ->
                creatingFolder = false
                val thenMove = moveAfterCreate
                moveAfterCreate = null
                createFolder(name, thenMove)
            },
            onDismiss = {
                creatingFolder = false
                moveAfterCreate = null
            },
        )
    }

    val folderActing = folderActionsFor
    if (folderActing != null) {
        FolderActionsDialog(
            folder = folderActing,
            palette = palette,
            onRename = {
                folderActionsFor = null
                renamingFolder = folderActing
            },
            onDelete = {
                folderActionsFor = null
                deletingFolder = folderActing
            },
            // Возврат в Галерею работает на Android 10 и новее; пустую папку возвращать нечего.
            onRestore = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && (folderCounts[folderActing.id] ?: 0) > 0) {
                {
                    folderActionsFor = null
                    restoringFolder = folderActing
                }
            } else {
                null
            },
            onDismiss = { folderActionsFor = null },
        )
    }

    val renaming = renamingFolder
    if (renaming != null) {
        key(renaming.id) {
            FolderNameDialog(
                title = stringResource(R.string.folder_rename_title),
                confirmText = stringResource(R.string.folder_save),
                initialName = renaming.name,
                takenNames = folders.filter { it.id != renaming.id }.mapTo(HashSet()) { it.name.lowercase() },
                palette = palette,
                onConfirm = { name ->
                    renamingFolder = null
                    renameFolder(renaming, name)
                },
                onDismiss = { renamingFolder = null },
            )
        }
    }

    val folderRestoring = restoringFolder
    if (folderRestoring != null) {
        AlertDialog(
            onDismissRequest = { restoringFolder = null },
            title = { Text(text = stringResource(R.string.folder_restore_title, folderRestoring.name)) },
            text = {
                Text(text = stringResource(R.string.folder_restore_text, folderCounts[folderRestoring.id] ?: 0))
            },
            confirmButton = {
                TextButton(onClick = {
                    restoringFolder = null
                    restoreFolder(folderRestoring)
                }) {
                    Text(text = stringResource(R.string.entry_restore), color = palette.opBg)
                }
            },
            dismissButton = {
                TextButton(onClick = { restoringFolder = null }) {
                    Text(text = stringResource(R.string.dialog_cancel))
                }
            },
        )
    }

    val folderDeleting = deletingFolder
    if (folderDeleting != null) {
        DeleteFolderDialog(
            folder = folderDeleting,
            count = folderCounts[folderDeleting.id] ?: 0,
            palette = palette,
            onConfirm = {
                deletingFolder = null
                deleteFolder(folderDeleting)
            },
            onDismiss = { deletingFolder = null },
        )
    }
}

/** Переключатель разделов «Фото и видео» | «Файлы». */
@Composable
private fun SectionSwitch(
    selected: Section,
    palette: CalcPalette,
    onSelect: (Section) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(palette.digitBg)
            .padding(4.dp),
    ) {
        SectionTab(
            text = stringResource(R.string.section_media),
            selected = selected == Section.MEDIA,
            palette = palette,
            modifier = Modifier.weight(1f),
        ) { onSelect(Section.MEDIA) }
        SectionTab(
            text = stringResource(R.string.section_files),
            selected = selected == Section.FILES,
            palette = palette,
            modifier = Modifier.weight(1f),
        ) { onSelect(Section.FILES) }
    }
}

@Composable
private fun SectionTab(
    text: String,
    selected: Boolean,
    palette: CalcPalette,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) palette.opBg else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) palette.opText else palette.displaySecondary,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Строка в списке «Файлы»: значок или первая страница PDF, имя, дата и размер. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: VaultEntry,
    palette: CalcPalette,
    cache: LruCache<String, ImageBitmap>,
    loadThumbnail: () -> ByteArray?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val info = remember(entry.id, entry.size) {
        val time = if (entry.takenAt > 0) entry.takenAt else entry.addedAt
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(time)) +
            " · " + Formatter.formatShortFileSize(context, entry.size)
    }
    val bitmap by produceState<ImageBitmap?>(cache.get(entry.id), entry.id) {
        if (value == null && entry.thumbId != null) {
            val loaded = withContext(Dispatchers.IO) {
                loadThumbnail()?.let { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            }
            if (loaded != null) cache.put(entry.id, loaded)
            value = loaded
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) palette.opBg.copy(alpha = 0.18f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(palette.digitBg),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            when {
                selected -> SelectedMark(palette)
                bmp != null -> Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else -> Text(
                    text = extensionLabel(entry),
                    color = palette.opBg,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                color = palette.displayPrimary,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = info,
                color = palette.displaySecondary,
                fontSize = 13.sp,
                maxLines = 1,
            )
        }
    }
}

/** Строка-действие в диалоге: крупная, на всю ширину. */
@Composable
internal fun DialogAction(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text = text,
        color = color,
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    )
}

@Composable
private fun CenterNote(title: String, text: String?, palette: CalcPalette) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, color = palette.displayPrimary, fontSize = 20.sp, textAlign = TextAlign.Center)
        if (text != null) {
            Spacer(Modifier.height(8.dp))
            Text(text = text, color = palette.displaySecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultCell(
    entry: VaultEntry,
    palette: CalcPalette,
    cache: LruCache<String, ImageBitmap>,
    compact: Boolean,
    loadThumbnail: () -> ByteArray?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val bitmap by produceState<ImageBitmap?>(cache.get(entry.id), entry.id) {
        if (value == null && entry.thumbId != null) {
            val loaded = withContext(Dispatchers.IO) {
                loadThumbnail()?.let { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            }
            if (loaded != null) cache.put(entry.id, loaded)
            value = loaded
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .background(palette.digitBg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = extensionLabel(entry),
                    color = palette.opBg,
                    fontSize = if (compact) 14.sp else 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                // В мелкой сетке имя не помещается — только тип файла.
                if (!compact) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = entry.name,
                        color = palette.displaySecondary,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        if (bmp != null && entry.kind != EntryKind.PHOTO && entry.kind != EntryKind.VIDEO) {
            // У документа с миниатюрой (первая страница PDF) — подпись типа.
            Text(
                text = extensionLabel(entry),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(Color(0xCC3B74FF))
                    .padding(horizontal = 4.dp),
            )
        }
        if (entry.kind == EntryKind.VIDEO && entry.durationMs > 0) {
            Text(
                text = formatDuration(entry.durationMs),
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color(0x99000000))
                    .padding(horizontal = 4.dp),
            )
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x55000000))
                    .border(3.dp, palette.opBg),
            )
            SelectedMark(
                palette,
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
            )
        }
    }
}

/** Синий кружок с галочкой на отмеченном файле. */
@Composable
private fun SelectedMark(palette: CalcPalette, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(palette.opBg),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "✓", color = palette.opText, fontSize = 15.sp)
    }
}

/**
 * Нижняя панель в режиме выделения: крупная «Сохранить в архив» и под ней
 * «Переместить», «Восстановить», «Удалить».
 */
@Composable
private fun SelectionBar(
    count: Int,
    canMove: Boolean,
    canRestore: Boolean,
    palette: CalcPalette,
    onArchive: () -> Unit,
    onMove: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        PrimaryButton(
            text = stringResource(R.string.action_archive, count),
            palette = palette,
            enabled = count > 0,
            modifier = Modifier.fillMaxWidth(),
            onClick = onArchive,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            if (canMove) {
                SelectionAction(stringResource(R.string.entry_move), palette.opBg, count > 0, onMove)
            }
            if (canRestore) {
                SelectionAction(stringResource(R.string.entry_restore), palette.opBg, count > 0, onRestore)
            }
            SelectionAction(stringResource(R.string.vault_delete_confirm), palette.error, count > 0, onDelete)
        }
    }
}

@Composable
private fun RowScope.SelectionAction(text: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (enabled) color else color.copy(alpha = 0.4f),
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

/** "отчёт.pdf" → "PDF"; без расширения — по типу. */
internal fun extensionLabel(entry: VaultEntry): String {
    val ext = entry.name.substringAfterLast('.', "").uppercase()
    if (ext.isNotEmpty() && ext.length <= 5) return ext
    return when (entry.kind) {
        EntryKind.PHOTO -> "IMG"
        EntryKind.VIDEO -> "VID"
        EntryKind.PDF -> "PDF"
        EntryKind.NOTE -> "TXT"
        EntryKind.AUDIO -> "AUDIO"
        EntryKind.OTHER -> "FILE"
    }
}
