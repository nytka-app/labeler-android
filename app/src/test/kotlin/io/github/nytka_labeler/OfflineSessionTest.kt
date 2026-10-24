package io.github.nytka_labeler

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.nytka_labeler.api.FailureKind
import io.github.nytka_labeler.outbox.SendWorker
import io.github.nytka_labeler.outbox.WorkManagerSendScheduler
import io.github.nytka_labeler.session.SessionRepository
import io.github.nytka_labeler.session.SessionUi
import io.github.nytka_labeler.session.StartResult
import io.github.nytka_labeler.support.Harness
import io.github.nytka_labeler.support.item
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A session works with no network: the clips are cached first, the answers queue, and a worker sends them when the
 * network is back. Clip files go once the session has ended and nothing is waiting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfflineSessionTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val items = (1L..3L).map { item(it, "device", "source", "Рядок $it") }
    private val h = Harness("device", items).also { it.server.serve(items) }

    @After
    fun tearDown() = h.close()

    private fun repository() = SessionRepository(h.api, h.clips)

    private fun start(): StartResult.Ready =
        runBlocking { repository().start("device", 30) { _, _ -> } } as StartResult.Ready

    @Test
    fun `start downloads both clips of every item with the token in the header only`() {
        val session = start().session

        assertEquals(3, session.cards.size)
        session.cards.forEach { card ->
            assertTrue(card.clip.length() > 0)
            assertTrue(card.withContext!!.length() > 0)
        }
        assertEquals("1 next + 3 x 2 clips", 7, h.server.requestPaths.size)
        assertTrue(h.server.requestPaths.any { it.endsWith("/1/clip?context=0") })
        assertTrue(h.server.requestPaths.any { it.endsWith("/1/clip?context=4") })
        assertTrue("no token in any URL", h.server.requestPaths.none { it.contains("tok-") })
    }

    @Test
    fun `answers given with no network wait in the outbox and the session never touches the network`() {
        val session = start().session
        h.online = false
        val requestsBefore = h.server.requestPaths.size
        val vm = h.viewModel(session)

        // The player is handed a cached file, not a URL.
        waitForCard(vm)
        assertTrue(h.player.loaded.all { it.exists() })
        vm.choose("me")
        vm.choose("live")
        vm.skip()
        waitUntil { runBlocking { h.outbox.pending() }.size == 3 }

        assertEquals(requestsBefore, h.server.requestPaths.size)
        h.passUndoWindow()
        assertEquals(
            "offline: nothing could be sent, and the answers stay",
            io.github.nytka_labeler.outbox.DrainResult.Retry,
            runBlocking { h.sender.drain() },
        )
        assertEquals(3, runBlocking { h.outbox.pending() }.size)
        assertTrue("clips stay while answers wait", session.cards.all { it.clip.exists() })
    }

    @Test
    fun `a worker sends the queue when the network constraint is met and the clips are deleted after`() {
        val session = start().session
        h.online = false
        val vm = h.viewModel(session)
        waitForCard(vm)
        vm.choose("me")
        vm.choose("live")
        vm.choose("none")
        waitUntil { runBlocking { h.outbox.pending() }.size == 3 }
        waitUntil { vm.ui.value is SessionUi.Ended }
        h.passUndoWindow()

        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: android.content.Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker? =
                    if (workerClassName ==
                        SendWorker::class.java.name
                    ) {
                        SendWorker(appContext, workerParameters, h.sender)
                    } else {
                        null
                    }
            }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration
                .Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(factory)
                .build(),
        )
        val work = WorkManager.getInstance(context)
        WorkManagerSendScheduler(context).schedule()
        val info = work.getWorkInfosForUniqueWork(WorkManagerSendScheduler.UNIQUE_NAME).get().single()

        // No network yet: the constraint holds the worker back.
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(0, h.server.posts.size)

        h.online = true
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(info.id)

        // The worker runs on a coroutine of its own; give it a moment.
        waitUntil { work.getWorkInfoById(info.id).get()!!.state == WorkInfo.State.SUCCEEDED }
        assertEquals(listOf(1L, 2L, 3L), h.server.posts.map { it.itemId })
        assertTrue(runBlocking { h.outbox.pending() }.isEmpty())
        assertFalse("clips go once the session ended and its answers are all sent", h.clips.exists("s-1"))
    }

    @Test
    fun `the worker asks for a retry on a server error and succeeds once it answers 200`() {
        val session = start().session
        val vm = h.viewModel(session)
        waitForCard(vm)
        vm.choose("me")
        waitUntil { runBlocking { h.outbox.pending() }.size == 1 }
        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: android.content.Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker? = SendWorker(appContext, workerParameters, h.sender)
            }
        h.passUndoWindow()
        h.server.postCodes += 500

        val worker = TestListenableWorkerBuilder<SendWorker>(context).setWorkerFactory(factory).build()

        assertEquals(ListenableWorker.Result.retry(), runBlocking { worker.doWork() })
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
        assertTrue(runBlocking { h.outbox.pending() }.isEmpty())
    }

    @Test
    fun `clips stay while an answer waits and go with the last 200`() {
        val session = start().session
        h.online = false
        val vm = h.viewModel(session)
        waitForCard(vm)
        listOf("me", "live", "none").forEach(vm::choose)
        waitUntil { runBlocking { h.outbox.pending() }.size == 3 }
        vm.leave()
        assertTrue(h.clips.exists("s-1"))

        h.online = true
        h.passUndoWindow()
        runBlocking { h.sender.drain() }

        assertFalse(h.clips.exists("s-1"))
    }

    @Test
    fun `an item whose audio is gone is left out and a session with none left is empty`() {
        h.server.missingClips = setOf("2/clip?context=0")
        val ready = start().session
        assertEquals(listOf(1L, 3L), ready.cards.map { it.item.id })

        h.server.missingClips = setOf("1/clip?context=0", "2/clip?context=0", "3/clip?context=0")
        h.clips.deleteAll()
        val result = runBlocking { repository().start("device", 30) { _, _ -> } }
        assertEquals(StartResult.Empty, result)
    }

    @Test
    fun `a missing context clip only hides the context button`() {
        h.server.missingClips = setOf("1/clip?context=4")
        val card = start().session.cards.first()
        assertNull(card.withContext)
        assertNotNull(card.clip)
    }

    @Test
    fun `a failed start says why and a refused token is told apart`() {
        h.server.next =
            io.github.nytka_labeler.api
                .NextResponse("s-1", "", emptyList())
        assertEquals(StartResult.Empty, runBlocking { repository().start("device", 30) { _, _ -> } })

        h.online = false
        val failed = runBlocking { repository().start("device", 30) { _, _ -> } } as StartResult.Failed
        assertEquals(FailureKind.Network, failed.kind)
    }

    private fun waitForCard(vm: io.github.nytka_labeler.session.SessionViewModel) =
        waitUntil { vm.ui.value is SessionUi.Showing }

    private fun waitUntil(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            org.robolectric.shadows.ShadowLooper
                .idleMainLooper()
            Thread.sleep(10)
        }
    }
}
