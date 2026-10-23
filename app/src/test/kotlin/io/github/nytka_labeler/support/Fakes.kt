package io.github.nytka_labeler.support

import io.github.nytka_labeler.api.ClipLine
import io.github.nytka_labeler.api.LabelItem
import io.github.nytka_labeler.api.Prefill
import io.github.nytka_labeler.outbox.SendScheduler
import io.github.nytka_labeler.play.ClipPlayer
import io.github.nytka_labeler.play.HeadphoneMonitor
import io.github.nytka_labeler.session.SessionSource
import io.github.nytka_labeler.session.StartResult
import io.github.nytka_labeler.settings.Settings
import io.github.nytka_labeler.settings.SettingsSource
import io.github.nytka_labeler.settings.TokenCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

const val TOKEN = "tok-SECRET-9f3a"

class FakeCipher : TokenCipher {
    override fun encrypt(plain: String) = "enc:" + plain.reversed()

    override fun decrypt(stored: String) = stored.removePrefix("enc:").reversed().takeIf { stored.startsWith("enc:") }
}

class FakeSettings(
    initial: Settings = Settings(),
) : SettingsSource {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<Settings> = state

    override suspend fun current() = state.value

    override suspend fun update(transform: (Settings) -> Settings) {
        state.value = transform(state.value)
    }
}

class FakeClipPlayer : ClipPlayer {
    private val mutablePlaying = MutableStateFlow(false)
    override val playing: StateFlow<Boolean> = mutablePlaying.asStateFlow()
    override var listenedMs: Long = LISTENED
    val loaded = mutableListOf<File>()
    var stopped = 0

    override fun load(
        file: File,
        keepCount: Boolean,
    ) {
        check(file.exists()) { "the player was given a clip that is not on disk" }
        loaded += file
        mutablePlaying.value = true
    }

    override fun toggle() {
        mutablePlaying.value = !mutablePlaying.value
    }

    override fun pause() {
        mutablePlaying.value = false
    }

    override fun stop() {
        stopped++
        mutablePlaying.value = false
    }

    override fun release() = stop()

    companion object {
        const val LISTENED = 5200L
    }
}

class RecordingScheduler : SendScheduler {
    var calls = 0

    override fun schedule() {
        calls++
    }
}

class FakeHeadphones(
    connected: Boolean,
) : HeadphoneMonitor {
    override val connected: StateFlow<Boolean> = MutableStateFlow(connected)
}

class FakeSessionSource(
    private val result: StartResult,
) : SessionSource {
    override suspend fun start(
        task: String,
        limit: Int,
        onProgress: (done: Int, total: Int) -> Unit,
    ) = result
}

fun item(
    id: Long,
    task: String,
    ask: String,
    vararg text: String,
    language: String? = null,
) = LabelItem(
    id = id,
    task = task,
    ask = ask,
    reason = "uncertain",
    conversationId = "c-1",
    startedAt = "2026-10-20T09:15:02Z",
    durationMs = 6400,
    lines = text.mapIndexed { i, t -> ClipLine(id * 10 + i, t, false, 0, 6400) },
    prefill = language?.let { Prefill(it) },
)

/** The task's `ask` as docs/api.md lists it. */
fun askOf(task: String) =
    when (task) {
        "device", "voice" -> "source"
        "overlap" -> "overlap"
        else -> "transcript"
    }
