package com.kezo.tide.player

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kezo.tide.api.Tidal
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
import java.io.File

/** A downloaded track plus the on-disk size of its audio file. */
@kotlinx.serialization.Serializable
data class DownloadedTrack(val track: Track, val bytes: Long)

/**
 * Offline downloads. Audio files live in the tool's private files directory
 * (`filesDir/downloads/<trackId>`), and a manifest of what's downloaded is
 * persisted in DataStore. The player prefers a local file when one exists.
 */
object Downloads {
    private val KEY = stringPreferencesKey("downloads")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(DownloadedTrack.serializer())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var store: DataStore<Preferences>? = null
    private var dir: File? = null

    private val _items = MutableStateFlow<List<DownloadedTrack>>(emptyList())
    val items: StateFlow<List<DownloadedTrack>> = _items.asStateFlow()

    private val _downloadedIds = MutableStateFlow<Set<Long>>(emptySet())
    val downloadedIds: StateFlow<Set<Long>> = _downloadedIds.asStateFlow()

    private val _downloadingIds = MutableStateFlow<Set<Long>>(emptySet())
    val downloadingIds: StateFlow<Set<Long>> = _downloadingIds.asStateFlow()

    /** trackId -> download progress fraction (0..1) while downloading. */
    private val _progress = MutableStateFlow<Map<Long, Float>>(emptyMap())
    val progress: StateFlow<Map<Long, Float>> = _progress.asStateFlow()

    fun init(dataStore: DataStore<Preferences>, filesDir: File) {
        if (store != null) return
        store = dataStore
        dir = File(filesDir, "downloads").apply { runCatching { mkdirs() } }
        scope.launch {
            try {
                val raw = dataStore.data.first()[KEY] ?: return@launch
                val saved = json.decodeFromString(serializer, raw)
                // drop any entries whose file has gone missing
                val valid = saved.filter { fileFor(it.track.id)?.exists() == true }
                _items.value = valid
                _downloadedIds.value = valid.map { it.track.id }.toSet()
                if (valid.size != saved.size) persist()
            } catch (_: Exception) {
                // corrupt manifest is not worth surfacing
            }
        }
    }

    private fun fileFor(trackId: Long): File? = dir?.let { File(it, "$trackId") }

    /**
     * Absolute path to a track's downloaded file, or null if not downloaded.
     * Guarded by the manifest so a partial/interrupted file is never played.
     */
    fun localPath(trackId: Long): String? =
        if (trackId in _downloadedIds.value) {
            fileFor(trackId)?.takeIf { it.exists() }?.absolutePath
        } else {
            null
        }

    /** Downloads one track (no-op if already downloaded or in progress). */
    fun download(track: Track) {
        if (track.id in _downloadedIds.value || track.id in _downloadingIds.value) return
        _downloadingIds.value = _downloadingIds.value + track.id
        scope.launch {
            fetch(track)
            _downloadingIds.value = _downloadingIds.value - track.id
        }
    }

    /** Downloads a list of tracks sequentially. */
    fun downloadAll(tracks: List<Track>) {
        val pending = tracks.filter { it.id !in _downloadedIds.value && it.id !in _downloadingIds.value }
        if (pending.isEmpty()) return
        _downloadingIds.value = _downloadingIds.value + pending.map { it.id }
        scope.launch {
            for (t in pending) {
                fetch(t)
                _downloadingIds.value = _downloadingIds.value - t.id
            }
        }
    }

    private suspend fun fetch(track: Track) {
        val file = fileFor(track.id) ?: return
        setProgress(track.id, 0f)
        try {
            val bytes = Tidal.downloadTrackTo(track.id, file) { written, total ->
                if (total > 0) setProgress(track.id, (written.toFloat() / total).coerceIn(0f, 1f))
            }
            _items.value = _items.value.filter { it.track.id != track.id } +
                DownloadedTrack(track, bytes)
            _downloadedIds.value = _downloadedIds.value + track.id
            persist()
        } catch (_: Exception) {
            runCatching { file.delete() }
        } finally {
            clearProgress(track.id)
        }
    }

    /** Updates progress, but only on ~1% steps to avoid churning the flow. */
    private fun setProgress(id: Long, fraction: Float) {
        val current = _progress.value[id]
        if (current != null && fraction < 1f && kotlin.math.abs(current - fraction) < 0.01f) return
        _progress.value = _progress.value + (id to fraction)
    }

    private fun clearProgress(id: Long) {
        if (id in _progress.value) _progress.value = _progress.value - id
    }

    fun remove(trackId: Long) {
        scope.launch {
            runCatching { fileFor(trackId)?.delete() }
            _items.value = _items.value.filter { it.track.id != trackId }
            _downloadedIds.value = _downloadedIds.value - trackId
            persist()
        }
    }

    /** Removes every track in [tracks] that is downloaded. */
    fun removeThese(tracks: List<Track>) {
        val ids = tracks.map { it.id }.toSet()
        scope.launch {
            ids.forEach { runCatching { fileFor(it)?.delete() } }
            _items.value = _items.value.filter { it.track.id !in ids }
            _downloadedIds.value = _downloadedIds.value - ids
            persist()
        }
    }

    fun clear() {
        scope.launch {
            _items.value.forEach { runCatching { fileFor(it.track.id)?.delete() } }
            _items.value = emptyList()
            _downloadedIds.value = emptySet()
            _progress.value = emptyMap()
            persist()
        }
    }

    private fun persist() {
        val snapshot = _items.value
        scope.launch {
            try {
                store?.edit { it[KEY] = json.encodeToString(serializer, snapshot) }
            } catch (_: Exception) {
                // best-effort
            }
        }
    }
}
