package io.github.nytka_labeler.support

import io.github.nytka_labeler.api.ClipLine
import io.github.nytka_labeler.api.Guess
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

const val TOKEN = "tok-SECRET-9f3a" // gitleaks:allow (synthetic test token)

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
    override var positionMs: Long = 0
    override var durationMs: Long = 0
    val loaded = mutableListOf<File>()
    var stopped = 0
    var replays = 0

    override fun load(
        file: File,
        keepCount: Boolean,
        autoplay: Boolean,
    ) {
        check(file.exists()) { "the player was given a clip that is not on disk" }
        loaded += file
        positionMs = 0
        mutablePlaying.value = autoplay
    }

    override fun replay() {
        replays++
        positionMs = 0
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

/** A clock the test moves by hand: the undo window and the outbox's not-before time read it. */
class TestClock(
    var now: Long = Instant.parse("2026-10-24T10:00:00Z").toEpochMilli(),
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = Instant.ofEpochMilli(now)

    fun advance(ms: Long) {
        now += ms
    }
}

fun item(
    id: Long,
    task: String,
    ask: String,
    vararg text: String,
    language: String? = null,
    guess: Guess? = null,
    peaks: List<Double>? = null,
    speakerKey: String? = null,
    before: List<ClipLine> = emptyList(),
    after: List<ClipLine> = emptyList(),
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
    guess = guess,
    peaks = peaks,
    speakerKey = speakerKey,
    contextBefore = before,
    contextAfter = after,
)

/** The task's `ask` as docs/api.md lists it. */
fun askOf(task: String) =
    when (task) {
        "device", "voice" -> "source"
        "overlap" -> "overlap"
        else -> "transcript"
    }
