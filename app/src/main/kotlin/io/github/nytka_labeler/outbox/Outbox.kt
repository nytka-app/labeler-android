package io.github.nytka_labeler.outbox

import io.github.nytka_labeler.api.Answer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Clock

/** The queue of answers; [OutboxSender] empties it. An answer leaves only when the server said 200. */
class Outbox(
    private val dao: OutboxDao,
    private val clock: Clock = Clock.systemUTC(),
) {
    val rows: Flow<List<OutboxRow>> = dao.observeAll()

    private val mutableSent = MutableStateFlow<Set<String>>(emptySet())

    /** Client ids the server answered 200 to since the app started: the only proof behind a "saved" tick. */
    val sent: StateFlow<Set<String>> = mutableSent.asStateFlow()

    suspend fun enqueue(
        answer: Answer,
        itemId: Long,
        sessionId: String,
        notBefore: Long = 0,
    ) {
        dao.deletePendingForItem(itemId)
        dao.insert(
            OutboxRow(
                clientId = answer.clientId,
                itemId = itemId,
                sessionId = sessionId,
                body = answer.encode(),
                state = OutboxRow.STATE_PENDING,
                error = null,
                createdAt = clock.millis(),
                notBefore = notBefore,
            ),
        )
    }

    /** Every answer that is not sent or parked, including those still inside their undo window. */
    suspend fun pending(): List<OutboxRow> = dao.pending()

    /** The pending answers to send now: their undo window has passed. */
    suspend fun due(): List<OutboxRow> = dao.due(clock.millis())

    /** Milliseconds until the next answer becomes due, or null when none waits for its window. */
    suspend fun millisToNextDue(): Long? = dao.nextDue(clock.millis())?.let { it - clock.millis() }

    /** Undo: true when the answer was still here and is gone now. */
    suspend fun cancel(clientId: String): Boolean = dao.cancel(clientId) > 0

    suspend fun all(): List<OutboxRow> = dao.all()

    /** The server said 200: the answer leaves the queue. */
    suspend fun complete(clientId: String) {
        dao.delete(clientId)
        mutableSent.update { it + clientId }
    }

    suspend fun park(
        clientId: String,
        reason: String,
    ) = dao.park(clientId, reason)

    suspend fun pendingCount(sessionId: String): Int = dao.pendingCount(sessionId)

    suspend fun dismissParked(clientId: String) = dao.dismissParked(clientId)
}
