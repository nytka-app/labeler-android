package io.github.nytka_labeler.api

import java.io.File

enum class FailureKind {
    NotConfigured,
    Unauthorized,
    Forbidden,
    NotFound,
    Unsupported,
    Server,
    Network,
    Timeout,
}

sealed interface ApiResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : ApiResult<T>

    /** [message] is a fixed sentence or the system's own message, never the server's response text. */
    data class Failure(
        val kind: FailureKind,
        val message: String,
    ) : ApiResult<Nothing>
}

/** What the server said to a posted answer. Only [Sent] lets the outbox drop the answer. */
sealed interface AnswerResult {
    data class Sent(
        val labelId: Long,
    ) : AnswerResult

    /** 400: retrying cannot help; [field] and [error] come from the server's field message. */
    data class Rejected(
        val field: String,
        val error: String,
    ) : AnswerResult

    /** 404: the item is gone. */
    data object ItemGone : AnswerResult

    /** 401 or 403: the token is wrong or lacks the `labeling` scope. */
    data object TokenRefused : AnswerResult

    data class Retry(
        val reason: String,
    ) : AnswerResult
}

/** The labeling routes of `docs/api.md`, apart so the screens and workers are tested without HTTP. */
interface LabelingClient {
    suspend fun tasks(): ApiResult<List<TaskInfo>>

    suspend fun next(
        task: String,
        limit: Int,
    ): ApiResult<NextResponse>

    /** Saves `GET /{id}/clip?context=` to [target]; the file exists only when the answer is [ApiResult.Ok]. */
    suspend fun clip(
        id: Long,
        context: Int,
        target: File,
    ): ApiResult<Unit>

    /** `GET /voice/pending`: `me` lines waiting for the owner to confirm. */
    suspend fun voicePending(): ApiResult<List<PendingVoice>>

    /** `POST /voice/confirm`: feeds the lines to the voiceprint; returns how many were learned. */
    suspend fun voiceConfirm(labelIds: List<Long>): ApiResult<Int>

    /** Posts an [Answer] body as it was stored, so a retry sends the same bytes and the same `clientId`. */
    suspend fun answer(
        itemId: Long,
        body: String,
    ): AnswerResult
}
