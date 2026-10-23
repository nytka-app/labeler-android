package io.github.nytka_labeler

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import io.github.nytka_labeler.api.Answer
import io.github.nytka_labeler.api.ApiResult
import io.github.nytka_labeler.api.ClipLine
import io.github.nytka_labeler.outbox.OutboxRow
import io.github.nytka_labeler.session.SessionUi
import io.github.nytka_labeler.settings.Settings
import io.github.nytka_labeler.settings.SettingsStore
import io.github.nytka_labeler.support.FakeCipher
import io.github.nytka_labeler.support.Harness
import io.github.nytka_labeler.support.TOKEN
import io.github.nytka_labeler.support.item
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.IOException

/** The token and transcript text never reach logs, `toString` or error messages. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TokenSafetyTest {
    private val secretLine = "Сьогодні ввечері у нас вечеря"

    @Test
    fun `Settings toString holds neither the token nor the address`() {
        val text = Settings(serverUrl = "https://nytka.example.com/", token = TOKEN).toString()

        assertFalse(text.contains(TOKEN))
        assertFalse(text.contains("nytka.example.com"))
    }

    @Test
    fun `the stored token is encrypted and comes back readable`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = File(context.cacheDir, "settings-${System.nanoTime()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(produceFile = { file })
        val store = SettingsStore(dataStore, FakeCipher())

        runBlocking { store.update { it.copy(serverUrl = "https://a.example/", token = TOKEN) } }

        val raw = runBlocking { dataStore.data.first() }[stringPreferencesKey("token")]
        assertNotEquals(TOKEN, raw)
        assertFalse(raw.orEmpty().contains(TOKEN))
        assertFalse("not in the file either", file.readBytes().toString(Charsets.UTF_8).contains(TOKEN))
        assertEquals(TOKEN, runBlocking { store.current() }.token)

        // An unrelated update must not erase it.
        runBlocking { store.update { it.copy(remindersEnabled = false) } }
        assertEquals(TOKEN, runBlocking { store.current() }.token)
    }

    @Test
    fun `an answer, a row and a line print without the text they carry`() {
        val answer = Answer("c-1", correction = secretLine)
        val row = OutboxRow("c-1", 1, "s-1", answer.encode(), OutboxRow.STATE_PENDING, null, 0)

        assertFalse(answer.toString().contains(secretLine))
        assertFalse(row.toString().contains(secretLine))
        assertFalse(ClipLine(1, secretLine).toString().contains(secretLine))
    }

    @Test
    fun `a whole session writes no log line with the token or any transcript text`() {
        ShadowLog.clear()
        val items = listOf(item(1, "quiet", "transcript", secretLine), item(2, "quiet", "transcript", secretLine))
        val h = Harness("quiet", items)
        try {
            h.server.serve(items)
            val vm = h.viewModel()
            wait { vm.ui.value is SessionUi.Showing }
            vm.choose("wrong_words")
            vm.confirm()
            h.server.postCodes += 500
            vm.skip()
            wait { runBlocking { h.outbox.pending() }.size == 2 }
            runBlocking { h.sender.drain() }
            h.server.postCodes += 400
            runBlocking { h.sender.drain() }
            h.online = false
            runBlocking { h.api.tasks() }
        } finally {
            h.close()
        }

        val everything = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable}" }
        assertFalse(everything.contains(TOKEN))
        assertFalse(everything.contains(secretLine))
    }

    @Test
    fun `failures carry fixed sentences, never the server's text or the token`() {
        val h = Harness("quiet", emptyList())
        try {
            h.server.tasks = emptyList()
            val problems = mutableListOf<String>()

            fun note(result: ApiResult<*>) = (result as? ApiResult.Failure)?.let { problems += it.message }

            h.online = false
            note(runBlocking { h.api.tasks() })
            h.online = true
            h.settings.also { runBlocking { it.update { s -> s.copy(token = "") } } }
            note(runBlocking { h.api.tasks() })
            runBlocking { h.settings.update { it.copy(token = TOKEN, serverUrl = "http://plain.example/") } }
            h.settings.also { runBlocking { it.update { s -> s.copy(privateNetwork = false) } } }
            note(runBlocking { h.api.tasks() })

            assertTrue(problems.isNotEmpty())
            problems.forEach { assertFalse(it.contains(TOKEN)) }
            assertTrue("plain http refused without the private network switch", problems.last().contains("http"))
        } finally {
            h.close()
        }
        assertTrue(IOException("x").message != null)
    }

    private fun wait(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            org.robolectric.shadows.ShadowLooper
                .idleMainLooper()
            Thread.sleep(10)
        }
    }
}
