package com.kezo.tide.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.kezo.tide.api.Track
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** Height (in LightOS grid units) of every uniform list row in the tool. */
const val ROW_UNITS = 3f

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Data<T>(val value: T) : UiState<T>
    data class Failed(val message: String) : UiState<Nothing>
}

/** Standard screen chrome: LightOS theme + background fill. */
@Composable
fun TideScreen(content: @Composable ColumnScope.() -> Unit) {
    val themeColors by LightThemeController.colors.collectAsState()
    LightTheme(colors = themeColors) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
            content = content,
        )
    }
}

@Composable
fun LoadingText(modifier: Modifier = Modifier) {
    LightText(
        text = "loading…",
        variant = LightTextVariant.Copy,
        lighten = true,
        modifier = modifier.padding(horizontal = 1f.gridUnitsAsDp()),
    )
}

@Composable
fun ErrorRetry(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
        LightText(text = message, variant = LightTextVariant.Paragraph, lighten = true)
        LightText(
            text = "retry",
            variant = LightTextVariant.Copy,
            underline = true,
            modifier = Modifier
                .padding(top = 0.5f.gridUnitsAsDp())
                .lightClickable(onClick = onRetry),
        )
    }
}

@Composable
fun EmptyText(text: String, modifier: Modifier = Modifier) {
    LightText(
        text = text,
        variant = LightTextVariant.Copy,
        lighten = true,
        modifier = modifier.padding(horizontal = 1f.gridUnitsAsDp()),
    )
}

/** Two-line list row sized for LightLazyScrollView's uniform item height. */
@Composable
fun TwoLineRow(
    primary: String,
    secondary: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_UNITS.gridUnitsAsDp())
            .lightClickable(onClick = onClick)
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        LightText(
            text = if (active) "· $primary" else primary,
            variant = LightTextVariant.Fine,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (secondary.isNotBlank()) {
            LightText(
                text = secondary,
                variant = LightTextVariant.Superfine,
                lighten = true,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun TrackRow(track: Track, onClick: () -> Unit, active: Boolean = false) {
    TwoLineRow(
        primary = track.title,
        secondary = listOf(track.artist, formatTime(track.durationSec * 1000))
            .filter { it.isNotBlank() }
            .joinToString("  ·  "),
        onClick = onClick,
        active = active,
    )
}

@Composable
fun SectionHeader(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        lighten = true,
        modifier = Modifier.padding(
            start = 1f.gridUnitsAsDp(),
            top = 1f.gridUnitsAsDp(),
            bottom = 0.25f.gridUnitsAsDp(),
        ),
    )
}

/** LightOS-style dotted progress line; tap to seek when [onSeekFraction] is set. */
@Composable
fun DottedProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    onSeekFraction: ((Float) -> Unit)? = null,
) {
    val colors = LightThemeTokens.colors
    val filledColor = colors.content
    val emptyColor = colors.contentSecondary.copy(alpha = 0.4f)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(1.5f.gridUnitsAsDp())
            .then(
                if (onSeekFraction != null) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures { offset ->
                            onSeekFraction(offset.x / size.width.toFloat())
                        }
                    }
                } else {
                    Modifier
                }
            ),
    ) {
        val dots = 25
        val radius = size.height * 0.10f
        val step = size.width / (dots - 1)
        for (i in 0 until dots) {
            val filled = i.toFloat() / (dots - 1) <= fraction.coerceIn(0f, 1f)
            drawCircle(
                color = if (filled) filledColor else emptyColor,
                radius = if (filled) radius else radius * 0.7f,
                center = Offset(i * step, size.height / 2f),
            )
        }
    }
}

@Composable
fun CenteredTimeRow(positionMs: Int, durationMs: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = formatTime(positionMs),
            variant = LightTextVariant.Superfine,
            lighten = true,
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
        LightText(
            text = formatTime(durationMs),
            variant = LightTextVariant.Superfine,
            lighten = true,
            align = TextAlign.End,
        )
    }
}

fun formatTime(ms: Int): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "$m:" + s.toString().padStart(2, '0')
}
