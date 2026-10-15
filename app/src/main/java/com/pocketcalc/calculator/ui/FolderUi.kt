package com.pocketcalc.calculator.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import com.pocketcalc.calculator.R
import com.pocketcalc.calculator.ui.theme.CalcPalette
import com.pocketcalc.calculator.vault.VaultEntry
import com.pocketcalc.calculator.vault.VaultFolder

/** Подпись «Папки» над списком папок (под фото и видео). */
@Composable
internal fun FoldersHeader(palette: CalcPalette) {
    Text(
        text = stringResource(R.string.folders_header),
        color = palette.displaySecondary,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 10.dp, top = 18.dp, bottom = 4.dp),
    )
}

/** Строка папки: значок, название и число файлов в ней. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderRow(
    folder: VaultFolder,
    count: Int,
    palette: CalcPalette,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(palette.digitBg),
            contentAlignment = Alignment.Center,
        ) {
            FolderIcon(modifier = Modifier.size(28.dp), color = palette.equalsBg)
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = folder.name,
            color = palette.displayPrimary,
            fontSize = 17.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = count.toString(), color = palette.displaySecondary, fontSize = 15.sp)
    }
}

/**
 * Окно с названием папки: новая папка или переименование.
 * [takenNames] — названия других папок в нижнем регистре: два одинаковых
 * названия запутали бы.
 */
@Composable
internal fun FolderNameDialog(
    title: String,
    confirmText: String,
    initialName: String,
    takenNames: Set<String>,
    palette: CalcPalette,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialName) }
    val clean = VaultFolder.cleanName(text)
    val taken = clean.lowercase() in takenNames
    val canSave = clean.isNotEmpty() && !taken
    val confirm: () -> Unit = { if (canSave) onConfirm(clean) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                PrivateTextField(
                    initialText = initialName,
                    hint = stringResource(R.string.folder_name_hint),
                    maxLength = VaultFolder.MAX_NAME,
                    textColor = MaterialTheme.colorScheme.onSurface,
                    hintColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    outlineColor = palette.opBg,
                    onTextChange = { text = it },
                    onDone = confirm,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (taken) {
                    Spacer(Modifier.height(6.dp))
                    Text(text = stringResource(R.string.folder_name_taken), color = palette.error, fontSize = 14.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = confirm, enabled = canSave) {
                Text(text = confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.dialog_cancel))
            }
        },
    )
}

/**
 * Поле ввода в одну строку. Как и в заметках, клавиатура не запоминает
 * набранные слова и не открывается на весь экран: названия папок — тоже секрет.
 */
@Composable
private fun PrivateTextField(
    initialText: String,
    hint: String,
    maxLength: Int,
    textColor: Color,
    hintColor: Color,
    outlineColor: Color,
    onTextChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnTextChange by rememberUpdatedState(onTextChange)
    val currentOnDone by rememberUpdatedState(onDone)
    AndroidView(
        factory = { context ->
            PrivateEditText(context).apply {
                val density = resources.displayMetrics.density
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = EditorInfo.IME_ACTION_DONE or
                    EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
                    EditorInfo.IME_FLAG_NO_EXTRACT_UI
                filters = arrayOf<InputFilter>(InputFilter.LengthFilter(maxLength))
                setText(initialText)
                setSelection(text.length)
                setTextColor(textColor.toArgb())
                setHintTextColor(hintColor.toArgb())
                this.hint = hint
                textSize = 18f
                background = GradientDrawable().apply {
                    cornerRadius = 12 * density
                    setStroke((1.5f * density).toInt().coerceAtLeast(1), outlineColor.toArgb())
                }
                val pad = (12 * density).toInt()
                setPadding(pad, pad, pad, pad)
                doAfterTextChanged { editable -> currentOnTextChange(editable?.toString().orEmpty()) }
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_DONE) {
                        currentOnDone()
                        true
                    } else {
                        false
                    }
                }
                post { showKeyboardWhenReady() }
            }
        },
        modifier = modifier,
    )
}

private class PrivateEditText(context: Context) : EditText(context) {
    private var keyboardWanted = false

    /**
     * Курсор и клавиатура. Система показывает клавиатуру только окну с фокусом,
     * а окно диалога получает фокус чуть позже, чем поле появляется на экране.
     */
    fun showKeyboardWhenReady() {
        if (hasWindowFocus()) showKeyboard() else keyboardWanted = true
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && keyboardWanted) {
            keyboardWanted = false
            showKeyboard()
        }
    }

    private fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }
}

/**
 * «Переместить в…»: общая сетка (если файл сейчас в папке), другие папки
 * и «+ Новая папка».
 */
@Composable
internal fun MoveDialog(
    entry: VaultEntry,
    folders: List<VaultFolder>,
    palette: CalcPalette,
    onMove: (VaultFolder?) -> Unit,
    onNewFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.move_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (entry.folderId != null) {
                    MoveTarget(text = stringResource(R.string.move_to_main), palette = palette, showIcon = false) {
                        onMove(null)
                    }
                }
                for (folder in folders) {
                    if (folder.id != entry.folderId) {
                        MoveTarget(text = folder.name, palette = palette, showIcon = true) { onMove(folder) }
                    }
                }
                DialogAction(text = stringResource(R.string.move_new_folder), color = palette.opBg, onClick = onNewFolder)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.dialog_cancel))
            }
        },
    )
}

@Composable
private fun MoveTarget(text: String, palette: CalcPalette, showIcon: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showIcon) {
            FolderIcon(modifier = Modifier.size(24.dp), color = palette.equalsBg)
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 18.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Долгое нажатие на папку: «Переименовать» или «Удалить папку». */
@Composable
internal fun FolderActionsDialog(
    folder: VaultFolder,
    palette: CalcPalette,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = folder.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                DialogAction(text = stringResource(R.string.folder_rename), color = palette.opBg, onClick = onRename)
                DialogAction(text = stringResource(R.string.folder_delete), color = palette.error, onClick = onDelete)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.dialog_cancel))
            }
        },
    )
}

/** Подтверждение удаления папки. Файлы из неё не удаляются, а возвращаются в общую сетку. */
@Composable
internal fun DeleteFolderDialog(
    folder: VaultFolder,
    count: Int,
    palette: CalcPalette,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.folder_delete_title, folder.name)) },
        text = if (count > 0) {
            { Text(text = stringResource(R.string.folder_delete_text, count)) }
        } else {
            null
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.vault_delete_confirm), color = palette.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.dialog_cancel))
            }
        },
    )
}
