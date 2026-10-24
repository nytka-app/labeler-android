package io.github.nytka_labeler.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The body of `POST /{id}`. Null fields are left out of the JSON: every axis field is optional, and a skip stores
 * nothing but itself.
 */
@Serializable
data class Answer(
    val clientId: String,
    val source: List<String>? = null,
    val deviceType: String? = null,
    val content: String? = null,
    val transcript: String? = null,
    val flags: List<String>? = null,
    val language: String? = null,
    val correction: String? = null,
    val overlap: String? = null,
    val linesDiffer: Boolean = false,
    val skipped: Boolean = false,
    val listenedMs: Long = 0,
    val answeredWith: String? = null,
) {
    /** A correction is transcript text: it stays out of logs and crash reports. */
    override fun toString() = "Answer(clientId=$clientId, skipped=$skipped)"

    fun encode(): String = wire.encodeToString(serializer(), this)

    companion object {
        /** The server's limits (`POST /{id}`): a longer value is a 400 that would park the answer. */
        const val MAX_LISTENED_MS = 600_000L
        const val MAX_CORRECTION = 2000

        private val wire =
            Json {
                encodeDefaults = true
                explicitNulls = false
            }

        fun decode(text: String): Answer = wire.decodeFromString(serializer(), text)
    }
}

@Serializable
data class AnswerReceipt(
    val labelId: Long = 0,
    val superseded: Long? = null,
)
