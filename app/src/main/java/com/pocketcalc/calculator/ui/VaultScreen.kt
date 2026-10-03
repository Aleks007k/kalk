package com.pocketcalc.calculator.ui

import android.app.Activity
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.pocketcalc.calculator.media.ImportSummary
import com.pocketcalc.calculator.media.Importer
import com.pocketcalc.calculator.media.MediaGallery
import com.pocketcalc.calculator.media.MediaItem
import com.pocketcalc.calculator.ui.theme.CalcPalette
import com.pocketcalc.calculator.ui.theme.calcPalette
import com.pocketcalc.calculator.vault.EntryKind
import com.pocketcalc.calculator.vault.VaultEntry
import com.pocketcalc.calculator.vault.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Что сейчас происходит на экране тайника. */
private sealed interface VaultFlow {
    data object Browse : VaultFlow
    data object Picking : VaultFlow
    data class Importing(val done: Int, val total: Int) : VaultFlow
}

/**
 * Экран открытого тайника. Одинаков для настоящего и фальшивого тайника.
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
    var toDelete by remember { mutableStateOf<VaultEntry?>(null) }
    var noPermission by remember { mutableStateOf(false) }

    val thumbCache = remember { LruCache<String, ImageBitmap>(200) }
    val viewSoon = stringResource(R.string.vault_view_soon)

    BackHandler {
        when (flow) {
            is VaultFlow.Picking -> flow = VaultFlow.Browse
            is VaultFlow.Importing -> Unit // во время шифрования «Назад» не прерывает
            is VaultFlow.Browse -> onLock()
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
        flow = VaultFlow.Importing(0, items.size)
        view.keepScreenOn = true
        scope.launch {
            val s = try {
                Importer.importMedia(context, app.repository, session, items) { done, total ->
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
        if (flow is VaultFlow.Picking) {
            key(pickerKey) {
                MediaPickerScreen(
                    onCancel = { flow = VaultFlow.Browse },
                    onConfirm = { items -> importMedia(items) },
                    onRequestMoreAccess = { requestMediaAccess() },
                )
            }
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
                    Text(
                        text = stringResource(R.string.vault_title),
                        color = palette.displayPrimary,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onLock) {
                        Text(text = stringResource(R.string.vault_lock), color = palette.opBg, fontSize = 17.sp)
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    val list = entries
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
                            title = stringResource(R.string.vault_empty_title),
                            text = stringResource(R.string.vault_empty_text),
                            palette = palette,
                        )
                        else -> LazyVerticalGrid(
                            columns = GridCells.Adaptive(110.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(list, key = { it.id }) { entry ->
                                VaultCell(
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
                                    onClick = {
                                        Toast.makeText(context, viewSoon, Toast.LENGTH_SHORT).show()
                                    },
                                    onLongClick = { toDelete = entry },
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                ) {
                    PrimaryButton(
                        text = stringResource(R.string.vault_add_media),
                        palette = palette,
                        modifier = Modifier.weight(1f),
                    ) { openMediaPicker() }
                    Spacer(Modifier.width(8.dp))
                    PrimaryButton(
                        text = stringResource(R.string.vault_add_files),
                        palette = palette,
                        modifier = Modifier.weight(1f),
                    ) { openDocsPicker() }
                }
            }
        }

        val current = flow
        if (current is VaultFlow.Importing) {
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
                        text = stringResource(R.string.import_progress, current.done, current.total),
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
        val lines = listOfNotNull(addedLine, duplicatesLine, failedLine, originalsLine, docsFailedLine)
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
    loadThumbnail: () -> ByteArray?,
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
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
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
    }
}

/** "отчёт.pdf" → "PDF"; без расширения — по типу. */
private fun extensionLabel(entry: VaultEntry): String {
    val ext = entry.name.substringAfterLast('.', "").uppercase()
    if (ext.isNotEmpty() && ext.length <= 5) return ext
    return when (entry.kind) {
        EntryKind.PHOTO -> "IMG"
        EntryKind.VIDEO -> "VID"
        EntryKind.PDF -> "PDF"
        EntryKind.NOTE -> "TXT"
        EntryKind.OTHER -> "FILE"
    }
}
