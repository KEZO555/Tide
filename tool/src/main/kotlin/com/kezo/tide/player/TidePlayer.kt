package com.kezo.tide.player

import com.kezo.tide.TidePrefs
import com.kezo.tide.api.Tidal
import com.kezo.tide.api.Track
import com.kezo.tide.ui.PlayerPresence
import com.thelightphone.sdk.LightAppState
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightMediaMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class RepeatMode { OFF, ALL, ONE }

/**
 * Queue + playback engine on top of the Light SDK's detached audio player
 * ([LightAudioPlayer] with [LightAudioPlayback.Detached]).
 *
 * The SDK owns a foreground [com.thelightphone.sdk.audio.LightAudioService], so
 * playback (and auto-advance to the next track) keeps going while Tide is
 * backgrounded or the screen is off, and the system media notification / lock
 * screen mirror what's playing. Tide keeps its own queue, shuffle, repeat and
 * offline logic here and drives the SDK player one track at a time, resolving
 * each track's source lazily (a local download when present, otherwise a stream
 * URL) exactly as before.
 *
 * Requires `capabilities = ["detached-audio"]` in `lighttool.toml`.
 */
object TidePlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var sealedActivity: SealedLightActivity? = null
    private var player: LightAudioPlayer? = null
    private var loadJob: Job? = null
    private var generation = 0

    /** The generation currently playing (armed once the SDK reports playing). */
    private var armedGen = -1
    /** The generation we've already ended/failed, so it can't advance twice. */
    private var terminalGen = -1

    /** Consecutive load/playback failures, so auto-skip can't loop forever. */
    private var consecutiveErrors = 0

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

    private var originalQueue: List<Track> = emptyList()

    /** A track is "finished" when the SDK stops playing within this of its end. */
    private const val END_EPS_MS = 1500L

    /**
     * Give the player the SDK handle it needs to build the detached audio
     * player. Called from the root screen; safe to call more than once.
     */
    fun attach(activity: SealedLightActivity) {
        sealedActivity = activity
    }

    /**
     * Lazily build the one detached SDK player for the process and wire its
     * state back into our flows. Returns null if we have no SDK handle yet or
     * the detached-audio capability is missing.
     */
    private fun ensurePlayer(): LightAudioPlayer? {
        player?.let { return it }
        val sealed = sealedActivity ?: return null
        val p = try {
            DefaultLightAudio(sealed).newPlayer(playback = LightAudioPlayback.Detached)
        } catch (e: Throwable) {
            _error.value = e.message ?: "couldn't start audio"
            return null
        }
        player = p
        observePlayer(p)
        return p
    }

    private fun observePlayer(p: LightAudioPlayer) {
        // Playing state drives our flag and, on a natural end, auto-advance.
        // This is event-driven (not polled), so it fires even while backgrounded.
        scope.launch {
            p.isPlaying.collect { playing ->
                if (playing) {
                    _isPlaying.value = true
                    _isLoading.value = false
                    consecutiveErrors = 0
                    armedGen = generation
                } else {
                    _isPlaying.value = false
                    val g = generation
                    if (armedGen == g && terminalGen != g) {
                        val dur = p.durationMs.value
                        val pos = p.positionMs.value
                        if (dur > 0 && pos >= dur - END_EPS_MS) {
                            terminalGen = g
                            onTrackEnded()
                        }
                    }
                }
            }
        }
        // Mirror position only while a player screen is visible and foregrounded,
        // so we don't churn UI state during background playback.
        scope.launch {
            combine(PlayerPresence.openCountFlow, LightAppState.foreground) { open, fg ->
                open > 0 && fg
            }.collectLatest { visible ->
                if (!visible) return@collectLatest
                p.positionMs.collect { _positionMs.value = it.toInt() }
            }
        }
        scope.launch {
            p.durationMs.collect { if (it > 0) _durationMs.value = it.toInt() }
        }
        scope.launch {
            p.error.collect { err ->
                val g = generation
                if (err != null && terminalGen != g) {
                    terminalGen = g
                    failTrack(err.diagnostic)
                }
            }
        }
    }

    fun play(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        consecutiveErrors = 0
        originalQueue = tracks
        val ordered = if (_shuffle.value) {
            listOf(tracks[startIndex]) + (tracks - tracks[startIndex]).shuffled()
        } else {
            tracks
        }
        _queue.value = ordered
        startTrack(if (_shuffle.value) 0 else startIndex)
    }

    /** Plays a list sequentially from the top (shuffle forced off). */
    fun playInOrder(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        _shuffle.value = false
        play(tracks, 0)
    }

    /** Turns shuffle on and plays the list from a random starting track. */
    fun playShuffled(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        _shuffle.value = true
        play(tracks, tracks.indices.random())
    }

    fun jumpTo(queueIndex: Int) {
        if (queueIndex in _queue.value.indices) {
            consecutiveErrors = 0
            startTrack(queueIndex)
        }
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
        Recents.record(track)

        loadJob = scope.launch {
            // Prefer an offline download; only hit the network when there isn't one.
            val local = Downloads.localPath(track.id)
            val source = if (local != null) {
                LightAudioSource.FileSource(File(local))
            } else if (TidePrefs.offlineEffective.value) {
                // Offline (manual toggle or no connection): never stream — skip
                // toward a downloaded track.
                if (gen == generation) failTrack("Offline — not downloaded")
                return@launch
            } else {
                val url = try {
                    withContext(Dispatchers.IO) { Tidal.streamUrl(track.id) }
                } catch (e: Exception) {
                    if (gen == generation) failTrack(e.message ?: "couldn't load track")
                    return@launch
                }
                LightAudioSource.UrlSource(url)
            }
            if (gen != generation) return@launch
            val p = ensurePlayer()
            if (p == null) {
                if (gen == generation) failTrack("audio unavailable")
                return@launch
            }
            val item = LightAudioItem(
                source = source,
                metadata = LightMediaMetadata(
                    title = track.title,
                    artist = track.artist,
                    album = track.albumTitle,
                    durationMs = (track.durationSec * 1000L).takeIf { it > 0 },
                ),
            )
            p.setMediaQueue(listOf(item))
            p.play()
        }
    }

    /**
     * A track failed to load or play. Skip to the next one so a single bad
     * track (region-locked, transient error, or not downloaded while offline)
     * doesn't stall playback — bounded so an all-failing queue can't loop.
     */
    private fun failTrack(message: String) {
        consecutiveErrors++
        val q = _queue.value
        if (q.size > 1 && consecutiveErrors < q.size) {
            next()
        } else {
            consecutiveErrors = 0
            _isLoading.value = false
            _isPlaying.value = false
            _error.value = message
        }
    }

    fun toggle() {
        val p = player ?: return
        if (_isPlaying.value) {
            p.pause()
        } else if (_current.value != null) {
            p.play()
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
                player?.pause()
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
        val clamped = ms.coerceIn(0, _durationMs.value)
        p.seekTo(clamped.toLong())
        _positionMs.value = clamped
    }

    fun seekToFraction(fraction: Float) {
        seekTo((fraction.coerceIn(0f, 1f) * _durationMs.value).toInt())
    }

    /** Seek relative to the current position (e.g. -15s / +15s), clamped. */
    fun seekBy(deltaMs: Int) {
        seekTo((_positionMs.value + deltaMs).coerceIn(0, _durationMs.value))
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
        player?.pause()
        _isPlaying.value = false
    }

    fun stop() {
        generation++
        loadJob?.cancel()
        player?.stop()
        _queue.value = emptyList()
        _index.value = -1
        _current.value = null
        _isPlaying.value = false
        _isLoading.value = false
        _positionMs.value = 0
        _durationMs.value = 0
    }
}
