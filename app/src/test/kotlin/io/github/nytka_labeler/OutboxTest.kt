package io.github.nytka_labeler

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.nytka_labeler.api.Answer
import io.github.nytka_labeler.api.LabelingApi
import io.github.nytka_labeler.outbox.DrainResult
import io.github.nytka_labeler.outbox.LabelDatabase
import io.github.nytka_labeler.outbox.Outbox
import io.github.nytka_labeler.outbox.OutboxRow
import io.github.nytka_labeler.outbox.OutboxSender
import io.github.nytka_labeler.settings.Settings
import io.github.nytka_labeler.support.FakeLabelingServer
import io.github.nytka_labeler.support.FakeSettings
import io.github.nytka_labeler.support.TOKEN
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OutboxTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val server = FakeLabelingServer()
    private val settings by lazy {
        FakeSettings(
            Settings(serverUrl = server.url, token = TOKEN, privateNetwork = true),
        )
    }
    private var dropResponses = false
    private val client =
        OkHttpClient
            .Builder()
            // The request reaches the server, the answer is lost on the way back: the phone cannot tell.
            .addInterceptor(
                Interceptor { chain ->
                    val response = chain.proceed(chain.request())
                    if (dropResponses) {
                        response.close()
                        throw IOException("connection reset")
                    }
                    response
                },
            ).build()
    private val openDatabases = mutableListOf<LabelDatabase>()

    @Before
    fun start() = server.start()

    @After
    fun stop() {
        openDatabases.forEach { it.close() }
        server.stop()
        context.deleteDatabase("outbox-test.db")
    }

    /** The same file each time: opening it again is what a new process does. */
    private fun open(): LabelDatabase =
        Room
            .databaseBuilder(context, LabelDatabase::class.java, "outbox-test.db")
            .setQueryExecutor { it.run() }
            .allowMainThreadQueries()
            .build()
            .also { openDatabases += it }

    private fun sender(outbox: Outbox) = OutboxSender(outbox, LabelingApi(client) { settings.current() }, settings)

    private fun enqueue(
        outbox: Outbox,
        itemId: Long,
        clientId: String = "00000000-0000-0000-0000-00000000000$itemId",
        skipped: Boolean = false,
    ) = runBlocking {
        outbox.enqueue(
            Answer(clientId, source = if (skipped) null else listOf("me"), skipped = skipped, listenedMs = 100),
            itemId,
            "s-1",
        )
    }

    @Test
    fun `answers survive process death and are sent from the reopened database`() {
        val first = open()
        enqueue(Outbox(first.outbox()), 1)
        enqueue(Outbox(first.outbox()), 2)
        first.close()

        val second = Outbox(open().outbox())
        assertEquals(listOf(1L, 2L), runBlocking { second.pending() }.map { it.itemId })

        assertEquals(DrainResult.Done, runBlocking { sender(second).drain() })

        assertEquals(listOf(1L, 2L), server.posts.map { it.itemId })
        assertTrue(runBlocking { second.pending() }.isEmpty())
    }

    @Test
    fun `a 500 keeps the answer and the next run sends it`() {
        val outbox = Outbox(open().outbox())
        enqueue(outbox, 1)
        server.postCodes += 500

        assertEquals(DrainResult.Retry, runBlocking { sender(outbox).drain() })
        assertEquals("kept after a 500", 1, runBlocking { outbox.pending() }.size)

        assertEquals(DrainResult.Done, runBlocking { sender(outbox).drain() })
        assertEquals(0, runBlocking { outbox.pending() }.size)
        assertEquals("the retry sent the same body", server.posts[0].body, server.posts[1].body)
    }

    @Test
    fun `a lost response is retried with the same clientId and makes one label`() {
        val outbox = Outbox(open().outbox())
        enqueue(outbox, 7)
        dropResponses = true

        assertEquals(DrainResult.Retry, runBlocking { sender(outbox).drain() })
        assertEquals("still queued: no 200 was seen", 1, runBlocking { outbox.pending() }.size)

        dropResponses = false
        assertEquals(DrainResult.Done, runBlocking { sender(outbox).drain() })

        assertEquals(2, server.posts.size)
        assertEquals(server.posts[0].body, server.posts[1].body)
        assertEquals("the server made one label for two posts", 1, server.labelCount)
    }

    @Test
    fun `a 400 parks the answer with the field error and the rest still go`() {
        val outbox = Outbox(open().outbox())
        enqueue(outbox, 1)
        enqueue(outbox, 2)
        server.postCodes += 400

        assertEquals(DrainResult.Done, runBlocking { sender(outbox).drain() })

        val all = runBlocking { outbox.pending() }
        assertTrue("nothing pending", all.isEmpty())
        val rows = runBlocking { outbox.all() }
        val parked = rows.single()
        assertEquals(OutboxRow.STATE_PARKED, parked.state)
        assertEquals(1L, parked.itemId)
        assertEquals("source: unknown value", parked.error)
        assertEquals(2, server.posts.size)
    }

    @Test
    fun `401 stops the run, keeps every answer and asks for a token`() {
        val outbox = Outbox(open().outbox())
        enqueue(outbox, 1)
        enqueue(outbox, 2)
        server.postCodes += 401

        assertEquals(DrainResult.TokenRefused, runBlocking { sender(outbox).drain() })

        assertEquals(1, server.posts.size)
        assertEquals(2, runBlocking { outbox.pending() }.size)
        assertTrue(runBlocking { settings.current() }.tokenRefused)

        // Stopped for good until a new token is saved: no request at all.
        assertEquals(DrainResult.TokenRefused, runBlocking { sender(outbox).drain() })
        assertEquals(1, server.posts.size)

        runBlocking { settings.update { it.copy(token = "new-token", tokenRefused = false) } }
        assertEquals(DrainResult.Done, runBlocking { sender(outbox).drain() })
        assertEquals(3, server.posts.size)
        assertEquals("Bearer new-token", server.posts.last().authorization)
    }

    @Test
    fun `403 stops the run the same way`() {
        val outbox = Outbox(open().outbox())
        enqueue(outbox, 1)
        server.postCodes += 403

        assertEquals(DrainResult.TokenRefused, runBlocking { sender(outbox).drain() })
        assertEquals(1, runBlocking { outbox.pending() }.size)
    }

    @Test
    fun `a 404 parks the answer because the clip is gone`() {
        val outbox = Outbox(open().outbox())
        enqueue(outbox, 1)
        server.postCodes += 404

        assertEquals(DrainResult.Done, runBlocking { sender(outbox).drain() })
        assertEquals(0, runBlocking { outbox.pending() }.size)
        val parked = runBlocking { outbox.all() }.single()
        assertEquals(OutboxRow.STATE_PARKED, parked.state)
    }

    @Test
    fun `a new answer to an item replaces its unsent one`() {
        val outbox = Outbox(open().outbox())
        enqueue(outbox, 1, clientId = "11111111-1111-1111-1111-111111111111")
        enqueue(outbox, 1, clientId = "22222222-2222-2222-2222-222222222222", skipped = true)

        runBlocking { sender(outbox).drain() }

        assertEquals(1, server.posts.size)
        assertTrue(
            server.posts
                .single()
                .body
                .contains("22222222"),
        )
        assertFalse(
            server.posts
                .single()
                .body
                .contains("11111111"),
        )
    }

    @Test
    fun `answers go out oldest first`() {
        val outbox = Outbox(open().outbox())
        listOf(5L, 3L, 9L, 1L).forEach { enqueue(outbox, it) }

        runBlocking { sender(outbox).drain() }

        assertEquals(listOf(5L, 3L, 9L, 1L), server.posts.map { it.itemId })
    }
}
