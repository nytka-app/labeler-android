package io.github.nytka_labeler.support

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.nytka_labeler.api.LabelItem
import io.github.nytka_labeler.api.LabelingApi
import io.github.nytka_labeler.outbox.LabelDatabase
import io.github.nytka_labeler.outbox.Outbox
import io.github.nytka_labeler.outbox.OutboxSender
import io.github.nytka_labeler.session.Card
import io.github.nytka_labeler.session.ClipStore
import io.github.nytka_labeler.session.LoadedSession
import io.github.nytka_labeler.session.SessionConfig
import io.github.nytka_labeler.session.SessionViewModel
import io.github.nytka_labeler.session.StartResult
import io.github.nytka_labeler.settings.Settings
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Everything a session needs, wired as in the app but with hand-written fakes at the edges: a real Room database
 * (in memory), the real [LabelingApi] and [OutboxSender] against [FakeLabelingServer], a fake player.
 */
class Harness(
    val task: String,
    val items: List<LabelItem>,
    /** The goal is far above a test's clips unless the test is about the goal. */
    val config: SessionConfig = SessionConfig(goal = 1_000),
    headphones: Boolean = true,
    val dir: File = Files.createTempDirectory("labeler").toFile(),
    val context: Context = ApplicationProvider.getApplicationContext<Application>(),
) {
    val server = FakeLabelingServer().also { it.start() }
    val settings = FakeSettings(Settings(serverUrl = server.url, token = TOKEN, privateNetwork = true))
    val db: LabelDatabase =
        Room
            .inMemoryDatabaseBuilder(context, LabelDatabase::class.java)
            .setQueryExecutor { it.run() }
            .allowMainThreadQueries()
            .build()
    val clock = TestClock()
    val outbox = Outbox(db.outbox(), clock)
    val headphones = FakeHeadphones(headphones)
    val clips = ClipStore(File(dir, "clips"))
    val player = FakeClipPlayer()
    val scheduler = RecordingScheduler()

    /** Flip to false for no network at all: every call fails with an IOException before it leaves the phone. */
    @Volatile var online = true

    val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor(
                Interceptor { chain ->
                    if (online) chain.proceed(chain.request()) else throw IOException("offline")
                },
            ).build()
    val api = LabelingApi(client) { settings.current() }
    val sender = OutboxSender(outbox, api, settings) { clips.sweep { outbox.pendingCount(it) } }

    /** The session as the repository would hand it over: every clip already on disk. */
    fun loaded(sessionId: String = "s-1"): LoadedSession {
        val tone =
            checkNotNull(
                checkNotNull(javaClass.classLoader).getResourceAsStream("tone.ogg"),
            ).use { it.readBytes() }
        val cards =
            items.map {
                val plain = clips.file(sessionId, it.id, 0).also { f -> f.writeBytes(tone) }
                val wide = clips.file(sessionId, it.id, 4).also { f -> f.writeBytes(tone) }
                Card(it, plain, wide)
            }
        return LoadedSession(sessionId, task, cards)
    }

    fun viewModel(session: LoadedSession = loaded()): SessionViewModel =
        SessionViewModel(
            SavedStateHandle(mapOf("task" to task)),
            FakeSessionSource(StartResult.Ready(session)),
            outbox,
            scheduler,
            clips,
            player,
            settings,
            clock,
            api,
            this.headphones,
            config,
        )

    /** The undo window of every answer given so far is over: the outbox may send them. */
    fun passUndoWindow() = clock.advance(config.undoMs + 1)

    fun close() {
        db.close()
        server.stop()
        dir.deleteRecursively()
    }
}
