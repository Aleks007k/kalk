package com.pocketcalc.calculator.ui

import android.graphics.BitmapFactory
import android.graphics.drawable.Animatable
import android.text.format.Formatter
import android.util.LruCache
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.pocketcalc.calculator.R
import com.pocketcalc.calculator.vault.EntryKind
import com.pocketcalc.calculator.vault.VaultEntry
import com.pocketcalc.calculator.vault.VaultRepository
import com.pocketcalc.calculator.vault.VaultSession
import com.pocketcalc.calculator.viewer.DecodedImage
import com.pocketcalc.calculator.viewer.PhotoDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/** Ключи пустых страниц до первого и после последнего файла. */
private const val EDGE_START = "edge:start"
private const val EDGE_END = "edge:end"

/** Фото больше этого размера не открываются в памяти (их можно восстановить). */
private const val MAX_PHOTO_BYTES = 200L * 1024 * 1024

/** Не больше двух фото расшифровываются и раскодируются одновременно — бережём память. */
private val decodeDispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()

/**
 * Просмотр файлов на весь экран с листанием влево-вправо в том же порядке,
 * что и в сетке. Фото и видео увеличиваются пальцами и двойным нажатием,
 * у видео — перемотка ползунком и на 10 секунд кнопками. Всё
 * расшифровывается в память — на диск ничего не пишется. Нажатие прячет и показывает верхнюю панель; «⋮» — восстановить
 * или удалить (то же меню, что при долгом нажатии в сетке).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ViewerScreen(
    entries: List<VaultEntry>,
    startEntryId: String,
    repository: VaultRepository,
    session: VaultSession,
    thumbCache: LruCache<String, ImageBitmap>,
    /** Папка для запасного пути показа PDF на старых Android (см. VaultPdf). */
    docTmpDir: File,
    canClose: Boolean,
    onClose: (lastEntryId: String?) -> Unit,
    onActions: (VaultEntry) -> Unit,
    /** «Изменить» у заметки: запись и её уже прочитанный текст. */
    onEditNote: (VaultEntry, String) -> Unit,
) {
    val startIndex = remember { entries.indexOfFirst { it.id == startEntryId }.coerceAtLeast(0) }
    // До первого и после последнего файла — пустые страницы-«края»: если
    // долистать до такой страницы, просмотр закрывается и видна сетка.
    // Страница p показывает файл entries[p − 1].
    val pagerState = rememberPagerState(initialPage = startIndex + 1) { entries.size + 2 }
    var chromeVisible by remember { mutableStateOf(true) }
    val current = entries.getOrNull(pagerState.currentPage - 1)

    BackHandler(enabled = canClose) { onClose(current?.id) }
    ViewerSystemBars(hidden = !chromeVisible)

    // Удалили или вернули последний файл — смотреть больше нечего.
    LaunchedEffect(entries.isEmpty()) {
        if (entries.isEmpty()) onClose(null)
    }

    // Долистали за край — назад к сетке (к первому или последнему файлу).
    LaunchedEffect(pagerState.settledPage, entries.size, canClose) {
        val settled = pagerState.settledPage
        if (canClose && entries.isNotEmpty()) {
            when (settled) {
                0 -> onClose(entries.first().id)
                entries.size + 1 -> onClose(entries.last().id)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            key = { index ->
                when (index) {
                    0 -> EDGE_START
                    entries.size + 1 -> EDGE_END
                    else -> entries[index - 1].id
                }
            },
        ) { page ->
            val entry = entries.getOrNull(page - 1)
            if (entry == null) {
                // Страница-«край»: пусто, сейчас закроется.
                Box(modifier = Modifier.fillMaxSize())
            } else {
                val toggleChrome = { chromeVisible = !chromeVisible }
                when (entry.kind) {
                    EntryKind.PHOTO -> PhotoPage(entry, repository, session, thumbCache, onTap = toggleChrome)
                    EntryKind.VIDEO -> VideoPage(
                        entry = entry,
                        repository = repository,
                        session = session,
                        placeholder = rememberThumbnail(entry, repository, session, thumbCache),
                        active = pagerState.settledPage == page,
                        chromeVisible = chromeVisible,
                        onTap = toggleChrome,
                        onChromeVisible = { chromeVisible = it },
                    )
                    EntryKind.PDF -> PdfPage(
                        entry = entry,
                        repository = repository,
                        session = session,
                        tmpDir = docTmpDir,
                        chromeVisible = chromeVisible,
                        onTap = toggleChrome,
                    )
                    EntryKind.NOTE -> NotePage(
                        entry = entry,
                        repository = repository,
                        session = session,
                        onEdit = { text -> onEditNote(entry, text) },
                    )
                    EntryKind.OTHER -> FilePage(entry, onTap = toggleChrome)
                }
            }
        }

        AnimatedVisibility(
            visible = chromeVisible && current != null,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            if (current != null) {
                ViewerTopBar(
                    entry = current,
                    onBack = { if (canClose) onClose(current.id) },
                    onMore = { onActions(current) },
                )
            }
        }
    }
}

// --- Фото ---------------------------------------------------------------

private sealed interface PhotoState {
    data object Loading : PhotoState
    class Ready(val image: DecodedImage) : PhotoState
    data object TooBig : PhotoState
    data object Failed : PhotoState
}

@Composable
private fun PhotoPage(
    entry: VaultEntry,
    repository: VaultRepository,
    session: VaultSession,
    thumbCache: LruCache<String, ImageBitmap>,
    onTap: () -> Unit,
) {
    val placeholder = rememberThumbnail(entry, repository, session, thumbCache)
    val state by produceState<PhotoState>(PhotoState.Loading, entry.id) {
        value = if (entry.size > MAX_PHOTO_BYTES) {
            PhotoState.TooBig
        } else {
            try {
                val image = withContext(decodeDispatcher) {
                    PhotoDecoder.decode(repository.readContent(session, entry, MAX_PHOTO_BYTES), entry.mime)
                }
                PhotoState.Ready(image)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PhotoState.Failed
            } catch (e: OutOfMemoryError) {
                PhotoState.Failed
            }
        }
    }
    when (val s = state) {
        is PhotoState.Ready -> ZoomablePhoto(s.image, onTap)
        PhotoState.Loading -> LoadingPage(placeholder, onTap)
        PhotoState.TooBig -> InfoPage(title = null, subtitle = null, text = stringResource(R.string.viewer_too_big), onTap = onTap)
        PhotoState.Failed -> InfoPage(title = null, subtitle = null, text = stringResource(R.string.viewer_cant_show), onTap = onTap)
    }
}

@Composable
private fun ZoomablePhoto(image: DecodedImage, onTap: () -> Unit) {
    val zoomState = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 4f))
    LaunchedEffect(image) {
        zoomState.setContentLocation(
            ZoomableContentLocation.scaledInsideAndCenterAligned(
                Size(image.width.toFloat(), image.height.toFloat()),
            ),
        )
    }
    val modifier = Modifier
        .fillMaxSize()
        .zoomable(zoomState, onClick = { onTap() })
    when (image) {
        is DecodedImage.Still -> {
            val bitmap = remember(image) { image.bitmap.asImageBitmap() }
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = modifier,
                alignment = Alignment.Center,
                contentScale = ContentScale.Inside,
            )
        }
        is DecodedImage.Animated -> {
            DisposableEffect(image) {
                onDispose { (image.drawable as? Animatable)?.stop() }
            }
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        setImageDrawable(image.drawable)
                        (image.drawable as? Animatable)?.start()
                    }
                },
                modifier = modifier,
            )
        }
    }
}

// --- Прочие файлы и сообщения -------------------------------------------

/** Файлы, которые нельзя посмотреть (архивы, документы Word и т.п.): только тип и имя. */
@Composable
private fun FilePage(entry: VaultEntry, onTap: () -> Unit) {
    InfoPage(
        title = extensionLabel(entry),
        subtitle = entry.name,
        text = stringResource(R.string.viewer_other),
        onTap = onTap,
    )
}

@Composable
internal fun InfoPage(title: String?, subtitle: String?, text: String, onTap: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (title != null) {
            Text(text = title, color = Color(0xFF6E9BFF), fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
        }
        if (subtitle != null) {
            Text(text = subtitle, color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
        }
        Text(text = text, color = Color(0xB3FFFFFF), fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun LoadingPage(placeholder: ImageBitmap?, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
        contentAlignment = Alignment.Center,
    ) {
        if (placeholder != null) {
            Image(placeholder, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        CircularProgressIndicator(color = Color.White)
    }
}

/** Миниатюра из сетки (или расшифрованная заново) — видна, пока грузится сам файл. */
@Composable
private fun rememberThumbnail(
    entry: VaultEntry,
    repository: VaultRepository,
    session: VaultSession,
    cache: LruCache<String, ImageBitmap>,
): ImageBitmap? {
    val thumbnail by produceState<ImageBitmap?>(cache.get(entry.id), entry.id) {
        if (value == null && entry.thumbId != null) {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    repository.readThumbnail(session, entry)?.let { bytes ->
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (loaded != null) {
                cache.put(entry.id, loaded)
                value = loaded
            }
        }
    }
    return thumbnail
}

// --- Верхняя панель и системные полосы ----------------------------------

@Composable
private fun ViewerTopBar(entry: VaultEntry, onBack: () -> Unit, onMore: () -> Unit) {
    val context = LocalContext.current
    val info = remember(entry.id) {
        val time = if (entry.takenAt > 0) entry.takenAt else entry.addedAt
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time)) +
            " · " + Formatter.formatShortFileSize(context, entry.size)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIconButton(onClick = onBack, size = 52.dp) {
            BackArrowIcon(modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                color = Color.White,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = info,
                color = Color(0xB3FFFFFF),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RoundIconButton(onClick = onMore, size = 52.dp) {
            MoreIcon(modifier = Modifier.size(28.dp))
        }
    }
}

/**
 * На чёрном фоне значки строки состояния светлые. Когда панель спрятана,
 * прячутся и системные полосы (вернуть — смахнуть от края экрана).
 * При выходе из просмотра всё возвращается как было.
 */
@Composable
private fun ViewerSystemBars(hidden: Boolean) {
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window } ?: return
    DisposableEffect(window) {
        val controller = WindowCompat.getInsetsController(window, view)
        val lightStatus = controller.isAppearanceLightStatusBars
        val lightNavigation = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = lightStatus
            controller.isAppearanceLightNavigationBars = lightNavigation
        }
    }
    LaunchedEffect(window, hidden) {
        val controller = WindowCompat.getInsetsController(window, view)
        if (hidden) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
