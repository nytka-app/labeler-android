package io.github.nytka_labeler

import io.github.nytka_labeler.api.ServerUrl
import io.github.nytka_labeler.api.UrlCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {
    @Test
    fun `https passes and gets a trailing slash`() {
        val ok = ServerUrl.check(" https://nytka.example.com/base?x=1#f ", privateNetwork = false) as UrlCheck.Ok
        assertEquals("https://nytka.example.com/base/", ok.base.toString())
    }

    @Test
    fun `plain http is refused unless the private network switch is on`() {
        assertTrue(ServerUrl.check("http://192.168.1.5:8080", privateNetwork = false) is UrlCheck.Invalid)
        assertTrue(ServerUrl.check("http://192.168.1.5:8080", privateNetwork = true) is UrlCheck.Ok)
    }

    @Test
    fun `empty and malformed addresses are refused`() {
        assertTrue(ServerUrl.check("  ", privateNetwork = false) is UrlCheck.Invalid)
        assertTrue(ServerUrl.check("nytka", privateNetwork = false) is UrlCheck.Invalid)
    }
}
