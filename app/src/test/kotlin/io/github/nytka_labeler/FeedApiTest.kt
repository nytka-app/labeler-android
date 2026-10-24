package io.github.nytka_labeler

import io.github.nytka_labeler.api.ApiResult
import io.github.nytka_labeler.api.ClipLine
import io.github.nytka_labeler.api.MIXED
import io.github.nytka_labeler.support.Harness
import io.github.nytka_labeler.support.item
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The feed's additions to the contract (docs/api.md): `task=mixed` and the optional item fields. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeedApiTest {
    private val peaks = List(100) { it / 100.0 }
    private val withExtras =
        item(
            1,
            "device",
            "source",
            "Привіт",
            peaks = peaks,
            speakerKey = "vg-17",
            before = listOf(ClipLine(0, "Перед", true, -2_100, -300)),
            after = listOf(ClipLine(2, "Після", false, 6_700, 8_000)),
        )
    private val plain = item(2, "quiet", "transcript", "Привіт")
    private val h = Harness("mixed", listOf(withExtras, plain)).also { it.server.serve(listOf(withExtras, plain)) }

    @After
    fun tearDown() = h.close()

    @Test
    fun `mixed is asked for as task=mixed and the optional fields arrive`() {
        val result = runBlocking { h.api.next(MIXED, 30) } as ApiResult.Ok

        assertTrue(h.server.requestPaths.any { it.endsWith("api/v1/labeling/next?task=mixed&limit=30") })
        val (first, second) = result.value.items
        assertEquals(peaks, first.peaks)
        assertEquals("vg-17", first.speakerKey)
        assertEquals(-2_100L, first.contextBefore.single().startMs)
        assertEquals(8_000L, first.contextAfter.single().endMs)
        assertNull("an older server sends none of them", second.peaks)
        assertNull(second.speakerKey)
        assertTrue(second.contextBefore.isEmpty() && second.contextAfter.isEmpty())
    }
}
