package io.github.nytka_labeler.session

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nytka_labeler.api.Answer
import io.github.nytka_labeler.api.ApiResult
import io.github.nytka_labeler.api.Ask
import io.github.nytka_labeler.api.Codes
import io.github.nytka_labeler.api.LabelItem
import io.github.nytka_labeler.api.LabelingClient
import io.github.nytka_labeler.api.TaskInfo
import io.github.nytka_labeler.api.Via
import io.github.nytka_labeler.outbox.Outbox
import io.github.nytka_labeler.outbox.OutboxRow
import io.github.nytka_labeler.outbox.SendScheduler
import io.github.nytka_labeler.play.ClipPlayer
import io.github.nytka_labeler.play.HeadphoneMonitor
import io.github.nytka_labeler.settings.Settings
import io.github.nytka_labeler.settings.SettingsSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/** The numbers of a feed session; tests shrink them. */
data class SessionConfig(
    /** Answers that end the feed with the end card. */
    val goal: Int = 30,
    /** How long an answer can be taken back before the outbox may send it. */
    val undoMs: Long = 4_000,
    /** How long the model's guess shows after an answer. */
    val flashMs: Long = 500,
)

/** What the owner has picked in the answer sheet but not yet sent. The button that answers reads it. */
data class Draft(
    /** `device`, `several` or `wrong_words`: the answer waits for Save. */
    val reveal: String? = null,
    val pickedSources: Set<String> = emptySet(),
    val deviceType: String? = null,
    val correction: String = "",
    val flags: Set<String> = emptySet(),
    val language: String? = null,
    val content: String? = null,
    val linesDiffer: Boolean = false,
)

/** Where an answered clip stands with the server. */
enum class SaveState { Waiting, Saved, Refused }

data class AnsweredCard(
    val skipped: Boolean,
    val state: SaveState,
    val refusal: String? = null,
)

/** One caption line; the text is what the owner heard of a stranger, so it stays out of logs. */
data class CaptionLine(
    val text: String,
    val startMs: Long,
    val endMs: Long,
) {
    override fun toString() = "CaptionLine($startMs..$endMs)"
}

/** One page of the feed. */
data class CardUi(
    val itemId: Long,
    val task: String,
    val ask: String,
    val question: String,
    val lines: List<CaptionLine>,
    val before: List<CaptionLine>,
    val after: List<CaptionLine>,
    /** 0 to 1 per bar, or null: the card then shows a flat bar. */
    val peaks: List<Float>?,
    val durationMs: Long,
    /** In a mixed feed, the task differs from the clip before: its name animates in. */
    val taskChange: Boolean,
)

/** Progress toward a gate of one task, from `/tasks` before and after the session. */
data class GateProgress(
    val task: String,
    val before: Int,
    val after: Int,
    val goal: Int,
    val goalLabel: String,
)

data class EndSummary(
    val answered: Int,
    val skipped: Int,
    val saved: Int,
    /** Answers still sending. */
    val waiting: Int,
    val refused: List<String>,
    /** Answers that differed from the model's guess. */
    val disagreed: Int,
    val goal: Int,
    val gates: List<GateProgress>,
)

enum class Sheet { Quick, Full }

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
        val goal: Int,
        val answeredCount: Int,
        val index: Int,
        val cards: List<CardUi>,
        val draft: Draft,
        val sheet: Sheet?,
        /** The codes of the quick sheet that a swipe or a rail button opened. */
        val quickOptions: List<String>,
        val playing: Boolean,
        /** On the loudspeaker the clip waits for a tap. */
        val waitsForTap: Boolean,
        val hasContext: Boolean,
        val showingContext: Boolean,
        val undo: Boolean,
        /** "model said: device, 0.71", for half a second after an answer. */
        val flash: String?,
    ) : SessionUi {
        val current: CardUi get() = cards[index]
    }

    data class Ended(
        val summary: EndSummary,
        val week: List<WeekDay>,
        val undo: Boolean,
    ) : SessionUi
}

private enum class Phase { Loading, Failed, Empty, Running, Ended }

/** What the owner gave for one item: enough for the end card and for Undo. */
private data class Given(
    val clientId: String,
    val skipped: Boolean,
    val task: String,
    val disagreed: Boolean,
    val index: Int,
)

private data class Core(
    val phase: Phase = Phase.Loading,
    val message: String = "",
    val progress: Pair<Int, Int> = 0 to 0,
    val session: LoadedSession? = null,
    val cards: List<CardUi> = emptyList(),
    val index: Int = 0,
    val sheet: Sheet? = null,
    val draft: Draft = Draft(),
    val showingContext: Boolean = false,
    val waitsForTap: Boolean = false,
    /** item id to what its latest answer was. */
    val given: Map<Long, Given> = emptyMap(),
    val undo: String? = null,
    val flash: String? = null,
    val gateBefore: List<TaskInfo>? = null,
    val gateAfter: List<TaskInfo>? = null,
)

private enum class Kind { Voice, Device, Text, Overlap }

private fun kindOf(item: LabelItem): Kind =
    when (item.task) {
        "voice" -> Kind.Voice
        "device" -> Kind.Device
        "quiet", "transcript" -> Kind.Text
        "overlap" -> Kind.Overlap
        else ->
            when (item.ask) {
                Ask.TRANSCRIPT -> Kind.Text
                Ask.OVERLAP -> Kind.Overlap
                else -> Kind.Device
            }
    }

private fun questionOf(kind: Kind) =
    when (kind) {
        Kind.Voice -> "Is this you?"
        Kind.Device -> "Is it coming from a device?"
        Kind.Text -> "Is the text right?"
        Kind.Overlap -> "Several voices at once?"
    }

/** The codes of the quick sheet a "No" (or, for overlap, a "Yes") opens. */
private fun quickOptionsOf(kind: Kind): List<String> =
    when (kind) {
        Kind.Device -> listOf("me", "live", "call", "none", "cant_tell")
        Kind.Text -> listOf("small_errors", "wrong_words", "made_up", "cant_hear")
        Kind.Overlap -> listOf("two", "three_plus")
        Kind.Voice -> emptyList()
    }

private fun yesCodes(kind: Kind): Set<String> =
    when (kind) {
        Kind.Voice -> setOf("me")
        Kind.Device -> setOf("device")
        Kind.Text -> setOf("right")
        Kind.Overlap -> setOf("two", "three_plus")
    }

private fun captions(lines: List<io.github.nytka_labeler.api.ClipLine>) =
    lines.map { CaptionLine(it.text, it.startMs, it.endMs) }

// One method per button group of the feed: a cohesive event surface for the screen, not a grab bag.
@Suppress("TooManyFunctions", "LongParameterList")
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
        private val client: LabelingClient,
        private val headphones: HeadphoneMonitor,
        private val config: SessionConfig,
    ) : ViewModel() {
        private val task: String = checkNotNull(state["task"])
        private val core = MutableStateFlow(Core())
        private var newId: () -> String = { UUID.randomUUID().toString() }
        private var speakerAutoplay = false

        // Enqueue and Undo run in the order they were asked, so Undo never overtakes the insert it undoes.
        private val outboxLock = Mutex()
        private val mutableHaptics = MutableSharedFlow<Boolean>(extraBufferCapacity = 8)

        /** One tick per answer: true for a "yes" (confirm), false for the rest (reject). */
        val haptics: SharedFlow<Boolean> = mutableHaptics

        private val mutablePosition = MutableStateFlow(0L)

        /** Playhead of the current clip, polled while it plays; the waveform and the captions follow it. */
        val position: StateFlow<Long> = mutablePosition

        val ui: StateFlow<SessionUi> =
            combine(core, outbox.rows, outbox.sent, player.playing, settings.settings, ::render)
                .stateIn(viewModelScope, SharingStarted.Eagerly, SessionUi.Loading(0, 0))

        init {
            viewModelScope.launch {
                player.playing.collectLatest { playing ->
                    mutablePosition.value = player.positionMs
                    while (playing) {
                        delay(POSITION_TICK_MS)
                        mutablePosition.value = player.positionMs
                    }
                }
            }
            viewModelScope.launch { settings.settings.collect { speakerAutoplay = it.autoplaySpeaker } }
            viewModelScope.launch { outbox.sent.collect { if (core.value.phase == Phase.Ended) refreshGates() } }
            load()
        }

        /** A test sets its own ids. */
        internal fun useIds(next: () -> String) {
            newId = next
        }

        fun load() {
            core.value = Core()
            viewModelScope.launch {
                speakerAutoplay = settings.current().autoplaySpeaker
                when (val result = source.start(task, SESSION_SIZE) { done, total -> progress(done, total) }) {
                    is StartResult.Failed ->
                        core.update { it.copy(phase = Phase.Failed, message = result.message) }
                    StartResult.Empty -> core.update { it.copy(phase = Phase.Empty) }
                    is StartResult.Ready -> {
                        core.update {
                            it.copy(phase = Phase.Running, session = result.session, cards = cardsOf(result.session))
                        }
                        showCard(0)
                        (client.tasks() as? ApiResult.Ok)?.let { ok ->
                            core.update { c -> c.copy(gateBefore = ok.value) }
                        }
                    }
                }
            }
        }

        /** "Another 30" on the end card: a new session of the same task. */
        fun another() {
            leave()
            load()
        }

        private fun progress(
            done: Int,
            total: Int,
        ) = core.update { it.copy(progress = done to total) }

        private fun cardsOf(session: LoadedSession): List<CardUi> =
            session.cards.mapIndexed { i, card ->
                val item = card.item
                CardUi(
                    itemId = item.id,
                    task = item.task,
                    ask = item.ask,
                    question = questionOf(kindOf(item)),
                    lines = captions(item.lines),
                    before = captions(item.contextBefore),
                    after = captions(item.contextAfter),
                    peaks = item.peaks?.takeIf { it.isNotEmpty() }?.map { it.toFloat().coerceIn(0f, 1f) },
                    durationMs = item.durationMs,
                    taskChange = task == MIXED_TASK && (i == 0 || session.cards[i - 1].item.task != item.task),
                )
            }

        // ---- answers

        /** The "Yes" of the task's fixed question: a swipe right, or the rail button. */
        fun yes(via: String) {
            val item = current()?.item ?: return
            when (kindOf(item)) {
                Kind.Voice -> commit(item, via, source = listOf("me"))
                Kind.Device -> commit(item, via, source = listOf("device"))
                Kind.Text -> commit(item, via, transcript = "right")
                Kind.Overlap -> openQuick()
            }
        }

        /** The "No": a swipe left, or the rail button. */
        fun no(via: String) {
            val item = current()?.item ?: return
            when (kindOf(item)) {
                Kind.Voice -> commit(item, via, source = listOf("not_me"))
                Kind.Overlap -> commit(item, via, overlap = "one")
                Kind.Device, Kind.Text -> openQuick()
            }
        }

        private fun openQuick() = core.update { it.copy(sheet = Sheet.Quick, draft = it.draft.copy(reveal = null)) }

        /** The full answer sheet with every value and the optional details (long-press, or More). */
        fun openFull() = core.update { if (it.phase == Phase.Running) it.copy(sheet = Sheet.Full) else it }

        fun dismissSheet() = core.update { it.copy(sheet = null, draft = it.draft.copy(reveal = null)) }

        /** A button of a sheet. Buttons that open a panel (Device, Several, Wrong words) wait for [confirm]. */
        fun choose(
            code: String,
            via: String = Via.SHEET,
        ) {
            val item = current()?.item ?: return
            when {
                item.ask == Ask.SOURCE && code == "device" -> reveal("device")
                item.ask == Ask.SOURCE && code == SEVERAL -> reveal(SEVERAL)
                item.ask == Ask.TRANSCRIPT && code == "wrong_words" -> {
                    val text =
                        item.lines
                            .singleOrNull()
                            ?.text
                            .orEmpty()
                    core.update { it.copy(draft = it.draft.copy(reveal = code, correction = text)) }
                }
                item.ask == Ask.SOURCE -> commit(item, via, source = listOf(code))
                item.ask == Ask.TRANSCRIPT -> commit(item, via, transcript = code)
                item.ask == Ask.OVERLAP -> commit(item, via, overlap = code)
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

        fun setContent(code: String) =
            core.update { it.copy(draft = it.draft.copy(content = code.takeIf { c -> c != it.draft.content })) }

        fun toggleLinesDiffer() = core.update { it.copy(draft = it.draft.copy(linesDiffer = !it.draft.linesDiffer)) }

        /** Save of an opened panel. */
        fun confirm() {
            val item = current()?.item ?: return
            val draft = core.value.draft
            when (draft.reveal) {
                "device" -> commit(item, Via.SHEET, source = listOf("device"), deviceType = draft.deviceType)
                SEVERAL ->
                    if (draft.pickedSources.isNotEmpty()) {
                        commit(item, Via.SHEET, source = Codes.SOURCE_PICKS.filter { it in draft.pickedSources })
                    }
                "wrong_words" -> {
                    val single = item.lines.size == 1
                    commit(
                        item,
                        Via.SHEET,
                        transcript = "wrong_words",
                        correction = draft.correction.takeIf { single && it.isNotBlank() },
                    )
                }
            }
        }

        /** The rail's Skip (or a call from a test): an explicit skip, sent as `skipped`. */
        fun skip(via: String = Via.BUTTON) {
            val item = current()?.item ?: return
            enqueue(
                item,
                Answer(
                    clientId = newId(),
                    skipped = true,
                    listenedMs = player.listenedMs.coerceIn(0, Answer.MAX_LISTENED_MS),
                    answeredWith = via,
                ),
            )
            advance()
        }

        private fun commit(
            item: LabelItem,
            via: String,
            source: List<String>? = null,
            deviceType: String? = null,
            transcript: String? = null,
            correction: String? = null,
            overlap: String? = null,
        ) {
            val draft = core.value.draft
            val answer =
                Answer(
                    clientId = newId(),
                    source = source,
                    deviceType = deviceType,
                    transcript = transcript,
                    flags = Codes.FLAGS.filter { it in draft.flags }.ifEmpty { null },
                    language = draft.language,
                    content = draft.content,
                    correction = correction,
                    overlap = overlap,
                    linesDiffer = draft.linesDiffer,
                    listenedMs = player.listenedMs.coerceIn(0, Answer.MAX_LISTENED_MS),
                    answeredWith = via,
                )
            enqueue(item, answer)
            val positive = (source.orEmpty() + listOfNotNull(transcript, overlap)).any { it in yesCodes(kindOf(item)) }
            mutableHaptics.tryEmit(positive)
            item.guess?.let { guess -> flash("model said: ${guess.value}, ${"%.2f".format(Locale.US, guess.score)}") }
            advance()
        }

        private fun enqueue(
            item: LabelItem,
            answer: Answer,
        ) {
            val sessionId = core.value.session?.sessionId ?: return
            val clientId = answer.clientId
            val given = Given(clientId, answer.skipped, item.task, disagrees(item, answer), core.value.index)
            core.update {
                it.copy(
                    given = it.given + (item.id to given),
                    undo = if (answer.skipped) it.undo else clientId,
                )
            }
            viewModelScope.launch {
                outboxLock.withLock {
                    outbox.enqueue(answer, item.id, sessionId, notBefore = clock.millis() + config.undoMs)
                }
                scheduler.schedule()
                if (!answer.skipped) markToday()
            }
            if (!answer.skipped) {
                viewModelScope.launch {
                    delay(config.undoMs)
                    core.update { if (it.undo == clientId) it.copy(undo = null) else it }
                }
            }
        }

        /** The model's guess is hidden until now; a clip with no guess never disagrees. */
        private fun disagrees(
            item: LabelItem,
            answer: Answer,
        ): Boolean {
            val guess = item.guess?.value ?: return false
            if (answer.skipped) return false
            val given = answer.source.orEmpty() + listOfNotNull(answer.transcript, answer.overlap)
            return given.isNotEmpty() && guess !in given
        }

        private fun flash(text: String) {
            core.update { it.copy(flash = text) }
            viewModelScope.launch {
                delay(config.flashMs)
                core.update { if (it.flash == text) it.copy(flash = null) else it }
            }
        }

        /** Takes the last answer back if the outbox has not sent it, and shows its clip again. */
        fun undo() {
            val clientId = core.value.undo ?: return
            val (itemId, given) =
                core.value.given.entries
                    .firstOrNull { it.value.clientId == clientId } ?: return
            viewModelScope.launch {
                val removed = outboxLock.withLock { outbox.cancel(clientId) }
                if (removed) {
                    core.update { it.copy(given = it.given - itemId, undo = null) }
                    if (core.value.phase == Phase.Ended) clips.unmarkEnded(core.value.session!!.sessionId)
                    showCard(given.index)
                } else {
                    core.update { it.copy(undo = null) }
                }
            }
        }

        private suspend fun markToday() {
            val today = LocalDate.now(clock).toString()
            val current = settings.current()
            if (current.lastSessionDay != today || today !in current.sessionDays) {
                val recent = (current.sessionDays + today).sortedDescending().take(DAYS_KEPT).toSet()
                settings.update { it.copy(lastSessionDay = today, sessionDays = recent) }
            }
        }

        // ---- moving

        /** The pager settled on [page] after a swipe. A swipe up past an unanswered clip is a skip. */
        fun onPage(page: Int) {
            val c = core.value
            val session = c.session ?: return
            if (c.phase != Phase.Running || page == c.index || page !in session.cards.indices) return
            val left = session.cards[c.index].item
            if (page > c.index && left.id !in c.given) {
                enqueue(
                    left,
                    Answer(
                        clientId = newId(),
                        skipped = true,
                        listenedMs = player.listenedMs.coerceIn(0, Answer.MAX_LISTENED_MS),
                        answeredWith = Via.SWIPE,
                    ),
                )
            }
            showCard(page)
        }

        fun back() {
            val index = core.value.index
            if (core.value.phase == Phase.Ended) {
                clips.unmarkEnded(core.value.session!!.sessionId)
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
            val answered =
                core.value.given.values
                    .count { !it.skipped }
            val next = core.value.index + 1
            if (answered >= config.goal || next >= session.cards.size) {
                player.stop()
                core.update { it.copy(phase = Phase.Ended, sheet = null, draft = Draft()) }
                finishSession(session)
                viewModelScope.launch { refreshGates() }
            } else {
                showCard(next)
            }
        }

        private fun showCard(
            index: Int,
            resume: Boolean = false,
        ) {
            val card = core.value.session!!.cards[index]
            val allowed = headphones.connected.value || speakerAutoplay
            core.update {
                it.copy(
                    phase = Phase.Running,
                    index = index,
                    sheet = null,
                    showingContext = false,
                    waitsForTap = !allowed,
                    draft = Draft(language = card.item.prefill?.language),
                )
            }
            mutablePosition.value = 0
            player.load(card.clip, autoplay = allowed && !resume)
            if (resume) player.pause()
        }

        /** Tap on the card: play the clip again from the start (the first tap on the loudspeaker starts it). */
        fun replay() {
            core.update { it.copy(waitsForTap = false) }
            player.replay()
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
            viewModelScope.launch { outboxLock.withLock { clips.sweep { outbox.pendingCount(it) } } }
        }

        private suspend fun refreshGates() {
            (client.tasks() as? ApiResult.Ok)?.let { ok -> core.update { it.copy(gateAfter = ok.value) } }
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
            prefs: Settings,
        ): SessionUi =
            when (c.phase) {
                Phase.Loading -> SessionUi.Loading(c.progress.first, c.progress.second)
                Phase.Failed -> SessionUi.Failed(c.message)
                Phase.Empty -> SessionUi.Empty
                Phase.Ended ->
                    SessionUi.Ended(
                        summary(c, rows, sent),
                        weekStrip(LocalDate.now(clock), prefs.sessionDays),
                        undo = c.undo != null,
                    )
                Phase.Running -> {
                    val session = checkNotNull(c.session)
                    val card = session.cards[c.index]
                    val kind = kindOf(card.item)
                    SessionUi.Showing(
                        position = c.index + 1,
                        total = minOf(config.goal, session.cards.size),
                        goal = config.goal,
                        answeredCount = c.given.values.count { !it.skipped },
                        index = c.index,
                        cards = c.cards,
                        draft = c.draft,
                        sheet = c.sheet,
                        quickOptions = quickOptionsOf(kind),
                        playing = playing,
                        waitsForTap = c.waitsForTap,
                        hasContext = card.withContext != null,
                        showingContext = c.showingContext,
                        undo = c.undo != null,
                        flash = c.flash,
                    )
                }
            }

        private fun stateOf(
            given: Given,
            rows: List<OutboxRow>,
            sent: Set<String>,
        ): AnsweredCard {
            val row = rows.firstOrNull { it.clientId == given.clientId }
            return when {
                // The tick needs the server's 200 itself, not just a row that is not there (yet).
                row == null && given.clientId in sent -> AnsweredCard(given.skipped, SaveState.Saved)
                row?.state == OutboxRow.STATE_PARKED -> AnsweredCard(given.skipped, SaveState.Refused, row.error)
                else -> AnsweredCard(given.skipped, SaveState.Waiting)
            }
        }

        private fun summary(
            c: Core,
            rows: List<OutboxRow>,
            sent: Set<String>,
        ): EndSummary {
            val states = c.given.values.map { stateOf(it, rows, sent) }
            return EndSummary(
                answered = states.count { !it.skipped },
                skipped = states.count { it.skipped },
                saved = states.count { it.state == SaveState.Saved },
                waiting = states.count { it.state == SaveState.Waiting },
                refused = states.mapNotNull { it.refusal },
                disagreed = c.given.values.count { it.disagreed },
                goal = config.goal,
                gates = gates(c),
            )
        }

        /** Gates of the tasks the owner answered in this session, where the server states a goal. */
        private fun gates(c: Core): List<GateProgress> {
            val before = c.gateBefore.orEmpty().associateBy { it.task }
            val answered =
                c.given.values
                    .filter { !it.skipped }
                    .map { it.task }
                    .toSet()
            return c.gateAfter
                .orEmpty()
                .filter { it.task in answered }
                .mapNotNull { after ->
                    after.goal?.takeIf { it > 0 }?.let { goal ->
                        GateProgress(
                            after.task,
                            before[after.task]?.towardGoal ?: after.towardGoal,
                            after.towardGoal,
                            goal,
                            after.goalLabel.orEmpty(),
                        )
                    }
                }
        }

        companion object {
            const val SESSION_SIZE = 30
            const val SEVERAL = "several"
            private const val MIXED_TASK = io.github.nytka_labeler.api.MIXED
            private const val MAX_SOURCES = 2
            private const val DAYS_KEPT = 30
            private const val POSITION_TICK_MS = 50L
        }
    }
