package io.github.nytka_labeler.play

import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Plays the clip of the current card; a fake in tests. Used on the main thread only. */
interface ClipPlayer {
    val playing: StateFlow<Boolean>

    /** Total time the current card has been heard, for `listenedMs`; reset by [load]. */
    val listenedMs: Long

    /** Prepares [file], resets [listenedMs] when [keepCount] is false, and starts playing. */
    fun load(
        file: File,
        keepCount: Boolean = false,
    )

    fun toggle()

    fun pause()

    fun stop()

    fun release()
}

/** Whether headphones (wired, USB or Bluetooth) are the output now. */
interface HeadphoneMonitor {
    val connected: StateFlow<Boolean>
}
