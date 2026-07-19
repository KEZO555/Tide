package com.kezo.tide.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.kezo.tide.api.Artist
import com.kezo.tide.api.Tidal
import com.kezo.tide.api.Track
import com.kezo.tide.player.TidePlayer
import com.kezo.tide.ui.ActionRow
import com.kezo.tide.ui.TideScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

class TrackOptionsViewModel : LightViewModel<Unit>()

/**
 * Long-press options for a track, LightOS style: a full-screen list of
 * plain action rows.
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
            LightTopBar(
                leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(track.title),
            )
            Column(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
                LightText(
                    text = track.artist,
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))

                ActionRow(text = "Play Next", onClick = {
                    TidePlayer.playNext(track)
                    goBack()
                })
                ActionRow(text = "Add to Queue", onClick = {
                    TidePlayer.addToQueue(track)
                    goBack()
                })
                if (track.albumId != 0L) {
                    ActionRow(text = "Go to Album", onClick = {
                        navigateTo({
                            TrackListScreen(it, track.albumTitle.ifBlank { "Album" }) {
                                Tidal.albumTracks(track.albumId)
                            }
                        })
                    })
                }
                if (track.artistId != 0L) {
                    ActionRow(text = "Go to Artist", onClick = {
                        navigateTo({ ArtistScreen(it, Artist(track.artistId, track.artist)) })
                    })
                }
            }
        }
    }
}
