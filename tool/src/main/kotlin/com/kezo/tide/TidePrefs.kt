package com.kezo.tide

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One customizable entry: a stable id plus whether it is shown. */
data class SectionPref(val id: String, val enabled: Boolean)

/**
 * User layout preferences: which Home sections and navigation tabs are shown,
 * and in what order. Persisted as simple "id:1,id:0" strings.
 */
object TidePrefs {
    private val KEY_HOME = stringPreferencesKey("homeSections")
    private val KEY_NAV = stringPreferencesKey("navTabs")
    private val KEY_OFFLINE = booleanPreferencesKey("offlineMode")
    private val KEY_ART_PLAYER = booleanPreferencesKey("artworkNowPlaying")
    private val KEY_ART_THUMBS = booleanPreferencesKey("artworkThumbnails")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val DEFAULT_HOME = listOf(
        SectionPref("recents", true),
        SectionPref("artists", true),
        SectionPref("mixes", true),
        SectionPref("releases", true),
    )
    val DEFAULT_NAV = listOf(
        SectionPref("home", true),
        SectionPref("liked", true),
        SectionPref("albums", true),
        SectionPref("playlists", true),
        SectionPref("downloads", true),
        SectionPref("search", true),
        SectionPref("settings", true),
    )

    /** These stay enabled no matter what, so the user can't lock themselves out. */
    val LOCKED_NAV = setOf("home", "settings")

    private var store: DataStore<Preferences>? = null

    private val _homeSections = MutableStateFlow(DEFAULT_HOME)
    val homeSections: StateFlow<List<SectionPref>> = _homeSections.asStateFlow()

    private val _navTabs = MutableStateFlow(DEFAULT_NAV)
    val navTabs: StateFlow<List<SectionPref>> = _navTabs.asStateFlow()

    private val _offlineMode = MutableStateFlow(false)
    val offlineMode: StateFlow<Boolean> = _offlineMode.asStateFlow()

    private val _artworkNowPlaying = MutableStateFlow(false)
    val artworkNowPlaying: StateFlow<Boolean> = _artworkNowPlaying.asStateFlow()

    private val _artworkThumbnails = MutableStateFlow(false)
    val artworkThumbnails: StateFlow<Boolean> = _artworkThumbnails.asStateFlow()

    fun init(dataStore: DataStore<Preferences>) {
        if (store != null) return
        store = dataStore
        scope.launch {
            try {
                val p = dataStore.data.first()
                p[KEY_HOME]?.let { _homeSections.value = decode(it, DEFAULT_HOME) }
                p[KEY_NAV]?.let { _navTabs.value = decode(it, DEFAULT_NAV) }
                p[KEY_OFFLINE]?.let { _offlineMode.value = it }
                p[KEY_ART_PLAYER]?.let { _artworkNowPlaying.value = it }
                p[KEY_ART_THUMBS]?.let { _artworkThumbnails.value = it }
            } catch (_: Exception) {
                // defaults are fine
            }
        }
    }

    fun setOfflineMode(enabled: Boolean) {
        _offlineMode.value = enabled
        scope.launch {
            try {
                store?.edit { it[KEY_OFFLINE] = enabled }
            } catch (_: Exception) {
                // best-effort
            }
        }
    }

    fun setArtworkNowPlaying(enabled: Boolean) {
        _artworkNowPlaying.value = enabled
        scope.launch {
            try {
                store?.edit { it[KEY_ART_PLAYER] = enabled }
            } catch (_: Exception) {
                // best-effort
            }
        }
    }

    fun setArtworkThumbnails(enabled: Boolean) {
        _artworkThumbnails.value = enabled
        scope.launch {
            try {
                store?.edit { it[KEY_ART_THUMBS] = enabled }
            } catch (_: Exception) {
                // best-effort
            }
        }
    }

    fun setHomeSections(list: List<SectionPref>) {
        _homeSections.value = list
        persist(KEY_HOME, list)
    }

    fun setNavTabs(list: List<SectionPref>) {
        val safe = list.map { if (it.id in LOCKED_NAV) it.copy(enabled = true) else it }
        _navTabs.value = safe
        persist(KEY_NAV, safe)
    }

    private fun persist(key: Preferences.Key<String>, list: List<SectionPref>) {
        val encoded = list.joinToString(",") { "${it.id}:${if (it.enabled) 1 else 0}" }
        scope.launch {
            try {
                store?.edit { it[key] = encoded }
            } catch (_: Exception) {
                // layout prefs are best-effort
            }
        }
    }

    /**
     * Decodes a stored list, keeping defaults for ids that are missing or new.
     * A new default (e.g. a tab added in an update) is inserted at its natural
     * spot — right after its preceding default that the user still has — rather
     * than tacked on at the end.
     */
    private fun decode(raw: String, defaults: List<SectionPref>): List<SectionPref> {
        val stored = raw.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size == 2 && defaults.any { it.id == parts[0] }) {
                SectionPref(parts[0], parts[1] == "1")
            } else {
                null
            }
        }
        val result = stored.toMutableList()
        defaults.forEachIndexed { defIndex, d ->
            if (result.none { it.id == d.id }) {
                val prevId = defaults.subList(0, defIndex)
                    .lastOrNull { pd -> result.any { it.id == pd.id } }?.id
                val insertAt = if (prevId == null) 0
                    else result.indexOfFirst { it.id == prevId } + 1
                result.add(insertAt, d)
            }
        }
        return result
    }
}
