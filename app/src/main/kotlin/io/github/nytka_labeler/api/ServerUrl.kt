package io.github.nytka_labeler.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface UrlCheck {
    data class Ok(
        val base: HttpUrl,
    ) : UrlCheck

    data class Invalid(
        val reason: String,
    ) : UrlCheck
}

/**
 * The app enforces HTTPS itself: Android cannot allow cleartext per server at runtime, so the
 * network security config permits it and this check refuses it unless the private-network
 * switch is on. Same rule as the main Nytka app.
 */
object ServerUrl {
    fun check(
        text: String,
        privateNetwork: Boolean,
    ): UrlCheck {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return UrlCheck.Invalid("Enter your server's address.")
        val url =
            trimmed.toHttpUrlOrNull()
                ?: return UrlCheck.Invalid("Enter a full address that starts with https://")
        if (!url.isHttps && !privateNetwork) {
            return UrlCheck.Invalid("Plain http:// needs the private network switch.")
        }
        val path = if (url.encodedPath.endsWith("/")) url.encodedPath else url.encodedPath + "/"
        return UrlCheck.Ok(
            url
                .newBuilder()
                .encodedPath(path)
                .query(null)
                .fragment(null)
                .build(),
        )
    }
}
