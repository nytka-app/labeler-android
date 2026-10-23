package io.github.nytka_labeler.outbox

import io.github.nytka_labeler.api.AnswerResult
import io.github.nytka_labeler.api.LabelingClient
import io.github.nytka_labeler.settings.SettingsSource

enum class DrainResult {
    /** Nothing is left to send. */
    Done,

    /** The server or the network failed; try again later with backoff. */
    Retry,

    /** The token was refused; nothing more is sent until the owner saves a new one. */
    TokenRefused,
}

/**
 * Sends the pending answers, oldest first. 200 removes one; 400 or 404 parks it with the reason; 401 or 403 stops
 * everything and asks for a token; anything else stops the run for a retry later (order is kept, and the same
 * `clientId` goes out again, which the server treats as the same answer).
 */
class OutboxSender(
    private val outbox: Outbox,
    private val client: LabelingClient,
    private val settings: SettingsSource,
    private val onChanged: suspend () -> Unit = {},
) {
    suspend fun drain(): DrainResult {
        if (settings.current().tokenRefused) return DrainResult.TokenRefused
        var result = DrainResult.Done
        // Answers given while this runs are picked up by the next pass of the loop.
        while (result == DrainResult.Done) {
            val rows = outbox.pending()
            if (rows.isEmpty()) break
            for (row in rows) {
                val step = send(row)
                if (step != null) {
                    result = step
                    break
                }
            }
        }
        onChanged()
        return result
    }

    /** Null to go on with the next answer. */
    private suspend fun send(row: OutboxRow): DrainResult? =
        when (val answer = client.answer(row.itemId, row.body)) {
            is AnswerResult.Sent -> {
                outbox.complete(row.clientId)
                null
            }
            is AnswerResult.Rejected -> {
                outbox.park(row.clientId, describe(answer.field, answer.error))
                null
            }
            AnswerResult.ItemGone -> {
                outbox.park(row.clientId, "This clip no longer exists on the server.")
                null
            }
            AnswerResult.TokenRefused -> {
                settings.update { it.copy(tokenRefused = true) }
                DrainResult.TokenRefused
            }
            is AnswerResult.Retry -> DrainResult.Retry
        }

    private fun describe(
        field: String,
        error: String,
    ) = if (field.isEmpty()) error else "$field: $error"
}
