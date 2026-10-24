package io.github.nytka_labeler.play

import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Plays the clip of the current card; a fake in tests. Used on the main thread only. */
interface ClipPlayer {
    val playing: StateFlow<Boolean>

    /** Total time the current card has been heard, for `listenedMs`; reset by [load]. */
    val listenedMs: Long

    /** Where the playhead is in the loaded clip, in milliseconds; the waveform and the captions follow it. */
    val positionMs: Long

    /** Length of the loaded clip, or 0 while it is not known. */
    val durationMs: Long

    /** Prepares [file], resets [listenedMs] when [keepCount] is false, and starts playing unless [autoplay] is off. */
    fun load(
        file: File,
        keepCount: Boolean = false,
        autoplay: Boolean = true,
    )

    /** Plays the loaded clip again from the start. */
    fun replay()

    fun toggle()

    fun pause()

    fun stop()

    fun release()
}

/** Whether headphones (wired, USB or Bluetooth) are the output now. */
interface HeadphoneMonitor {
    val connected: StateFlow<Boolean>
}
