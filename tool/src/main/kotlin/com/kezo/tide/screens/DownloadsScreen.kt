package com.kezo.tide.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.kezo.tide.player.Downloads
import com.kezo.tide.player.TidePlayer
import com.kezo.tide.ui.ActionRow
import com.kezo.tide.ui.EmptyText
import com.kezo.tide.ui.NumberedTrackRow
import com.kezo.tide.ui.ROW_UNITS
import com.kezo.tide.ui.TideScreen
import com.kezo.tide.ui.formatBytes
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

class DownloadsViewModel : LightViewModel<Unit>()

/** Manage offline downloads: total size, play, and remove. */
class DownloadsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, DownloadsViewModel>(sealedActivity) {

    override val viewModelClass: Class<DownloadsViewModel>
        get() = DownloadsViewModel::class.java

    override fun createViewModel() = DownloadsViewModel()

    @Composable
    override fun Content() {
        val downloads by Downloads.items.collectAsState()
        val downloadingIds by Downloads.downloadingIds.collectAsState()
        val current by TidePlayer.current.collectAsState()
        var confirmClear by remember { mutableStateOf(false) }

        TideScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("Downloads"),
            )

            val totalBytes = downloads.sumOf { it.bytes }
            val summary = when {
                downloads.isEmpty() && downloadingIds.isEmpty() -> "No downloads yet"
                else -> buildString {
                    append("${downloads.size} ${if (downloads.size == 1) "song" else "songs"}")
                    append(" · ${formatBytes(totalBytes)}")
                    if (downloadingIds.isNotEmpty()) append(" · ${downloadingIds.size} downloading")
                }
            }
            LightText(
                text = summary,
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(
                    horizontal = 1f.gridUnitsAsDp(),
                    vertical = 0.5f.gridUnitsAsDp(),
                ),
            )

            if (downloads.isEmpty()) {
                EmptyText("Download songs, albums, or playlists to listen offline.")
                return@TideScreen
            }

            val tracks = downloads.map { it.track }
            LightLazyScrollView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                uniformItemHeightGridUnits = ROW_UNITS,
            ) {
                items(tracks.size) { i ->
                    val track = tracks[i]
                    NumberedTrackRow(
                        number = null,
                        track = track,
                        active = current?.id == track.id,
                        downloaded = true,
                        onClick = {
                            TidePlayer.play(tracks, i)
                            navigateTo({ PlayerScreen(it) })
                        },
                        onLongClick = {
                            navigateTo({ TrackOptionsScreen(it, track) })
                        },
                    )
                }
            }

            ActionRow(
                text = if (confirmClear) "Tap again to remove all" else "Remove All Downloads",
                onClick = {
                    if (confirmClear) {
                        Downloads.clear()
                        confirmClear = false
                    } else {
                        confirmClear = true
                    }
                },
            )
        }
    }
}
