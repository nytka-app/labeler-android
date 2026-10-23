package io.github.nytka_labeler.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nytka_labeler.api.ApiResult
import io.github.nytka_labeler.api.LabelingClient
import io.github.nytka_labeler.api.PendingVoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VoiceUi(
    val loading: Boolean = true,
    val items: List<PendingVoice> = emptyList(),
    /** Label ids ticked for confirming; all of them at first. */
    val checked: Set<Long> = emptySet(),
    val error: String? = null,
    val busy: Boolean = false,
    /** The server's `learned` after a confirm. */
    val learned: Int? = null,
)

@HiltViewModel
class VoiceViewModel
    @Inject
    constructor(
        private val client: LabelingClient,
    ) : ViewModel() {
        private val state = MutableStateFlow(VoiceUi())
        val ui: StateFlow<VoiceUi> = state.asStateFlow()

        init {
            refresh()
        }

        fun refresh() {
            state.update { it.copy(loading = true, error = null) }
            viewModelScope.launch {
                when (val result = client.voicePending()) {
                    is ApiResult.Ok ->
                        state.update {
                            it.copy(
                                loading = false,
                                items = result.value,
                                checked =
                                    result.value
                                        .map { p ->
                                            p.labelId
                                        }.toSet(),
                            )
                        }
                    is ApiResult.Failure -> state.update { it.copy(loading = false, error = result.message) }
                }
            }
        }

        fun toggle(labelId: Long) =
            state.update {
                it.copy(
                    checked =
                        if (labelId in
                            it.checked
                        ) {
                            it.checked - labelId
                        } else {
                            it.checked + labelId
                        },
                )
            }

        fun confirm() {
            val s = state.value
            val ids = s.items.map { it.labelId }.filter { it in s.checked }
            if (ids.isEmpty() || s.busy) return
            state.update { it.copy(busy = true, error = null) }
            viewModelScope.launch {
                when (val result = client.voiceConfirm(ids)) {
                    is ApiResult.Ok ->
                        state.update {
                            it.copy(
                                busy = false,
                                learned = result.value,
                                items = it.items.filter { p -> p.labelId !in ids },
                                checked = it.checked - ids.toSet(),
                            )
                        }
                    is ApiResult.Failure -> state.update { it.copy(busy = false, error = result.message) }
                }
            }
        }
    }
