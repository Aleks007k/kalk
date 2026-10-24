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
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketcalc.calculator.R
import com.pocketcalc.calculator.media.DayGroup
import com.pocketcalc.calculator.media.MediaGallery
import com.pocketcalc.calculator.media.MediaItem
import com.pocketcalc.calculator.media.groupByDay
import com.pocketcalc.calculator.ui.theme.CalcPalette
import com.pocketcalc.calculator.ui.theme.calcPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * Выбор фото и видео из памяти телефона. Как в Галерее: новые сверху,
 * по дням — «Сегодня», «Вчера», дата; кружок у даты отмечает весь день.
 * Нажатие на снимок отмечает его, «Спрятать» передаёт выбранное в тайник.
 * Число колонок меняется щипком ([columns], [onColumnsStep]) — так же,
 * как в самом тайнике.
 */
@Composable
fun MediaPickerScreen(
    columns: Int,
    onColumnsStep: (Int) -> Unit,
    onCancel: () -> Unit,
    onConfirm: (List<MediaItem>) -> Unit,
    onRequestMoreAccess: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    val palette = calcPalette(isSystemInDarkTheme())
    val context = LocalContext.current

    var mediaItems by remember { mutableStateOf<List<MediaItem>?>(null) }
    var days by remember { mutableStateOf<List<DayGroup<MediaItem>>>(emptyList()) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val today = remember { LocalDate.now() }
    val partial = remember { MediaGallery.isPartialAccess(context) }
    val thumbCache = remember { thumbnailCache(32 * 1024 * 1024) }
    // Миниатюра примерно в ширину плитки: чётко и без лишней памяти.
    val screenWidthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val thumbPx = (screenWidthPx / columns).toInt().coerceIn(128, 512)

    LaunchedEffect(Unit) {
        val (all, grouped) = withContext(Dispatchers.IO) {
            val loaded = MediaGallery.load(context)
            loaded to groupByDay(loaded, ZoneId.systemDefault()) { it.takenAt }
        }
        days = grouped
        mediaItems = all
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

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pinchToChangeColumns(onColumnsStep),
        ) {
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
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    for (group in days) {
                        item(
                            key = "day-" + group.day,
                            span = { GridItemSpan(maxLineSpan) },
                            contentType = "day",
                        ) {
                            val keys = group.items.map { it.uri.toString() }
                            val allSelected = keys.all { it in selected }
                            DayHeader(
                                label = dayLabel(group.day, today),
                                allSelected = allSelected,
                                palette = palette,
                                onToggleAll = {
                                    selected = if (allSelected) selected - keys.toSet() else selected + keys
                                },
                            )
                        }
                        items(group.items, key = { it.uri.toString() }, contentType = { "media" }) { item ->
                            val key = item.uri.toString()
                            MediaCell(
                                item = item,
                                isSelected = key in selected,
                                palette = palette,
                                cache = thumbCache,
                                thumbPx = thumbPx,
                                onToggle = {
                                    selected = if (key in selected) selected - key else selected + key
                                },
                            )
                        }
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

/** «Сегодня», «Вчера», «Пт, 17 окт.» или, для прошлых лет, «17 окт. 2025 г.». */
@Composable
private fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> stringResource(R.string.picker_today)
    today.minusDays(1) -> stringResource(R.string.picker_yesterday)
    else -> remember(day, today) { formatDay(day, today) }
}

private fun formatDay(day: LocalDate, today: LocalDate): String {
    val locale = Locale.getDefault()
    val skeleton = if (day.year == today.year) "EEEdMMM" else "yMMMd"
    val date = Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant())
    val text = android.icu.text.DateFormat.getInstanceForSkeleton(skeleton, locale).format(date)
    return text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}

/** Заголовок дня: дата слева, справа кружок «отметить весь день». */
@Composable
private fun DayHeader(label: String, allSelected: Boolean, palette: CalcPalette, onToggleAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = palette.displayPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onToggleAll),
            contentAlignment = Alignment.Center,
        ) {
            if (allSelected) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(palette.opBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "✓", color = palette.opText, fontSize = 15.sp)
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .border(2.dp, palette.displaySecondary, CircleShape),
                )
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
    thumbPx: Int,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    val key = item.uri.toString() + "#" + thumbPx
    // При смене числа колонок старая миниатюра видна, пока грузится новая.
    val bitmap by produceState<ImageBitmap?>(cache.get(key), key) {
        val cached = cache.get(key)
        if (cached != null) {
            value = cached
            return@produceState
        }
        val loaded = withContext(Dispatchers.IO) {
            MediaGallery.systemThumbnail(context, item, thumbPx)?.asImageBitmap()
        }
        if (loaded != null) {
            cache.put(key, loaded)
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
