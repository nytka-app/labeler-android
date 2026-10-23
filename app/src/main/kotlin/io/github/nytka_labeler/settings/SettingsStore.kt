package io.github.nytka_labeler.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class SettingsStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher,
) : SettingsSource {
    // Decrypting the token calls the Keystore: keep it off the collector's (often main) thread.
    override val settings: Flow<Settings> = dataStore.data.map(::read).flowOn(Dispatchers.IO)

    override suspend fun current(): Settings = settings.first()

    override suspend fun update(transform: (Settings) -> Settings) {
        dataStore.edit { preferences ->
            val current = read(preferences)
            val next = transform(current)
            preferences[SERVER_URL] = next.serverUrl
            // Touch the stored token only when it changes: a token the Keystore cannot read right now
            // reads as "", and an unrelated update must not erase it.
            if (next.token != current.token) {
                if (next.token.isEmpty()) preferences.remove(TOKEN) else preferences[TOKEN] = cipher.encrypt(next.token)
            }
            preferences[PRIVATE_NETWORK] = next.privateNetwork
            preferences[REMINDERS] = next.remindersEnabled
            preferences[LAST_SESSION_DAY] = next.lastSessionDay
            preferences[TOKEN_REFUSED] = next.tokenRefused
        }
    }

    private fun read(preferences: Preferences) =
        Settings(
            serverUrl = preferences[SERVER_URL] ?: "",
            token = preferences[TOKEN]?.let(cipher::decrypt) ?: "",
            privateNetwork = preferences[PRIVATE_NETWORK] ?: false,
            remindersEnabled = preferences[REMINDERS] ?: true,
            lastSessionDay = preferences[LAST_SESSION_DAY] ?: "",
            tokenRefused = preferences[TOKEN_REFUSED] ?: false,
        )

    companion object {
        private val SERVER_URL = stringPreferencesKey("server_url")
        private val TOKEN = stringPreferencesKey("token")
        private val PRIVATE_NETWORK = booleanPreferencesKey("private_network")
        private val REMINDERS = booleanPreferencesKey("reminders")
        private val LAST_SESSION_DAY = stringPreferencesKey("last_session_day")
        private val TOKEN_REFUSED = booleanPreferencesKey("token_refused")

        fun create(
            context: Context,
            cipher: TokenCipher = KeystoreTokenCipher(),
        ) = SettingsStore(
            PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("settings") }),
            cipher,
        )
    }
}
