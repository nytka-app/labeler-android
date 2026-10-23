package io.github.nytka_labeler.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nytka_labeler.api.ApiResult
import io.github.nytka_labeler.api.FailureKind
import io.github.nytka_labeler.api.LabelingClient
import io.github.nytka_labeler.api.TaskInfo
import io.github.nytka_labeler.outbox.Outbox
import io.github.nytka_labeler.outbox.OutboxRow
import io.github.nytka_labeler.outbox.SendScheduler
import io.github.nytka_labeler.play.HeadphoneMonitor
import io.github.nytka_labeler.remind.ReminderControl
import io.github.nytka_labeler.settings.SettingsSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TasksUi(
    val loading: Boolean = true,
    val tasks: List<TaskInfo> = emptyList(),
    val error: String? = null,
    /** The server refused the token: the screen asks for a new one. */
    val needsToken: Boolean = false,
    val headphones: Boolean = false,
    val reminders: Boolean = true,
    /** Answers still on the phone, waiting for the network. */
    val waiting: Int = 0,
    val refused: List<OutboxRow> = emptyList(),
)

private data class Loaded(
    val loading: Boolean = true,
    val tasks: List<TaskInfo> = emptyList(),
    val error: String? = null,
    val authFailed: Boolean = false,
)

@HiltViewModel
class TasksViewModel
    @Inject
    constructor(
        private val client: LabelingClient,
        private val outbox: Outbox,
        private val scheduler: SendScheduler,
        private val settings: SettingsSource,
        private val reminders: ReminderControl,
        headphones: HeadphoneMonitor,
    ) : ViewModel() {
        private val loaded = MutableStateFlow(Loaded())

        val ui: StateFlow<TasksUi> =
            combine(loaded, outbox.rows, headphones.connected, settings.settings) { l, rows, phones, s ->
                TasksUi(
                    loading = l.loading,
                    tasks = l.tasks,
                    error = l.error,
                    needsToken = l.authFailed || s.tokenRefused,
                    headphones = phones,
                    reminders = s.remindersEnabled,
                    waiting = rows.count { it.state == OutboxRow.STATE_PENDING },
                    refused = rows.filter { it.state == OutboxRow.STATE_PARKED },
                )
            }.stateIn(viewModelScope, SharingStarted.Eagerly, TasksUi())

        init {
            refresh()
        }

        fun refresh() {
            loaded.update { it.copy(loading = true, error = null) }
            scheduler.schedule()
            viewModelScope.launch {
                loaded.value =
                    when (val result = client.tasks()) {
                        is ApiResult.Ok -> Loaded(loading = false, tasks = result.value)
                        is ApiResult.Failure ->
                            Loaded(
                                loading = false,
                                error = result.message,
                                authFailed = result.kind in setOf(FailureKind.Unauthorized, FailureKind.Forbidden),
                            )
                    }
            }
        }

        fun setReminders(enabled: Boolean) {
            viewModelScope.launch {
                settings.update { it.copy(remindersEnabled = enabled) }
                reminders.apply(enabled)
            }
        }

        fun dismissRefused(clientId: String) {
            viewModelScope.launch { outbox.dismissParked(clientId) }
        }
    }
