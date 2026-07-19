package com.kezo.tide.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.kezo.tide.R
import com.kezo.tide.api.Track
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTypography
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** Height (in LightOS grid units) of every uniform list row in the tool. */
const val ROW_UNITS = 3f

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Data<T>(val value: T) : UiState<T>
    data class Failed(val message: String) : UiState<Nothing>
}

/**
 * Echo uses Public Sans for every piece of text; same face, LightOS size table.
 */
@Composable
fun publicSansTypography(): LightTypography {
    return remember {
        val family = FontFamily(Font(R.font.publicsans_regular, FontWeight.Normal))
        fun style(size: Double, lineHeightFactor: Double, letterSpacingFactor: Double = 0.0) =
            TextStyle(
                fontSize = size.sp,
                fontFamily = family,
                fontWeight = FontWeight.Normal,
                lineHeight = (size * lineHeightFactor).sp,
                letterSpacing = if (letterSpacingFactor > 0) (size * letterSpacingFactor).sp else androidx.compose.ui.unit.TextUnit.Unspecified,
            )
        LightTypography(
            title = style(115.0, 1.10),
            subtitle = style(52.0, 1.20),
            heading = style(38.0, 1.35),
            subheading = style(30.0, 1.25, 0.03),
            copy = style(30.0, 1.50),
            button = style(30.0, 1.10, 0.15),
            paragraph = style(24.5, 1.25),
            paragraphWide = style(25.0, 1.30, 0.02),
            detail = style(20.0, 1.45),
            fine = style(25.0, 1.15, 0.03),
            superfine = style(16.0, 1.20),
            micro = style(8.0, 1.20),
        )
    }
}

/** Standard screen chrome: echo-style theme (Public Sans) + background fill. */
@Composable
fun TideScreen(content: @Composable ColumnScope.() -> Unit) {
    val themeColors by LightThemeController.colors.collectAsState()
    LightTheme(colors = themeColors, typography = publicSansTypography()) {
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
        text = "Loading...",
        variant = LightTextVariant.Fine,
        lighten = true,
        modifier = modifier.padding(horizontal = 1f.gridUnitsAsDp()),
    )
}

@Composable
fun ErrorRetry(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
        LightText(text = message, variant = LightTextVariant.Fine, lighten = true)
        LightText(
            text = "Retry",
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
        variant = LightTextVariant.Fine,
        lighten = true,
        modifier = modifier.padding(horizontal = 1f.gridUnitsAsDp()),
    )
}

/**
 * Echo's TrackListItem: "N." number column, track name, "artist · duration" below.
 */
@Composable
fun NumberedTrackRow(
    number: Int,
    track: Track,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_UNITS.gridUnitsAsDp())
            .lightClickable(onClick = onClick)
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = "$number.",
            variant = LightTextVariant.Fine,
            lighten = !active,
            align = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.width(2.4f.gridUnitsAsDp()),
        )
        Spacer(modifier = Modifier.width(0.5f.gridUnitsAsDp()))
        Column(modifier = Modifier.fillMaxWidth()) {
            LightText(
                text = track.title,
                variant = LightTextVariant.Fine,
                underline = active,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LightText(
                text = listOf(track.artist, formatTime(track.durationSec * 1000))
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                variant = LightTextVariant.Superfine,
                lighten = true,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Echo's MediaListItem: primary text with secondary line, no number column. */
@Composable
fun MediaRow(
    primary: String,
    secondary: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_UNITS.gridUnitsAsDp())
            .lightClickable(onClick = onClick)
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalArrangement = Arrangement.Center,
    ) {
        LightText(
            text = primary,
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

/** Echo's StyledButton: large text, underlined, in a full-width row. */
@Composable
fun TextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    LightText(
        text = text,
        variant = LightTextVariant.Copy,
        underline = true,
        modifier = modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.4f.gridUnitsAsDp()),
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

/**
 * Echo's progress bar: a thin full-width line with a thicker filled portion,
 * both in the content color. Tap to seek when [onSeekFraction] is set.
 */
@Composable
fun SolidProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    onSeekFraction: ((Float) -> Unit)? = null,
) {
    val color = LightThemeTokens.colors.content
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(1f.gridUnitsAsDp())
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
        val thin = size.height * 0.10f
        val thick = size.height * 0.28f
        val cy = size.height / 2f
        drawRect(
            color = color,
            topLeft = Offset(0f, cy - thin / 2f),
            size = Size(size.width, thin),
        )
        val filled = size.width * fraction.coerceIn(0f, 1f)
        if (filled > 0f) {
            drawRect(
                color = color,
                topLeft = Offset(0f, cy - thick / 2f),
                size = Size(filled, thick),
            )
        }
    }
}

@Composable
fun TimeRow(positionMs: Int, durationMs: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(text = formatTime(positionMs), variant = LightTextVariant.Superfine)
        Spacer(modifier = Modifier.weight(1f))
        LightText(
            text = formatTime(durationMs),
            variant = LightTextVariant.Superfine,
            align = TextAlign.End,
        )
    }
}

/**
 * Echo's Navbar: a row of icon tabs, space-between, active icon at full
 * strength and inactive ones dimmed.
 */
@Composable
fun TabBar(
    tabs: List<Pair<LightIconConfiguration, Boolean>>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 1.2f.gridUnitsAsDp(), vertical = 0.6f.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { i, (icon, active) ->
            Box(
                modifier = Modifier
                    .alpha(if (active) 1f else 0.43f)
                    .lightClickable { onSelect(i) },
            ) {
                LightIcon(icon = icon, size = 2.2f)
            }
        }
    }
}

fun formatTime(ms: Int): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "$m:" + s.toString().padStart(2, '0')
}
