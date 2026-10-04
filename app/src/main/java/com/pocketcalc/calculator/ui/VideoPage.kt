package com.pocketcalc.calculator.ui

import android.os.SystemClock
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import com.pocketcalc.calculator.R
import com.pocketcalc.calculator.vault.VaultEntry
import com.pocketcalc.calculator.vault.VaultRepository
import com.pocketcalc.calculator.vault.VaultSession
import com.pocketcalc.calculator.viewer.VaultPlayer
import kotlinx.coroutines.delay
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

/** Через сколько панели прячутся сами, пока видео играет. */
private const val AUTO_HIDE_MS = 3_000L

/** Как часто обновлять время и ползунок. */
private const val POSITION_TICK_MS = 250L

/**
 * Видео в просмотре. Плеер создаётся только для страницы, на которой
 * остановилось листание, и освобождается, когда её пролистали или тайник
 * закрылся. Пока страница не активна — миниатюра со значком «играть».
 */
@Composable
internal fun VideoPage(
    entry: VaultEntry,
    repository: VaultRepository,
    session: VaultSession,
    placeholder: ImageBitmap?,
    active: Boolean,
    chromeVisible: Boolean,
    onTap: () -> Unit,
    onChromeVisible: (Boolean) -> Unit,
) {
    if (active) {
        ActiveVideo(entry, repository, session, placeholder, chromeVisible, onTap, onChromeVisible)
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
            contentAlignment = Alignment.Center,
        ) {
            if (placeholder != null) {
                Image(placeholder, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color(0x99000000)),
                contentAlignment = Alignment.Center,
            ) {
                PlayPauseIcon(showPause = false, modifier = Modifier.size(34.dp))
            }
        }
    }
}

/**
 * Кадры выводятся в TextureView: его, в отличие от SurfaceView, можно
 * увеличивать пальцами так же, как фото. Окно тайника защищено от
 * скриншотов целиком, поэтому и кадры видео на них не попадают.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun ActiveVideo(
    entry: VaultEntry,
    repository: VaultRepository,
    session: VaultSession,
    placeholder: ImageBitmap?,
    chromeVisible: Boolean,
    onTap: () -> Unit,
    onChromeVisible: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val currentOnChromeVisible by rememberUpdatedState(onChromeVisible)
    val exoPlayer = remember(entry.id) { VaultPlayer.create(context, repository, session, entry) }
    var videoSize by remember(entry.id) { mutableStateOf(Size.Unspecified) }
    var playing by remember(entry.id) { mutableStateOf(false) }
    var wantsToPlay by remember(entry.id) { mutableStateOf(true) }
    var ended by remember(entry.id) { mutableStateOf(false) }
    var firstFrame by remember(entry.id) { mutableStateOf(false) }
    var failed by remember(entry.id) { mutableStateOf(false) }
    var lastInteraction by remember(entry.id) { mutableLongStateOf(0L) }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) {
                    videoSize = Size(size.width * size.pixelWidthHeightRatio, size.height.toFloat())
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                wantsToPlay = playWhenReady
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                ended = playbackState == Player.STATE_ENDED
                if (ended) currentOnChromeVisible(true)
            }

            override fun onRenderedFirstFrame() {
                firstFrame = true
            }

            override fun onPlayerError(error: PlaybackException) {
                failed = true
                currentOnChromeVisible(true)
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Пока видео играет, панели прячутся сами через 3 секунды без касаний.
    LaunchedEffect(chromeVisible, playing, lastInteraction) {
        if (chromeVisible && playing) {
            delay(AUTO_HIDE_MS)
            currentOnChromeVisible(false)
        }
    }

    val zoomState = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 4f))
    LaunchedEffect(videoSize) {
        if (videoSize.isSpecified) {
            zoomState.setContentLocation(ZoomableContentLocation.scaledToFitAndCenterAligned(videoSize))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zoomable(zoomState, onClick = { onTap() }),
            contentAlignment = Alignment.Center,
        ) {
            val ratio = if (videoSize.isSpecified) videoSize.width / videoSize.height else 16f / 9f
            AndroidView(
                factory = { viewContext ->
                    TextureView(viewContext).also { exoPlayer.setVideoTextureView(it) }
                },
                update = { textureView -> textureView.keepScreenOn = playing },
                modifier = Modifier.aspectRatio(ratio),
            )
            // Пока не появился первый кадр — миниатюра, чтобы не мелькал чёрный экран.
            if (!firstFrame && placeholder != null) {
                Image(placeholder, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }

        if (failed) {
            Text(
                text = stringResource(R.string.viewer_video_failed),
                color = Color.White,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
            )
        }

        AnimatedVisibility(
            visible = chromeVisible && !failed,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            VideoControls(
                player = exoPlayer,
                showPause = wantsToPlay && !ended,
                ended = ended,
                onInteraction = { lastInteraction = SystemClock.uptimeMillis() },
            )
        }
    }
}

/** Нижняя панель: −10 с, пауза/пуск, +10 с, ползунок перемотки и время. */
@Composable
private fun VideoControls(
    player: Player,
    showPause: Boolean,
    ended: Boolean,
    onInteraction: () -> Unit,
) {
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            val d = player.duration
            duration = if (d == C.TIME_UNSET || d < 0) 0L else d
            delay(POSITION_TICK_MS)
        }
    }

    val shown = if (dragging) dragValue.toLong() else position
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(onClick = { onInteraction(); player.seekBack() }, size = 60.dp) {
                Seek10Icon(forward = false, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.width(36.dp))
            RoundIconButton(
                onClick = { onInteraction(); togglePlayPause(player, ended) },
                size = 72.dp,
                background = Color(0x33FFFFFF),
            ) {
                PlayPauseIcon(showPause = showPause, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.width(36.dp))
            RoundIconButton(onClick = { onInteraction(); player.seekForward() }, size = 60.dp) {
                Seek10Icon(forward = true, modifier = Modifier.size(44.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = formatClock(shown), color = Color.White, fontSize = 13.sp)
            Slider(
                value = shown.coerceIn(0L, duration).toFloat(),
                onValueChange = { value ->
                    onInteraction()
                    dragging = true
                    dragValue = value
                },
                onValueChangeFinished = {
                    player.seekTo(dragValue.toLong())
                    dragging = false
                },
                valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color(0x55FFFFFF),
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            )
            Text(text = formatClock(duration), color = Color.White, fontSize = 13.sp)
        }
    }
}

private fun togglePlayPause(player: Player, ended: Boolean) {
    when {
        ended -> {
            player.seekTo(0)
            player.play()
        }
        player.playWhenReady -> player.pause()
        else -> player.play()
    }
}

/** 75000 мс → "1:15", 3725000 мс → "1:02:05". */
private fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
