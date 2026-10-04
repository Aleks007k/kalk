package com.pocketcalc.calculator.viewer

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.pocketcalc.calculator.vault.VaultEntry
import com.pocketcalc.calculator.vault.VaultRepository
import com.pocketcalc.calculator.vault.VaultSession

/** Видеоплеер для файла из тайника. Вызывающий обязан вызвать release(). */
object VaultPlayer {

    @OptIn(UnstableApi::class)
    fun create(
        context: Context,
        repository: VaultRepository,
        session: VaultSession,
        entry: VaultEntry,
    ): ExoPlayer {
        val dataSourceFactory = DataSource.Factory {
            VaultDataSource(entry.size) { repository.openSeekable(session, entry) }
        }
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            // Пауза, если звонок или другое приложение заиграло звук, и если отключили наушники.
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                // Адрес нужен плееру только как имя: данные идут из VaultDataSource.
                setMediaItem(MediaItem.fromUri(Uri.parse("vault://media/" + entry.id)))
                prepare()
                playWhenReady = true
            }
    }
}
