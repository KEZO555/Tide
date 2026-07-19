package com.kezo.tide.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.kezo.tide.api.Track
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeColors
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * Height (in LightOS grid units) of every uniform list row in the tool.
 * Phono's library rows: large title + lighter secondary line, roomy spacing.
 */
const val ROW_UNITS = 3.2f

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Data<T>(val value: T) : UiState<T>
    data class Failed(val message: String) : UiState<Nothing>
}

/**
 * Standard screen chrome. Uses the SDK's default typography, which resolves to
 * the LP3's system Akkurat face at LightOS sizes — the same look as Phono.
 */
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
            variant = LightTextVariant.Fine,
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
 * Phono library track row: number column, large title, lighter
 * "artist · duration" line beneath.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NumberedTrackRow(
    number: Int,
    track: Track,
    onClick: () -> Unit,
    active: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_UNITS.gridUnitsAsDp())
            .combinedClickable(
                interactionSource = null,
                indication = null,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = "$number.",
            variant = LightTextVariant.Superfine,
            lighten = !active,
            align = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.width(2f.gridUnitsAsDp()),
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

/** Phono library media row: large title with a lighter secondary line. */
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

/** Large underlined text button row. */
@Composable
fun TextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    LightText(
        text = text,
        variant = LightTextVariant.Fine,
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

// ---------- settings primitives (LightOS settings-tool patterns) ----------

/** Section label above a settings group. */
@Composable
fun SectionLabel(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        lighten = true,
        modifier = Modifier.padding(
            top = 1.5f.gridUnitsAsDp(),
            bottom = 0.5f.gridUnitsAsDp(),
        ),
    )
}

/** Plain tappable settings row; [selected] underlines the label (radio-style). */
@Composable
fun ActionRow(text: String, selected: Boolean = false, onClick: () -> Unit) {
    LightText(
        text = text,
        variant = LightTextVariant.Fine,
        underline = selected,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.5f.gridUnitsAsDp()),
    )
}

/** LightOS toggle row: switch glyph on the left, label beside it. */
@Composable
fun ToggleRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable { onToggle(!checked) }
            .padding(vertical = 0.5f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightIcon(
            // LightIcons names are inverted vs the artwork (knob-left is labeled ON).
            icon = if (checked) LightIcons.TOGGLE_OFF else LightIcons.TOGGLE_ON,
            modifier = Modifier.padding(end = 0.65f.gridUnitsAsDp()),
        )
        LightText(
            text = label,
            variant = LightTextVariant.Fine,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---------- tab bar (Phono's navbar) ----------

sealed interface TabIcon {
    data class Light(val icon: LightIconConfiguration) : TabIcon
    data class Vector(val icon: ImageVector) : TabIcon
}

private const val TAB_ICON_SIZE_UNITS = 2.55f
private const val TAB_BAR_HEIGHT_UNITS = 3.5f

@Composable
fun inactiveTabColor(): Color =
    if (LightThemeTokens.colors == LightThemeColors.Dark) Color(0xFF6E6E6E)
    else Color(0xFFC1C1C1)

/**
 * Phono's tab bar: icon logos in a space-between row, active at full content
 * color, inactive dimmed gray.
 */
@Composable
fun TabBar(
    tabs: List<Pair<TabIcon, Boolean>>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LightThemeTokens.colors
    val inactive = inactiveTabColor()
    val barHeight = TAB_BAR_HEIGHT_UNITS.gridUnitsAsDp()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 0.5f.gridUnitsAsDp())
            .height(barHeight)
            .padding(horizontal = 0.35f.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { i, (icon, active) ->
            val tint = if (active) colors.content else inactive
            Box(
                modifier = Modifier
                    .size(barHeight)
                    .lightClickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                when (icon) {
                    is TabIcon.Light -> LightIcon(
                        icon = icon.icon,
                        size = TAB_ICON_SIZE_UNITS,
                        modifier = Modifier.alpha(if (active) 1f else 0.43f),
                    )

                    is TabIcon.Vector -> Icon(
                        painter = rememberVectorPainter(icon.icon),
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(TAB_ICON_SIZE_UNITS.gridUnitsAsDp()),
                    )
                }
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
