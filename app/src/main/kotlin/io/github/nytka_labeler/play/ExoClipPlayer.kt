package io.github.nytka_labeler.play

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * ExoPlayer over local clip files. It takes audio focus (and gives it back), pauses on the headphones being
 * unplugged (`setHandleAudioBecomingNoisy`), and sits behind a [MediaSession], so the play/pause button of a headset
 * toggles the current clip. Nothing about a clip is logged. Used on the main thread only.
 */
class ExoClipPlayer(
    private val context: Context,
) : ClipPlayer {
    private val mutablePlaying = MutableStateFlow(false)
    override val playing: StateFlow<Boolean> = mutablePlaying.asStateFlow()

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var heard = 0L
    private var playingSince = 0L

    override val listenedMs: Long
        get() = heard + if (playingSince > 0) SystemClock.elapsedRealtime() - playingSince else 0

    override val positionMs: Long
        get() = player?.currentPosition ?: 0

    override val durationMs: Long
        get() = player?.duration?.takeIf { it != C.TIME_UNSET } ?: 0

    override fun replay() {
        val exo = player ?: return
        exo.seekTo(0)
        exo.play()
    }

    override fun load(
        file: File,
        keepCount: Boolean,
        autoplay: Boolean,
    ) {
        val exo = ensure()
        if (!keepCount) {
            heard = 0
            playingSince = 0
        }
        exo.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
        exo.prepare()
        if (autoplay) exo.play() else exo.pause()
    }

    override fun toggle() {
        val exo = player ?: return
        if (exo.playbackState == Player.STATE_ENDED) {
            exo.seekTo(0)
            exo.play()
        } else if (exo.isPlaying) {
            exo.pause()
        } else {
            exo.play()
        }
    }

    override fun pause() {
        player?.pause()
    }

    override fun stop() {
        player?.stop()
    }

    private fun ensure(): ExoPlayer =
        player ?: ExoPlayer
            .Builder(context)
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true,
            ).setHandleAudioBecomingNoisy(true)
            .build()
            .also { exo ->
                exo.addListener(
                    object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            if (isPlaying) {
                                playingSince = SystemClock.elapsedRealtime()
                            } else if (playingSince > 0) {
                                heard += SystemClock.elapsedRealtime() - playingSince
                                playingSince = 0
                            }
                            mutablePlaying.value = isPlaying
                        }
                    },
                )
                session = MediaSession.Builder(context, exo).build()
                player = exo
            }

    override fun release() {
        session?.release()
        session = null
        player?.release()
        player = null
        mutablePlaying.value = false
    }
}
