package com.kezo.tide.player

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kezo.tide.api.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private const val MAX_RECENTS = 30

/**
 * Locally tracked playback history (TIDAL's API has no listening history for
 * this flow). Persisted in the tool's DataStore, newest first, deduplicated.
 */
object Recents {
    private val KEY = stringPreferencesKey("recentTracks")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Track.serializer())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var store: DataStore<Preferences>? = null

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    fun init(dataStore: DataStore<Preferences>) {
        if (store != null) return
        store = dataStore
        scope.launch {
            try {
                val raw = dataStore.data.first()[KEY] ?: return@launch
                _tracks.value = json.decodeFromString(serializer, raw)
            } catch (_: Exception) {
                // corrupt or stale history is not worth surfacing
            }
        }
    }

    fun record(track: Track) {
        _tracks.value = (listOf(track) + _tracks.value.filter { it.id != track.id })
            .take(MAX_RECENTS)
        persist()
    }

    fun clear() {
        _tracks.value = emptyList()
        persist()
    }

    private fun persist() {
        val snapshot = _tracks.value
        scope.launch {
            try {
                store?.edit { it[KEY] = json.encodeToString(serializer, snapshot) }
            } catch (_: Exception) {
                // history is best-effort
            }
        }
    }
}
