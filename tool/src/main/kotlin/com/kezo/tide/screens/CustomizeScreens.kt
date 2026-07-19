package com.kezo.tide.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.kezo.tide.SectionPref
import com.kezo.tide.TidePrefs
import com.kezo.tide.ui.TideScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

class CustomizeViewModel : LightViewModel<Unit>()

/**
 * Shared editor: rows with a LightOS toggle glyph on the left and up/down
 * arrows on the right for reordering.
 */
abstract class SectionEditorScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val labels: Map<String, String>,
    private val locked: Set<String>,
) : LightScreen<Unit, CustomizeViewModel>(sealedActivity) {

    override val viewModelClass: Class<CustomizeViewModel>
        get() = CustomizeViewModel::class.java

    override fun createViewModel() = CustomizeViewModel()

    protected abstract val current: List<SectionPref>
    protected abstract fun save(list: List<SectionPref>)

    @Composable
    protected abstract fun observed(): List<SectionPref>

    @Composable
    override fun Content() {
        val list = observed()

        TideScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(title),
            )
            LightText(
                text = "Tap to show or hide, arrows to reorder",
                variant = LightTextVariant.Superfine,
                lighten = true,
                modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
            )
            Spacer(modifier = Modifier.height(0.5f.gridUnitsAsDp()))
            LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth()) {
                list.forEachIndexed { i, pref ->
                    EditorRow(
                        pref = pref,
                        label = labels[pref.id] ?: pref.id,
                        isLocked = pref.id in locked,
                        canMoveUp = i > 0,
                        canMoveDown = i < list.size - 1,
                        onToggle = {
                            if (pref.id !in locked) {
                                save(list.map {
                                    if (it.id == pref.id) it.copy(enabled = !it.enabled) else it
                                })
                            }
                        },
                        onMove = { delta ->
                            val to = i + delta
                            if (to in list.indices) {
                                val mutable = list.toMutableList()
                                val item = mutable.removeAt(i)
                                mutable.add(to, item)
                                save(mutable)
                            }
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun EditorRow(
        pref: SectionPref,
        label: String,
        isLocked: Boolean,
        canMoveUp: Boolean,
        canMoveDown: Boolean,
        onToggle: () -> Unit,
        onMove: (Int) -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .lightClickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LightIcon(
                    // LightIcons names are inverted vs the artwork (knob-left is labeled ON).
                    icon = if (pref.enabled) LightIcons.TOGGLE_OFF else LightIcons.TOGGLE_ON,
                    modifier = Modifier
                        .padding(end = 0.65f.gridUnitsAsDp())
                        .alpha(if (isLocked) 0.4f else 1f),
                )
                LightText(
                    text = label,
                    variant = LightTextVariant.Copy,
                )
            }
            Box(
                modifier = Modifier
                    .alpha(if (canMoveUp) 1f else 0.3f)
                    .lightClickable(enabled = canMoveUp) { onMove(-1) },
            ) {
                LightIcon(icon = LightIcons.UP, size = 1.6f)
            }
            Spacer(modifier = Modifier.width(1f.gridUnitsAsDp()))
            Box(
                modifier = Modifier
                    .alpha(if (canMoveDown) 1f else 0.3f)
                    .lightClickable(enabled = canMoveDown) { onMove(1) },
            ) {
                LightIcon(icon = LightIcons.DOWN, size = 1.6f)
            }
        }
    }
}

/** Settings editor for the Home page's sections. */
class HomeSectionsScreen(sealedActivity: SealedLightActivity) : SectionEditorScreen(
    sealedActivity,
    title = "Home Sections",
    labels = mapOf(
        "recents" to "Recently Played",
        "artists" to "Artists",
        "mixes" to "My Mixes",
        "releases" to "New Releases",
    ),
    locked = emptySet(),
) {
    override val current: List<SectionPref> get() = TidePrefs.homeSections.value
    override fun save(list: List<SectionPref>) = TidePrefs.setHomeSections(list)

    @Composable
    override fun observed(): List<SectionPref> {
        val list by TidePrefs.homeSections.collectAsState()
        return list
    }
}

/** Settings editor for the navigation bar's tabs. */
class NavBarScreen(sealedActivity: SealedLightActivity) : SectionEditorScreen(
    sealedActivity,
    title = "Navigation Bar",
    labels = mapOf(
        "home" to "Home",
        "liked" to "Liked Songs",
        "albums" to "Albums",
        "playlists" to "Playlists",
        "search" to "Search",
        "settings" to "Settings",
    ),
    locked = TidePrefs.LOCKED_NAV,
) {
    override val current: List<SectionPref> get() = TidePrefs.navTabs.value
    override fun save(list: List<SectionPref>) = TidePrefs.setNavTabs(list)

    @Composable
    override fun observed(): List<SectionPref> {
        val list by TidePrefs.navTabs.collectAsState()
        return list
    }
}
