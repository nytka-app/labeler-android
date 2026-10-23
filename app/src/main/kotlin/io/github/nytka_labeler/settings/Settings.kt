package io.github.nytka_labeler.settings

import kotlinx.coroutines.flow.Flow

data class Settings(
    val serverUrl: String = "",
    val token: String = "",
    val privateNetwork: Boolean = false,
    val remindersEnabled: Boolean = true,
    /** The local date (ISO) of the last session the owner answered in; the reminder stays quiet that day. */
    val lastSessionDay: String = "",
    /** Set when the server refused the token (401 or 403); the outbox stops until a new one is saved. */
    val tokenRefused: Boolean = false,
) {
    val configured: Boolean get() = serverUrl.isNotBlank() && token.isNotEmpty()

    /** Never prints the token. */
    override fun toString() =
        "Settings(serverUrl=<set:${serverUrl.isNotBlank()}>, token=<set:${token.isNotEmpty()}>, " +
            "privateNetwork=$privateNetwork, remindersEnabled=$remindersEnabled, tokenRefused=$tokenRefused)"
}

interface SettingsSource {
    val settings: Flow<Settings>

    suspend fun current(): Settings

    suspend fun update(transform: (Settings) -> Settings)
}
