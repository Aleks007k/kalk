package com.pocketcalc.calculator.ui

import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketcalc.calculator.R
import com.pocketcalc.calculator.media.MediaGallery
import com.pocketcalc.calculator.media.MediaItem
import com.pocketcalc.calculator.ui.theme.CalcPalette
import com.pocketcalc.calculator.ui.theme.calcPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Выбор фото и видео из памяти телефона: сетка миниатюр, нажатие отмечает,
 * «Спрятать» передаёт выбранное в тайник.
 */
@Composable
fun MediaPickerScreen(
    onCancel: () -> Unit,
    onConfirm: (List<MediaItem>) -> Unit,
    onRequestMoreAccess: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    val palette = calcPalette(isSystemInDarkTheme())
    val context = LocalContext.current

    var mediaItems by remember { mutableStateOf<List<MediaItem>?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val partial = remember { MediaGallery.isPartialAccess(context) }
    val thumbCache = remember { LruCache<String, ImageBitmap>(300) }

    LaunchedEffect(Unit) {
        mediaItems = withContext(Dispatchers.IO) { MediaGallery.load(context) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .safeDrawingPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) {
                Text(text = stringResource(R.string.dialog_cancel), color = palette.displaySecondary)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = if (selected.isEmpty()) {
                    stringResource(R.string.picker_title)
                } else {
                    stringResource(R.string.picker_selected, selected.size)
                },
                color = palette.displayPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
        }

        if (partial) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.picker_partial),
                    color = palette.displaySecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRequestMoreAccess) {
                    Text(text = stringResource(R.string.picker_partial_more), color = palette.opBg)
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val list = mediaItems
            when {
                list == null -> CircularProgressIndicator(
                    color = palette.opBg,
                    modifier = Modifier.align(Alignment.Center),
                )
                list.isEmpty() -> Text(
                    text = stringResource(R.string.picker_empty),
                    color = palette.displaySecondary,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(100.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(list, key = { it.uri.toString() }) { item ->
                        val key = item.uri.toString()
                        MediaCell(
                            item = item,
                            isSelected = key in selected,
                            palette = palette,
                            cache = thumbCache,
                            onToggle = {
                                selected = if (key in selected) selected - key else selected + key
                            },
                        )
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            PrimaryButton(
                text = if (selected.isEmpty()) {
                    stringResource(R.string.picker_hide)
                } else {
                    stringResource(R.string.picker_hide) + " (" + selected.size + ")"
                },
                palette = palette,
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val chosen = mediaItems.orEmpty().filter { it.uri.toString() in selected }
                onConfirm(chosen)
            }
        }
    }
}

@Composable
private fun MediaCell(
    item: MediaItem,
    isSelected: Boolean,
    palette: CalcPalette,
    cache: LruCache<String, ImageBitmap>,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    val key = item.uri.toString()
    val bitmap by produceState<ImageBitmap?>(cache.get(key), key) {
        if (value == null) {
            val loaded = withContext(Dispatchers.IO) {
                MediaGallery.systemThumbnail(context, item, 256)?.asImageBitmap()
            }
            if (loaded != null) cache.put(key, loaded)
            value = loaded
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(1.dp)
            .background(palette.digitBg)
            .clickable(onClick = onToggle),
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (item.isVideo) {
            Text(
                text = formatDuration(item.durationMs),
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color(0x99000000))
                    .padding(horizontal = 4.dp),
            )
        }
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x55000000))
                    .border(3.dp, palette.opBg),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(palette.opBg),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "✓", color = palette.opText, fontSize = 15.sp)
            }
        }
    }
}

/** 75000 мс → "1:15". */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return ""
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%d:%02d".format(m, s)
    }
}
