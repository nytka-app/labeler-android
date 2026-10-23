package io.github.nytka_labeler.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nytka_labeler.api.ServerUrl
import io.github.nytka_labeler.api.UrlCheck
import io.github.nytka_labeler.outbox.SendScheduler
import io.github.nytka_labeler.settings.SettingsSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SetupUi(
    val url: String = "",
    val token: String = "",
    val privateNetwork: Boolean = false,
    val tokenSaved: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
)

@HiltViewModel
class SetupViewModel
    @Inject
    constructor(
        private val settings: SettingsSource,
        private val scheduler: SendScheduler,
    ) : ViewModel() {
        private val state = MutableStateFlow(SetupUi())
        val ui: StateFlow<SetupUi> = state.asStateFlow()

        init {
            viewModelScope.launch {
                val current = settings.current()
                state.update {
                    it.copy(
                        url = current.serverUrl,
                        privateNetwork = current.privateNetwork,
                        tokenSaved = current.token.isNotEmpty(),
                    )
                }
            }
        }

        fun setUrl(text: String) = state.update { it.copy(url = text, error = null) }

        fun setToken(text: String) = state.update { it.copy(token = text, error = null) }

        fun setPrivateNetwork(on: Boolean) = state.update { it.copy(privateNetwork = on, error = null) }

        /** Saves the address and, when one was typed, the token. A new token clears the "refused" stop. */
        fun save() {
            val s = state.value
            when (val check = ServerUrl.check(s.url, s.privateNetwork)) {
                is UrlCheck.Invalid -> state.update { it.copy(error = check.reason) }
                is UrlCheck.Ok -> {
                    if (s.token.isBlank() && !s.tokenSaved) {
                        state.update { it.copy(error = "Paste a token of the labeling scope.") }
                        return
                    }
                    viewModelScope.launch {
                        settings.update {
                            it.copy(
                                serverUrl = check.base.toString(),
                                privateNetwork = s.privateNetwork,
                                token = s.token.trim().ifEmpty { it.token },
                                tokenRefused = if (s.token.isBlank()) it.tokenRefused else false,
                            )
                        }
                        scheduler.schedule()
                        state.update { it.copy(token = "", tokenSaved = true, done = true) }
                    }
                }
            }
        }
    }
