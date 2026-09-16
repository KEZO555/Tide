package com.thelightphone.sdk

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the tool's UI is currently in the foreground (its [LightActivity] is
 * resumed). Tools can observe [foreground] to pause work that only matters while
 * the user is looking — e.g. UI polling — so playback with the screen off stays
 * idle. Updated by [LightActivity]'s resume/pause; defaults to true.
 */
object LightAppState {
    private val _foreground = MutableStateFlow(true)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    internal fun setForeground(value: Boolean) {
        _foreground.value = value
    }
}
