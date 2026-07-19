package com.kezo.tide.player

import android.media.AudioAttributes
import android.media.MediaPlayer
import com.kezo.tide.api.Tidal
import com.kezo.tide.api.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RepeatMode { OFF, ALL, ONE }

/**
 * Queue + playback engine on top of [android.media.MediaPlayer].
 *
 * The Light SDK currently has no background-audio service API, so playback
 * lives with the tool process: it keeps playing while the tool is open and
 * pauses when LightOS pauses the tool.
 */
object TidePlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var player: MediaPlayer? = null
    private var loadJob: Job? = null
    private var generation = 0
    private var prepared = false

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index.asStateFlow()

    private val _current = MutableStateFlow<Track?>(null)
    val current: StateFlow<Track?> = _current.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _positionMs = MutableStateFlow(0)
    val positionMs: StateFlow<Int> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0)
    val durationMs: StateFlow<Int> = _durationMs.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _repeat = MutableStateFlow(RepeatMode.OFF)
    val repeat: StateFlow<RepeatMode> = _repeat.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    /** In-app volume multiplier (0..1); hardware keys still set system volume. */
    fun setVolume(value: Float) {
        val v = value.coerceIn(0f, 1f)
        _volume.value = v
        runCatching { player?.setVolume(v, v) }
    }

    private var originalQueue: List<Track> = emptyList()

    init {
        scope.launch {
            while (isActive) {
                val p = player
                if (p != null && prepared && _isPlaying.value) {
                    runCatching { _positionMs.value = p.currentPosition }
                }
                delay(500)
            }
        }
    }

    private fun ensurePlayer(): MediaPlayer {
        player?.let { return it }
        val p = MediaPlayer()
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        p.setOnCompletionListener { onTrackEnded() }
        p.setOnErrorListener { _, _, _ ->
            _error.value = "playback error"
            _isPlaying.value = false
            _isLoading.value = false
            true
        }
        player = p
        return p
    }

    fun play(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        originalQueue = tracks
        val ordered = if (_shuffle.value) {
            listOf(tracks[startIndex]) + (tracks - tracks[startIndex]).shuffled()
        } else {
            tracks
        }
        _queue.value = ordered
        startTrack(if (_shuffle.value) 0 else startIndex)
    }

    fun jumpTo(queueIndex: Int) {
        if (queueIndex in _queue.value.indices) startTrack(queueIndex)
    }

    /** Appends a track to the end of the queue (starts playback when idle). */
    fun addToQueue(track: Track) {
        if (_queue.value.isEmpty()) {
            play(listOf(track), 0)
            return
        }
        _queue.value = _queue.value + track
        originalQueue = originalQueue + track
    }

    /** Inserts a track right after the one currently playing. */
    fun playNext(track: Track) {
        if (_queue.value.isEmpty()) {
            play(listOf(track), 0)
            return
        }
        val q = _queue.value.toMutableList()
        q.add((_index.value + 1).coerceIn(0, q.size), track)
        _queue.value = q
        originalQueue = originalQueue + track
    }

    /** Removes the entry at [queueIndex]; the playing entry can't be removed. */
    fun removeFromQueue(queueIndex: Int) {
        val q = _queue.value.toMutableList()
        if (queueIndex !in q.indices || queueIndex == _index.value) return
        q.removeAt(queueIndex)
        _queue.value = q
        if (queueIndex < _index.value) _index.value = _index.value - 1
    }

    /** Moves a queue entry from one position to another, keeping playback stable. */
    fun moveInQueue(from: Int, to: Int) {
        val q = _queue.value.toMutableList()
        if (from !in q.indices || to !in q.indices || from == to) return
        val item = q.removeAt(from)
        q.add(to, item)
        _queue.value = q
        val cur = _index.value
        _index.value = when {
            from == cur -> to
            from < cur && to >= cur -> cur - 1
            from > cur && to <= cur -> cur + 1
            else -> cur
        }
    }

    /** Drops everything except the currently playing track. */
    fun clearUpcoming() {
        val cur = _current.value ?: return
        _queue.value = listOf(cur)
        originalQueue = listOf(cur)
        _index.value = 0
    }

    private fun startTrack(i: Int) {
        val track = _queue.value.getOrNull(i) ?: return
        val gen = ++generation
        loadJob?.cancel()
        _index.value = i
        _current.value = track
        _positionMs.value = 0
        _durationMs.value = track.durationSec * 1000
        _isLoading.value = true
        _isPlaying.value = false
        _error.value = null
        prepared = false
        Recents.record(track)

        loadJob = scope.launch {
            val url = try {
                withContext(Dispatchers.IO) { Tidal.streamUrl(track.id) }
            } catch (e: Exception) {
                if (gen == generation) {
                    _isLoading.value = false
                    _error.value = e.message ?: "couldn't load track"
                }
                return@launch
            }
            if (gen != generation) return@launch
            val p = ensurePlayer()
            runCatching {
                p.reset()
                p.setDataSource(url)
                p.setOnPreparedListener {
                    if (gen != generation) return@setOnPreparedListener
                    prepared = true
                    _durationMs.value = if (it.duration > 0) it.duration else track.durationSec * 1000
                    it.setVolume(_volume.value, _volume.value)
                    it.start()
                    _isLoading.value = false
                    _isPlaying.value = true
                }
                p.prepareAsync()
            }.onFailure {
                _isLoading.value = false
                _error.value = "couldn't start playback"
            }
        }
    }

    fun toggle() {
        val p = player ?: return
        if (!prepared) return
        runCatching {
            if (p.isPlaying) {
                p.pause()
                _isPlaying.value = false
            } else {
                p.start()
                _isPlaying.value = true
            }
        }
    }

    fun next() {
        val q = _queue.value
        if (q.isEmpty()) return
        val nextIndex = _index.value + 1
        when {
            nextIndex < q.size -> startTrack(nextIndex)
            _repeat.value == RepeatMode.ALL -> startTrack(0)
            else -> {
                runCatching { player?.pause() }
                _isPlaying.value = false
            }
        }
    }

    fun previous() {
        if (_positionMs.value > 3000 || _index.value <= 0) {
            seekTo(0)
        } else {
            startTrack(_index.value - 1)
        }
    }

    fun seekTo(ms: Int) {
        val p = player ?: return
        if (!prepared) return
        runCatching {
            p.seekTo(ms.coerceIn(0, _durationMs.value))
            _positionMs.value = ms.coerceIn(0, _durationMs.value)
        }
    }

    fun seekToFraction(fraction: Float) {
        seekTo((fraction.coerceIn(0f, 1f) * _durationMs.value).toInt())
    }

    private fun onTrackEnded() {
        when (_repeat.value) {
            RepeatMode.ONE -> startTrack(_index.value)
            else -> next()
        }
    }

    fun toggleShuffle() {
        val turningOn = !_shuffle.value
        _shuffle.value = turningOn
        val cur = _current.value ?: return
        if (turningOn) {
            _queue.value = listOf(cur) + (_queue.value - cur).shuffled()
            _index.value = 0
        } else if (originalQueue.isNotEmpty()) {
            _queue.value = originalQueue
            _index.value = originalQueue.indexOfFirst { it.id == cur.id }.coerceAtLeast(0)
        }
    }

    fun cycleRepeat() {
        _repeat.value = when (_repeat.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    }

    fun pause() {
        runCatching { if (player?.isPlaying == true) player?.pause() }
        _isPlaying.value = false
    }

    fun stop() {
        generation++
        loadJob?.cancel()
        runCatching { player?.reset() }
        prepared = false
        _queue.value = emptyList()
        _index.value = -1
        _current.value = null
        _isPlaying.value = false
        _isLoading.value = false
        _positionMs.value = 0
        _durationMs.value = 0
    }
}
