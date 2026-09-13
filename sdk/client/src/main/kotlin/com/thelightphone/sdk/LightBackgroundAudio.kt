package com.thelightphone.sdk

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SDK background-audio capability.
 *
 * A tool that declares `backgroundAudio = true` in `lighttool.toml` can call
 * [update] / [stop] to run the SDK's foreground media service
 * ([LightPlaybackService]). That service keeps the tool's process alive while
 * audio is playing, so playback keeps advancing (e.g. auto-play the next track)
 * even when the tool is in the background — which a plain tool process can't do,
 * because LightOS freezes it once it's no longer foreground.
 *
 * All Android framework access lives here in the SDK. Tool code only ever
 * touches the plain [NowPlaying] data class and the [Controller] callbacks, so
 * it stays within the tool sandbox.
 */
object LightBackgroundAudio {

    /** Snapshot of what's playing, mirrored into the media notification. */
    data class NowPlaying(
        val title: String,
        val artist: String,
        val isPlaying: Boolean,
    )

    /** Transport actions from the notification, forwarded back to the tool. */
    interface Controller {
        fun onTogglePlay()
        fun onNext()
        fun onPrevious()
    }

    private var appContext: Context? = null

    @Volatile
    private var controller: Controller? = null

    @Volatile
    private var started = false

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    internal val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    /** Wired up by [LightSdkApplication] at process start; tools never call this. */
    internal fun attachContext(context: Context) {
        appContext = context.applicationContext
    }

    /** Register the transport callbacks that power the notification controls. */
    fun configure(controller: Controller) {
        this.controller = controller
    }

    /**
     * Publish the current track and make sure the foreground service is running.
     * Safe to call on every track change and play/pause toggle — the service is
     * started once (from the foreground, on the first call) and only updated
     * afterwards.
     */
    fun update(nowPlaying: NowPlaying) {
        _nowPlaying.value = nowPlaying
        val ctx = appContext ?: return
        if (!started) {
            started = true
            runCatching {
                ContextCompat.startForegroundService(
                    ctx,
                    Intent(ctx, LightPlaybackService::class.java),
                )
            }
        }
    }

    /** Tear down background playback (queue finished or playback stopped). */
    fun stop() {
        started = false
        _nowPlaying.value = null
    }

    internal fun dispatchTogglePlay() {
        controller?.onTogglePlay()
    }

    internal fun dispatchNext() {
        controller?.onNext()
    }

    internal fun dispatchPrevious() {
        controller?.onPrevious()
    }

    internal fun markServiceDestroyed() {
        started = false
    }
}
