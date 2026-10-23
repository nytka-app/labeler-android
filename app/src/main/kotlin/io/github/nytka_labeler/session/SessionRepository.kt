package io.github.nytka_labeler.session

import io.github.nytka_labeler.api.ApiResult
import io.github.nytka_labeler.api.FailureKind
import io.github.nytka_labeler.api.LabelItem
import io.github.nytka_labeler.api.LabelingClient
import java.io.File

/**
 * One clip of a session: the item, its clip and the clip with 4 s of context either side (null when the server
 * has none).
 */
data class Card(
    val item: LabelItem,
    val clip: File,
    val withContext: File?,
)

data class LoadedSession(
    val sessionId: String,
    val task: String,
    val cards: List<Card>,
)

sealed interface StartResult {
    data class Ready(
        val session: LoadedSession,
    ) : StartResult

    /** The task has nothing open. */
    data object Empty : StartResult

    data class Failed(
        val kind: FailureKind,
        val message: String,
    ) : StartResult
}

/** Where a session comes from; the screens take this, a fake serves them in tests. */
interface SessionSource {
    suspend fun start(
        task: String,
        limit: Int,
        onProgress: (done: Int, total: Int) -> Unit,
    ): StartResult
}

/**
 * Leases a session with `GET /next` and downloads every clip (context 0 and 4) into [ClipStore], so the session
 * works with no network. A clip already on disk is not fetched again, so a second call to `/next` (the server
 * returns the same unanswered items) resumes where a dropped download stopped. An item whose audio is gone (404) is
 * left out.
 */
class SessionRepository(
    private val client: LabelingClient,
    private val clips: ClipStore,
) : SessionSource {
    override suspend fun start(
        task: String,
        limit: Int,
        onProgress: (done: Int, total: Int) -> Unit,
    ): StartResult {
        val next =
            when (val result = client.next(task, limit)) {
                is ApiResult.Failure -> return StartResult.Failed(result.kind, result.message)
                is ApiResult.Ok -> result.value
            }
        val total = next.items.size
        onProgress(0, total)
        val cards = mutableListOf<Card>()
        next.items.forEachIndexed { index, item ->
            val card =
                when (val got = fetch(next.sessionId, item)) {
                    is Fetched.Failed -> return StartResult.Failed(got.kind, got.message)
                    Fetched.Gone -> null
                    is Fetched.Got -> got.card
                }
            card?.let(cards::add)
            onProgress(index + 1, total)
        }
        if (cards.isEmpty()) {
            clips.markEnded(next.sessionId)
            return StartResult.Empty
        }
        return StartResult.Ready(LoadedSession(next.sessionId, task, cards))
    }

    private suspend fun fetch(
        sessionId: String,
        item: LabelItem,
    ): Fetched {
        val plain = clips.file(sessionId, item.id, 0)
        download(item.id, 0, plain)?.let { failure ->
            return if (failure.kind ==
                FailureKind.NotFound
            ) {
                Fetched.Gone
            } else {
                Fetched.Failed(failure.kind, failure.message)
            }
        }
        val wide = clips.file(sessionId, item.id, CONTEXT_SECONDS)
        val failure = download(item.id, CONTEXT_SECONDS, wide)
        return when {
            failure == null -> Fetched.Got(Card(item, plain, wide))
            failure.kind == FailureKind.NotFound -> Fetched.Got(Card(item, plain, null))
            else -> Fetched.Failed(failure.kind, failure.message)
        }
    }

    private suspend fun download(
        id: Long,
        context: Int,
        target: File,
    ): ApiResult.Failure? {
        if (target.length() > 0) return null
        return client.clip(id, context, target) as? ApiResult.Failure
    }

    private sealed interface Fetched {
        data class Got(
            val card: Card,
        ) : Fetched

        data object Gone : Fetched

        data class Failed(
            val kind: FailureKind,
            val message: String,
        ) : Fetched
    }

    private companion object {
        const val CONTEXT_SECONDS = 4
    }
}
