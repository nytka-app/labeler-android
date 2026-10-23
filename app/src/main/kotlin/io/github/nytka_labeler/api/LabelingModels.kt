package io.github.nytka_labeler.api

import kotlinx.serialization.Serializable

/** The wire types of `docs/api.md` (labeling API v1). Unknown keys are ignored, so a newer server works. */
@Serializable
data class TaskInfo(
    val task: String,
    val ask: String,
    val open: Int = 0,
    val labelled: Int = 0,
    val goal: Int? = null,
    val goalLabel: String? = null,
)

@Serializable
data class TasksResponse(
    val tasks: List<TaskInfo> = emptyList(),
)

@Serializable
data class ClipLine(
    val segmentId: Long,
    val text: String = "",
    val isUser: Boolean = false,
    val startMs: Long = 0,
    val endMs: Long = 0,
) {
    /** The text is what the owner heard of a stranger: it stays out of logs and crash reports. */
    override fun toString() = "ClipLine(segmentId=$segmentId)"
}

@Serializable
data class Guess(
    val model: String,
    val value: String,
    val score: Double,
)

@Serializable
data class Prefill(
    val language: String? = null,
)

@Serializable
data class LabelItem(
    val id: Long,
    val task: String,
    val ask: String,
    val reason: String = "",
    val conversationId: String = "",
    val startedAt: String = "",
    val durationMs: Long = 0,
    val lines: List<ClipLine> = emptyList(),
    val guess: Guess? = null,
    val prefill: Prefill? = null,
)

@Serializable
data class NextResponse(
    val sessionId: String,
    val leasedUntil: String = "",
    val items: List<LabelItem> = emptyList(),
)

/** The 400 body: `{ "field": "source", "error": "unknown value" }`. */
@Serializable
data class FieldError(
    val field: String = "",
    val error: String = "",
)

/** The axes a card asks, as `ask` names them. */
object Ask {
    const val SOURCE = "source"
    const val TRANSCRIPT = "transcript"
    const val OVERLAP = "overlap"
}

/** The fixed codes of `docs/api.md` § Values, in the order the buttons show them. */
object Codes {
    val SOURCE = listOf("me", "live", "device", "call", "none", "cant_tell")
    val SOURCE_PICKS = listOf("me", "live", "device", "call")
    val DEVICE_TYPE = listOf("tv", "phone", "laptop", "car", "assistant", "other")
    val TRANSCRIPT = listOf("right", "small_errors", "wrong_words", "made_up", "cant_hear")
    val FLAGS = listOf("quiet", "noisy", "overlap", "muffled", "clipped", "two_speakers")
    val CONTENT = listOf("speech", "singing", "vocal", "music", "other_sound", "silence")
    val LANGUAGE = listOf("uk", "ru", "en", "mixed", "other")
    val OVERLAP = listOf("one", "two", "three_plus", "cant_tell")
}

@Serializable
data class PendingVoice(
    val labelId: Long,
    val segmentId: Long = 0,
    val durationMs: Long = 0,
)

@Serializable
data class PendingVoiceResponse(
    val items: List<PendingVoice> = emptyList(),
)

@Serializable
data class ConfirmVoiceRequest(
    val labelIds: List<Long>,
)

@Serializable
data class ConfirmVoiceResponse(
    val learned: Int = 0,
)
