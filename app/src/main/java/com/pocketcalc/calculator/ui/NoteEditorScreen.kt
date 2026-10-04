package com.pocketcalc.calculator.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import com.pocketcalc.calculator.R
import com.pocketcalc.calculator.ui.theme.CalcPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Через сколько после последнего нажатия клавиши заметка сохраняется сама. */
private const val AUTOSAVE_DELAY_MS = 800L

private enum class SaveStatus { SAVED, SAVING, FAILED }

/**
 * Редактор заметки на весь экран. Текст сохраняется сам — меньше чем через
 * секунду после того, как перестали печатать: тайник запирается, как только
 * приложение уходит с экрана (звонок, кнопка «Домой»), и несохранённое
 * пропало бы. «Назад» сохраняет и закрывает.
 *
 * [onSave] шифрует и записывает текст, возвращает false при сбое.
 */
@Composable
fun NoteEditorScreen(
    title: String,
    initialText: String,
    palette: CalcPalette,
    onSave: suspend (String) -> Boolean,
    onClose: () -> Unit,
) {
    var currentText by remember { mutableStateOf(initialText) }
    var savedText by remember { mutableStateOf(initialText) }
    var status by remember { mutableStateOf(SaveStatus.SAVED) }
    val scope = rememberCoroutineScope()
    val saveLock = remember { Mutex() }

    // Сохранения идут строго по очереди: более старый текст не перезапишет новый.
    suspend fun saveNow(value: String): Boolean = saveLock.withLock {
        if (value == savedText) return@withLock true
        status = SaveStatus.SAVING
        val ok = onSave(value)
        if (ok) savedText = value
        status = if (ok) SaveStatus.SAVED else SaveStatus.FAILED
        ok
    }

    LaunchedEffect(currentText) {
        if (currentText == savedText) return@LaunchedEffect
        delay(AUTOSAVE_DELAY_MS)
        val value = currentText
        scope.launch { saveNow(value) }
    }

    fun close() {
        // Если сохранить не удалось, первый «Назад» оставляет текст на экране
        // (его можно скопировать); повторный — закрывает, как просили.
        val alreadyFailed = status == SaveStatus.FAILED
        scope.launch {
            if (saveNow(currentText) || alreadyFailed) onClose()
        }
    }

    BackHandler { close() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .safeDrawingPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(onClick = { close() }, size = 52.dp) {
                BackArrowIcon(modifier = Modifier.size(30.dp), color = palette.displayPrimary)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = title,
                color = palette.displayPrimary,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = when (status) {
                    SaveStatus.SAVED -> stringResource(R.string.note_saved)
                    SaveStatus.SAVING -> stringResource(R.string.note_saving)
                    SaveStatus.FAILED -> stringResource(R.string.note_save_failed)
                },
                color = if (status == SaveStatus.FAILED) palette.error else palette.displaySecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

        val hintText = stringResource(R.string.note_hint)
        AndroidView(
            factory = { context ->
                NoteEditText(context).apply {
                    setText(initialText)
                    setSelection(initialText.length)
                    setTextColor(palette.displayPrimary.toArgb())
                    setHintTextColor(palette.displaySecondary.toArgb())
                    hint = hintText
                    background = null
                    gravity = Gravity.TOP or Gravity.START
                    textSize = 18f
                    val pad = (16 * resources.displayMetrics.density).toInt()
                    setPadding(pad, pad / 2, pad, pad)
                    inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    // Клавиатура не запоминает слова из заметок и не открывается на весь экран.
                    imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    doAfterTextChanged { editable -> currentText = editable?.toString().orEmpty() }
                    // Курсор и клавиатура — когда поле уже на экране.
                    post {
                        requestFocus()
                        context.getSystemService(InputMethodManager::class.java)
                            ?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
    }
}

/**
 * Поле ввода заметки. Скопированный или вырезанный текст помечается как
 * секретный: Android 13+ не показывает его во всплывающем превью буфера
 * обмена. Вставка в другие приложения работает как обычно.
 */
private class NoteEditText(context: Context) : EditText(context) {

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.copy || id == android.R.id.cut) {
            val start = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
            val end = maxOf(selectionStart, selectionEnd).coerceAtLeast(0)
            val editable = text
            if (editable != null && end > start) {
                val clip = ClipData.newPlainText(null, editable.subSequence(start, end))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    clip.description.extras = PersistableBundle().apply {
                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                }
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
                if (id == android.R.id.cut) {
                    editable.delete(start, end)
                } else {
                    setSelection(end)
                }
                return true
            }
        }
        return super.onTextContextMenuItem(id)
    }
}
