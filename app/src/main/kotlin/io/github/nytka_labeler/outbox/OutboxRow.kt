package io.github.nytka_labeler.outbox

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One answer waiting for the server. [body] is the JSON of `POST /{itemId}` exactly as it will be sent, so a retry
 * repeats the same `clientId`. It can hold a correction of a transcript line: it stays in this database and nowhere
 * else (not in logs, not in errors).
 */
@Entity(tableName = "outbox")
data class OutboxRow(
    @PrimaryKey val clientId: String,
    val itemId: Long,
    val sessionId: String,
    val body: String,
    val state: String,
    /** Why a [STATE_PARKED] answer was refused: the server's field message. */
    val error: String?,
    val createdAt: Long,
) {
    /** [body] can hold transcript text, so it is not printed. */
    override fun toString() = "OutboxRow(clientId=$clientId, itemId=$itemId, state=$state)"

    companion object {
        const val STATE_PENDING = "pending"
        const val STATE_PARKED = "parked"
    }
}
