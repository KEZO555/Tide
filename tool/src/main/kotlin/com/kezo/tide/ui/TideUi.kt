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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
const val ROW_UNITS = 3.8f

/**
 * Tracks whether a PlayerScreen is somewhere in the navigation stack, so
 * headers can pop back toward it instead of stacking endless copies.
 */
object PlayerPresence {
    var openCount: Int = 0
}

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
    number: Int?,
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
        if (number != null) {
            LightText(
                text = "$number.",
                variant = LightTextVariant.Detail,
                lighten = !active,
                align = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.width(2.2f.gridUnitsAsDp()),
            )
            Spacer(modifier = Modifier.width(0.5f.gridUnitsAsDp()))
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            LightText(
                text = track.title,
                variant = LightTextVariant.Subheading,
                underline = active,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LightText(
                text = listOf(
                    track.artist,
                    formatTime(track.durationSec * 1000),
                    if (track.explicit) "E" else "",
                )
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                variant = LightTextVariant.Detail,
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
            variant = LightTextVariant.Subheading,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (secondary.isNotBlank()) {
            LightText(
                text = secondary,
                variant = LightTextVariant.Detail,
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
        variant = LightTextVariant.Copy,
        underline = true,
        modifier = modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.4f.gridUnitsAsDp()),
    )
}

/**
 * Right-aligned sort control for library lists: shows the current option and
 * expands into a LightOS-style list with a checkmark on the active choice.
 */
@Composable
fun SortDropdown(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = options.getOrElse(selectedIndex) { options.firstOrNull() ?: "" }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        Row(
            modifier = Modifier
                .lightClickable { expanded = !expanded }
                .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.35f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightText(
                text = "Sort: $current",
                variant = LightTextVariant.Detail,
                lighten = true,
            )
            LightIcon(
                icon = if (expanded) LightIcons.UP else LightIcons.DOWN,
                size = 1.1f,
                modifier = Modifier
                    .padding(start = 0.4f.gridUnitsAsDp())
                    .alpha(0.6f),
            )
        }
        if (expanded) {
            options.forEachIndexed { i, label ->
                LightText(
                    text = label,
                    variant = LightTextVariant.Detail,
                    lighten = i != selectedIndex,
                    underline = i == selectedIndex,
                    align = TextAlign.End,
                    modifier = Modifier
                        .lightClickable {
                            onSelect(i)
                            expanded = false
                        }
                        .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.35f.gridUnitsAsDp()),
                )
            }
            Spacer(modifier = Modifier.height(0.3f.gridUnitsAsDp()))
        }
    }
}

/** Underlined "Show All" row under a capped home section. */
@Composable
fun ShowAllRow(onClick: () -> Unit) {
    LightText(
        text = "Show All",
        variant = LightTextVariant.Detail,
        underline = true,
        modifier = Modifier
            .lightClickable(onClick = onClick)
            .padding(
                start = 1f.gridUnitsAsDp(),
                top = 0.25f.gridUnitsAsDp(),
                bottom = 0.5f.gridUnitsAsDp(),
            ),
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

/** Large section headline for the Home page. */
@Composable
fun HomeSectionHeader(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Heading,
        modifier = Modifier.padding(
            start = 1f.gridUnitsAsDp(),
            top = 1.5f.gridUnitsAsDp(),
            bottom = 0.4f.gridUnitsAsDp(),
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
        variant = LightTextVariant.Copy,
        underline = selected,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.5f.gridUnitsAsDp()),
    )
}

/**
 * LightOS settings row: label on the left, an optional current value and a
 * chevron on the right — the standard "drill into a sub-page" row.
 */
@Composable
fun SettingsNavRow(
    label: String,
    value: String? = null,
    chevron: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_UNITS.gridUnitsAsDp())
            .lightClickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Copy,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!value.isNullOrBlank()) {
            LightText(
                text = value,
                variant = LightTextVariant.Detail,
                lighten = true,
                maxLines = 1,
                modifier = Modifier.padding(end = 0.5f.gridUnitsAsDp()),
            )
        }
        if (chevron) {
            LightIcon(
                icon = LightIcons.ARROW_RIGHT,
                size = 1.3f,
                modifier = Modifier.alpha(0.5f),
            )
        }
    }
}

/** LightOS settings toggle row: label on the left, switch glyph on the right. */
@Composable
fun SettingsToggleRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_UNITS.gridUnitsAsDp())
            .lightClickable { onToggle(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Copy,
            modifier = Modifier.weight(1f),
        )
        LightIcon(
            // LightIcons names are inverted vs the artwork (knob-left is labeled ON).
            icon = if (checked) LightIcons.TOGGLE_OFF else LightIcons.TOGGLE_ON,
        )
    }
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
            variant = LightTextVariant.Copy,
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
