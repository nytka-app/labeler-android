package io.github.nytka_labeler.session

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nytka_labeler.api.Answer
import io.github.nytka_labeler.api.Ask
import io.github.nytka_labeler.api.Codes
import io.github.nytka_labeler.outbox.Outbox
import io.github.nytka_labeler.outbox.OutboxRow
import io.github.nytka_labeler.outbox.SendScheduler
import io.github.nytka_labeler.play.ClipPlayer
import io.github.nytka_labeler.settings.SettingsSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

/** What the owner has picked on the card but not yet sent. The button that answers reads it. */
data class Draft(
    /** `device`, `several` or `wrong_words`: the answer waits for Save. */
    val reveal: String? = null,
    val pickedSources: Set<String> = emptySet(),
    val deviceType: String? = null,
    val correction: String = "",
    val flags: Set<String> = emptySet(),
    val language: String? = null,
    val linesDiffer: Boolean = false,
)

/** Where an answered card stands with the server. */
enum class SaveState { Waiting, Saved, Refused }

data class AnsweredCard(
    val skipped: Boolean,
    val state: SaveState,
    val refusal: String? = null,
)

data class EndSummary(
    val answered: Int,
    val skipped: Int,
    val saved: Int,
    val waiting: Int,
    val refused: List<String>,
)

sealed interface SessionUi {
    data class Loading(
        val done: Int,
        val total: Int,
    ) : SessionUi

    data class Failed(
        val message: String,
    ) : SessionUi

    data object Empty : SessionUi

    data class Showing(
        val position: Int,
        val total: Int,
        val itemId: Long,
        val ask: String,
        val lines: List<String>,
        val draft: Draft,
        val hasContext: Boolean,
        val showingContext: Boolean,
        val playing: Boolean,
        val answered: AnsweredCard?,
        val previous: AnsweredCard?,
    ) : SessionUi

    data class Ended(
        val summary: EndSummary,
    ) : SessionUi
}

private enum class Phase { Loading, Failed, Empty, Running, Ended }

private data class Core(
    val phase: Phase = Phase.Loading,
    val message: String = "",
    val progress: Pair<Int, Int> = 0 to 0,
    val session: LoadedSession? = null,
    val index: Int = 0,
    val draft: Draft = Draft(),
    val showingContext: Boolean = false,
    /** item id to the client id of its latest answer and whether that was a skip. */
    val given: Map<Long, Pair<String, Boolean>> = emptyMap(),
)

// One method per button group of the card: a cohesive event surface for the screen, not a grab bag.
@Suppress("TooManyFunctions")
@HiltViewModel
class SessionViewModel
    @Inject
    constructor(
        state: SavedStateHandle,
        private val source: SessionSource,
        private val outbox: Outbox,
        private val scheduler: SendScheduler,
        private val clips: ClipStore,
        private val player: ClipPlayer,
        private val settings: SettingsSource,
        private val clock: Clock,
    ) : ViewModel() {
        private val task: String = checkNotNull(state["task"])
        private val core = MutableStateFlow(Core())
        private var newId: () -> String = { UUID.randomUUID().toString() }

        val ui: StateFlow<SessionUi> =
            combine(core, outbox.rows, outbox.sent, player.playing, ::render)
                .stateIn(viewModelScope, SharingStarted.Eagerly, SessionUi.Loading(0, 0))

        init {
            load()
        }

        /** A test sets its own ids. */
        internal fun useIds(next: () -> String) {
            newId = next
        }

        fun load() {
            core.value = Core()
            viewModelScope.launch {
                when (val result = source.start(task, SESSION_SIZE) { done, total -> progress(done, total) }) {
                    is StartResult.Failed ->
                        core.update { it.copy(phase = Phase.Failed, message = failureText(result)) }
                    StartResult.Empty -> core.update { it.copy(phase = Phase.Empty) }
                    is StartResult.Ready -> {
                        core.update { it.copy(phase = Phase.Running, session = result.session) }
                        showCard(0)
                    }
                }
            }
        }

        private fun progress(
            done: Int,
            total: Int,
        ) = core.update { it.copy(progress = done to total) }

        private fun failureText(failed: StartResult.Failed) = failed.message

        // ---- answers

        /** A button of the asked axis. Buttons that open a panel (Device, Several, Wrong words) wait for [confirm]. */
        fun choose(code: String) {
            val card = current() ?: return
            when {
                card.item.ask == Ask.SOURCE && code == "device" -> reveal("device")
                card.item.ask == Ask.SOURCE && code == SEVERAL -> reveal(SEVERAL)
                card.item.ask == Ask.TRANSCRIPT && code == "wrong_words" -> {
                    val text =
                        card.item.lines
                            .singleOrNull()
                            ?.text
                            .orEmpty()
                    core.update { it.copy(draft = it.draft.copy(reveal = code, correction = text)) }
                }
                card.item.ask == Ask.SOURCE -> commit(source = listOf(code))
                card.item.ask == Ask.TRANSCRIPT -> commit(transcript = code)
                card.item.ask == Ask.OVERLAP -> commit(overlap = code)
            }
        }

        private fun reveal(what: String) = core.update { it.copy(draft = it.draft.copy(reveal = what)) }

        fun pickDeviceType(type: String) =
            core.update { it.copy(draft = it.draft.copy(deviceType = type.takeIf { t -> t != it.draft.deviceType })) }

        fun togglePick(code: String) =
            core.update {
                val picked = it.draft.pickedSources
                val next =
                    when {
                        code in picked -> picked - code
                        picked.size < MAX_SOURCES -> picked + code
                        else -> picked
                    }
                it.copy(draft = it.draft.copy(pickedSources = next))
            }

        fun setCorrection(text: String) = core.update { it.copy(draft = it.draft.copy(correction = text)) }

        fun toggleFlag(code: String) =
            core.update {
                val flags = it.draft.flags
                it.copy(draft = it.draft.copy(flags = if (code in flags) flags - code else flags + code))
            }

        fun setLanguage(code: String) =
            core.update { it.copy(draft = it.draft.copy(language = code.takeIf { c -> c != it.draft.language })) }

        fun toggleLinesDiffer() = core.update { it.copy(draft = it.draft.copy(linesDiffer = !it.draft.linesDiffer)) }

        /** Save of an opened panel. */
        fun confirm() {
            val draft = core.value.draft
            when (draft.reveal) {
                "device" -> commit(source = listOf("device"), deviceType = draft.deviceType)
                SEVERAL ->
                    if (draft.pickedSources.isNotEmpty()) {
                        commit(source = Codes.SOURCE_PICKS.filter { it in draft.pickedSources })
                    }
                "wrong_words" -> {
                    val single = current()?.item?.lines?.size == 1
                    commit(
                        transcript = "wrong_words",
                        correction = draft.correction.takeIf { single && it.isNotBlank() },
                    )
                }
            }
        }

        fun skip() {
            val card = current() ?: return
            enqueue(Answer(clientId = newId(), skipped = true, listenedMs = player.listenedMs), card.item.id)
            advance()
        }

        private fun commit(
            source: List<String>? = null,
            deviceType: String? = null,
            transcript: String? = null,
            correction: String? = null,
            overlap: String? = null,
        ) {
            val card = current() ?: return
            val draft = core.value.draft
            val answer =
                Answer(
                    clientId = newId(),
                    source = source,
                    deviceType = deviceType,
                    transcript = transcript,
                    flags = Codes.FLAGS.filter { it in draft.flags }.ifEmpty { null },
                    language = draft.language,
                    correction = correction,
                    overlap = overlap,
                    linesDiffer = draft.linesDiffer,
                    listenedMs = player.listenedMs,
                )
            enqueue(answer, card.item.id)
            advance()
        }

        private fun enqueue(
            answer: Answer,
            itemId: Long,
        ) {
            val sessionId = core.value.session?.sessionId ?: return
            core.update { it.copy(given = it.given + (itemId to (answer.clientId to answer.skipped))) }
            viewModelScope.launch {
                outbox.enqueue(answer, itemId, sessionId)
                scheduler.schedule()
                if (!answer.skipped) markToday()
            }
        }

        private suspend fun markToday() {
            val today = LocalDate.now(clock).toString()
            if (settings.current().lastSessionDay != today) settings.update { it.copy(lastSessionDay = today) }
        }

        // ---- moving

        fun back() {
            val index = core.value.index
            if (core.value.phase == Phase.Ended) {
                showCard(
                    core.value.session!!
                        .cards.lastIndex,
                    resume = true,
                )
            } else if (index > 0) {
                showCard(index - 1, resume = true)
            }
        }

        private fun advance() {
            val session = core.value.session ?: return
            val next = core.value.index + 1
            if (next >= session.cards.size) {
                player.stop()
                core.update { it.copy(phase = Phase.Ended) }
                finishSession(session)
            } else {
                showCard(next)
            }
        }

        private fun showCard(
            index: Int,
            resume: Boolean = false,
        ) {
            val card = core.value.session!!.cards[index]
            core.update {
                it.copy(
                    phase = Phase.Running,
                    index = index,
                    showingContext = false,
                    draft = Draft(language = card.item.prefill?.language),
                )
            }
            player.load(card.clip)
            if (resume) player.pause()
        }

        fun playPause() = player.toggle()

        fun toggleContext() {
            val card = current() ?: return
            val wide = card.withContext ?: return
            val showing = !core.value.showingContext
            core.update { it.copy(showingContext = showing) }
            player.load(if (showing) wide else card.clip, keepCount = true)
        }

        fun pausePlayback() = player.pause()

        /** The owner leaves the session (End screen or the back arrow). Clips go once nothing is waiting. */
        fun leave() {
            val session = core.value.session ?: return
            player.stop()
            finishSession(session)
        }

        private fun finishSession(session: LoadedSession) {
            clips.markEnded(session.sessionId)
            scheduler.schedule()
            viewModelScope.launch { clips.sweep { outbox.pendingCount(it) } }
        }

        private fun current(): Card? {
            val c = core.value
            return if (c.phase == Phase.Running) c.session?.cards?.getOrNull(c.index) else null
        }

        override fun onCleared() {
            player.stop()
        }

        // ---- what the screen shows

        private fun render(
            c: Core,
            rows: List<OutboxRow>,
            sent: Set<String>,
            playing: Boolean,
        ): SessionUi =
            when (c.phase) {
                Phase.Loading -> SessionUi.Loading(c.progress.first, c.progress.second)
                Phase.Failed -> SessionUi.Failed(c.message)
                Phase.Empty -> SessionUi.Empty
                Phase.Ended -> SessionUi.Ended(summary(c, rows, sent))
                Phase.Running -> {
                    val session = checkNotNull(c.session)
                    val card = session.cards[c.index]
                    SessionUi.Showing(
                        position = c.index + 1,
                        total = session.cards.size,
                        itemId = card.item.id,
                        ask = card.item.ask,
                        lines = card.item.lines.map { it.text },
                        draft = c.draft,
                        hasContext = card.withContext != null,
                        showingContext = c.showingContext,
                        playing = playing,
                        answered = answeredCard(c, card.item.id, rows, sent),
                        previous =
                            session.cards
                                .getOrNull(
                                    c.index - 1,
                                )?.let { answeredCard(c, it.item.id, rows, sent) },
                    )
                }
            }

        private fun answeredCard(
            c: Core,
            itemId: Long,
            rows: List<OutboxRow>,
            sent: Set<String>,
        ): AnsweredCard? {
            val (clientId, skipped) = c.given[itemId] ?: return null
            val row = rows.firstOrNull { it.clientId == clientId }
            return when {
                // The tick needs the server's 200 itself, not just a row that is not there (yet).
                row == null && clientId in sent -> AnsweredCard(skipped, SaveState.Saved)
                row?.state == OutboxRow.STATE_PARKED -> AnsweredCard(skipped, SaveState.Refused, row.error)
                else -> AnsweredCard(skipped, SaveState.Waiting)
            }
        }

        private fun summary(
            c: Core,
            rows: List<OutboxRow>,
            sent: Set<String>,
        ): EndSummary {
            val states = c.given.keys.mapNotNull { answeredCard(c, it, rows, sent) }
            return EndSummary(
                answered = states.count { !it.skipped },
                skipped = states.count { it.skipped },
                saved = states.count { it.state == SaveState.Saved },
                waiting = states.count { it.state == SaveState.Waiting },
                refused = states.mapNotNull { it.refusal },
            )
        }

        companion object {
            const val SESSION_SIZE = 30
            const val SEVERAL = "several"
            private const val MAX_SOURCES = 2
        }
    }
