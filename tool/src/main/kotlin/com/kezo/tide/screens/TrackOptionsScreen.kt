package com.kezo.tide.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.kezo.tide.api.Artist
import com.kezo.tide.api.Tidal
import com.kezo.tide.api.Track
import com.kezo.tide.player.TidePlayer
import com.kezo.tide.ui.TideScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

class TrackOptionsViewModel : LightViewModel<Unit>()

/**
 * Long-press options for a track, in the SDK's fullscreen-modal style:
 * track header, large centered options, and a close glyph in the bottom bar.
 */
class TrackOptionsScreen(
    sealedActivity: SealedLightActivity,
    private val track: Track,
) : LightScreen<Unit, TrackOptionsViewModel>(sealedActivity) {

    override val viewModelClass: Class<TrackOptionsViewModel>
        get() = TrackOptionsViewModel::class.java

    override fun createViewModel() = TrackOptionsViewModel()

    @Composable
    override fun Content() {
        TideScreen {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(2f.gridUnitsAsDp()))
                LightText(
                    text = track.title,
                    variant = LightTextVariant.Subheading,
                    align = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                LightText(
                    text = track.artist,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    align = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )

                Box(modifier = Modifier.weight(1f))

                MenuOption("Play Next") {
                    TidePlayer.playNext(track)
                    goBack()
                }
                MenuOption("Add to Queue") {
                    TidePlayer.addToQueue(track)
                    goBack()
                }
                if (track.albumId != 0L) {
                    MenuOption("Go to Album") {
                        navigateTo({
                            TrackListScreen(it, track.albumTitle.ifBlank { "Album" }, numbered = true) {
                                Tidal.albumTracks(track.albumId)
                            }
                        })
                    }
                }
                if (track.artistId != 0L) {
                    MenuOption("Go to Artist") {
                        navigateTo({ ArtistScreen(it, Artist(track.artistId, track.artist)) })
                    }
                }
                MenuOption("Track Radio") {
                    navigateTo({
                        TrackListScreen(it, "Radio") { Tidal.trackRadio(track.id) }
                    })
                }

                Box(modifier = Modifier.weight(1f))
            }

            LightBottomBar(
                items = listOf(
                    LightBarButton.LightIcon(
                        icon = LightIcons.CLOSE,
                        onClick = { goBack() },
                    ),
                ),
            )
        }
    }

    /** LightOS menu option: large, centered, roomy. */
    @Composable
    private fun MenuOption(label: String, onClick: () -> Unit) {
        LightText(
            text = label,
            variant = LightTextVariant.Heading,
            align = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable(onClick = onClick)
                .padding(vertical = 0.6f.gridUnitsAsDp()),
        )
    }
}
