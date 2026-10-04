package com.pocketcalc.calculator.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketcalc.calculator.R
import com.pocketcalc.calculator.vault.TextCodec
import com.pocketcalc.calculator.vault.VaultEntry
import com.pocketcalc.calculator.vault.VaultRepository
import com.pocketcalc.calculator.vault.VaultSession
import com.pocketcalc.calculator.viewer.VaultPdf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import java.io.File

/** PDF больше этого размера не открываются в памяти (их можно восстановить). */
private const val MAX_PDF_BYTES = 200L * 1024 * 1024

/** Заметки больше этого размера не показываются (такой TXT — скорее книга, чем заметка). */
private const val MAX_NOTE_BYTES = 2L * 1024 * 1024

/** Состояние загрузки документа. */
private sealed interface DocState<out T> {
    data object Loading : DocState<Nothing>
    class Ready<T>(val value: T) : DocState<T>
    data object TooBig : DocState<Nothing>
    data object Locked : DocState<Nothing>
    data object Failed : DocState<Nothing>
}

// --- PDF ----------------------------------------------------------------

/**
 * PDF в просмотре: страницы листаются вверх-вниз (влево-вправо — другие
 * файлы), каждую можно увеличить пальцами или двойным нажатием.
 */
@Composable
internal fun PdfPage(
    entry: VaultEntry,
    repository: VaultRepository,
    session: VaultSession,
    tmpDir: File,
    chromeVisible: Boolean,
    onTap: () -> Unit,
) {
    val state by produceState<DocState<VaultPdf>>(DocState.Loading, entry.id) {
        if (entry.size > MAX_PDF_BYTES) {
            value = DocState.TooBig
            return@produceState
        }
        // Открытие не прерываем на полпути, иначе открытый документ потерялся бы
        // незакрытым; если страницу уже пролистали — сразу закрываем ниже.
        val result: DocState<VaultPdf> = withContext(NonCancellable + Dispatchers.IO) {
            try {
                DocState.Ready(VaultPdf.open(repository.readContent(session, entry, MAX_PDF_BYTES), tmpDir))
            } catch (e: SecurityException) {
                DocState.Locked
            } catch (e: Exception) {
                DocState.Failed
            } catch (e: OutOfMemoryError) {
                DocState.Failed
            }
        }
        value = result
        if (result is DocState.Ready) {
            awaitDispose { result.value.close() }
        }
    }
    when (val s = state) {
        is DocState.Ready -> PdfPager(s.value, chromeVisible, onTap)
        DocState.Loading -> LoadingPage(placeholder = null, onTap = onTap)
        DocState.TooBig -> InfoPage(null, null, stringResource(R.string.viewer_too_big), onTap)
        DocState.Locked -> InfoPage(null, null, stringResource(R.string.viewer_pdf_locked), onTap)
        DocState.Failed -> InfoPage(null, null, stringResource(R.string.viewer_cant_show), onTap)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PdfPager(pdf: VaultPdf, chromeVisible: Boolean, onTap: () -> Unit) {
    val pagerState = rememberPagerState { pdf.pageCount }
    // Страница рисуется в полтора раза шире экрана — чётко и при увеличении.
    val screenWidthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val renderWidth = (screenWidthPx * 1.5f).toInt().coerceIn(600, 2048)
    Box(modifier = Modifier.fillMaxSize()) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            PdfPageImage(pdf, index, renderWidth, onTap)
        }
        if (chromeVisible && pdf.pageCount > 1) {
            Text(
                text = "${pagerState.currentPage + 1} / ${pdf.pageCount}",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

private sealed interface PageImage {
    data object Loading : PageImage
    class Ready(val bitmap: ImageBitmap) : PageImage
    data object Failed : PageImage
}

@Composable
private fun PdfPageImage(pdf: VaultPdf, index: Int, widthPx: Int, onTap: () -> Unit) {
    val image by produceState<PageImage>(PageImage.Loading, pdf, index, widthPx) {
        value = try {
            PageImage.Ready(withContext(Dispatchers.IO) { pdf.render(index, widthPx).asImageBitmap() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PageImage.Failed
        } catch (e: OutOfMemoryError) {
            PageImage.Failed
        }
    }
    when (val s = image) {
        is PageImage.Ready -> {
            val zoomState = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 4f))
            LaunchedEffect(s.bitmap) {
                zoomState.setContentLocation(
                    ZoomableContentLocation.scaledInsideAndCenterAligned(
                        Size(s.bitmap.width.toFloat(), s.bitmap.height.toFloat()),
                    ),
                )
            }
            Image(
                bitmap = s.bitmap,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .zoomable(zoomState, onClick = { onTap() }),
                alignment = Alignment.Center,
                contentScale = ContentScale.Inside,
            )
        }
        PageImage.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        PageImage.Failed -> InfoPage(null, null, stringResource(R.string.viewer_cant_show), onTap)
    }
}

// --- Заметки (TXT) ------------------------------------------------------

/**
 * Заметка в просмотре: текст и кнопка «Изменить». [onEdit] получает уже
 * прочитанный текст — редактор открывается сразу, без повторной расшифровки.
 */
@Composable
internal fun NotePage(
    entry: VaultEntry,
    repository: VaultRepository,
    session: VaultSession,
    onEdit: (String) -> Unit,
) {
    // Ключ включает отпечаток: после правки текст перечитывается.
    val state by produceState<DocState<String>>(DocState.Loading, entry.id, entry.sha256) {
        value = if (entry.size > MAX_NOTE_BYTES) {
            DocState.TooBig
        } else {
            try {
                DocState.Ready(
                    withContext(Dispatchers.IO) {
                        TextCodec.decode(repository.readContent(session, entry, MAX_NOTE_BYTES))
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DocState.Failed
            }
        }
    }
    when (val s = state) {
        is DocState.Ready -> NoteText(s.value, onEdit = { onEdit(s.value) })
        DocState.Loading -> LoadingPage(placeholder = null, onTap = {})
        DocState.TooBig -> InfoPage(null, null, stringResource(R.string.viewer_too_big), onTap = {})
        DocState.Locked, DocState.Failed -> InfoPage(null, null, stringResource(R.string.viewer_cant_show), onTap = {})
    }
}

@Composable
private fun NoteText(text: String, onEdit: () -> Unit) {
    // Абзацы по отдельности: длинный текст листается без задержек.
    val paragraphs = remember(text) { text.split('\n') }
    Box(modifier = Modifier.fillMaxSize()) {
        if (text.isBlank()) {
            Text(
                text = stringResource(R.string.note_empty),
                color = Color(0x99FFFFFF),
                fontSize = 17.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 104.dp, bottom = 120.dp),
            ) {
                items(paragraphs.size) { i ->
                    Text(
                        text = paragraphs[i].ifEmpty { " " },
                        color = Color.White,
                        fontSize = 17.sp,
                        lineHeight = 25.sp,
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.note_edit),
            color = Color.White,
            fontSize = 17.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 20.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Color(0xFF3B74FF))
                .clickable(onClick = onEdit)
                .padding(horizontal = 32.dp, vertical = 14.dp),
        )
    }
}
