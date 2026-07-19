package com.kezo.tide.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.kezo.tide.api.Tidal
import com.kezo.tide.api.Track
import com.kezo.tide.player.RepeatMode
import com.kezo.tide.player.TidePlayer
import com.kezo.tide.ui.CenteredTimeRow
import com.kezo.tide.ui.DottedProgress
import com.kezo.tide.ui.EmptyText
import com.kezo.tide.ui.ROW_UNITS
import com.kezo.tide.ui.TideScreen
import com.kezo.tide.ui.TrackRow
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class PlayerViewModel : LightViewModel<Unit>() {
    /** null = unknown / loading */
    val favorite = MutableStateFlow<Boolean?>(null)

    fun refreshFavorite(trackId: Long) {
        favorite.value = null
        viewModelScope.launch(Dispatchers.IO) {
            Tidal.ensureFavTrackIds()
            favorite.value = trackId in Tidal.favTrackIds
        }
    }

    fun toggleFavorite(track: Track) {
        val currently = favorite.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (currently) Tidal.removeFavoriteTrack(track.id)
                else Tidal.addFavoriteTrack(track.id)
                favorite.value = !currently
            } catch (_: Exception) {
                // leave state as-is; user can retry
            }
        }
    }
}

class PlayerScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, PlayerViewModel>(sealedActivity) {

    override val viewModelClass: Class<PlayerViewModel>
        get() = PlayerViewModel::class.java

    override fun createViewModel() = PlayerViewModel()

    @Composable
    override fun Content() {
        val track by TidePlayer.current.collectAsState()
        val isPlaying by TidePlayer.isPlaying.collectAsState()
        val isLoading by TidePlayer.isLoading.collectAsState()
        val positionMs by TidePlayer.positionMs.collectAsState()
        val durationMs by TidePlayer.durationMs.collectAsState()
        val shuffle by TidePlayer.shuffle.collectAsState()
        val repeat by TidePlayer.repeat.collectAsState()
        val playbackError by TidePlayer.error.collectAsState()
        val favorite by viewModel.favorite.collectAsState()

        LaunchedEffect(track?.id) {
            track?.id?.let(viewModel::refreshFavorite)
        }

        TideScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("now playing"),
                rightButton = track?.let { t ->
                    LightBarButton.LightIcon(
                        if (favorite == true) LightIcons.STAR else LightIcons.STAR_OUTLINE,
                        onClick = { viewModel.toggleFavorite(t) },
                    )
                },
            )

            val t = track
            if (t == null) {
                EmptyText("nothing playing")
                return@TideScreen
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 1.5f.gridUnitsAsDp()),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LightText(
                    text = t.title,
                    variant = LightTextVariant.Subheading,
                    align = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                LightText(
                    text = t.artist,
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    align = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                )
                if (t.albumTitle.isNotBlank()) {
                    LightText(
                        text = t.albumTitle,
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        align = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val statusLine = when {
                    isLoading -> "loading…"
                    playbackError != null -> playbackError ?: ""
                    else -> Tidal.quality.lowercase() + if (t.explicit) "  ·  explicit" else ""
                }
                LightText(
                    text = statusLine,
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                    align = TextAlign.Center,
                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                )
            }

            Column(modifier = Modifier.padding(horizontal = 1.5f.gridUnitsAsDp())) {
                DottedProgress(
                    fraction = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                    onSeekFraction = TidePlayer::seekToFraction,
                )
                CenteredTimeRow(positionMs = positionMs, durationMs = durationMs)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1f.gridUnitsAsDp()),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LightIcon(
                    icon = LightIcons.SHUFFLE,
                    modifier = Modifier
                        .alpha(if (shuffle) 1f else 0.35f)
                        .lightClickable { TidePlayer.toggleShuffle() },
                )
                LightIcon(
                    icon = LightIcons.REWIND,
                    modifier = Modifier.lightClickable { TidePlayer.previous() },
                )
                LightIcon(
                    icon = if (isPlaying) LightIcons.PAUSE else LightIcons.PLAY,
                    size = 3f,
                    modifier = Modifier.lightClickable { TidePlayer.toggle() },
                )
                LightIcon(
                    icon = LightIcons.FAST_FORWARD,
                    modifier = Modifier.lightClickable { TidePlayer.next() },
                )
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier
                        .alpha(if (repeat != RepeatMode.OFF) 1f else 0.35f)
                        .lightClickable { TidePlayer.cycleRepeat() },
                ) {
                    LightIcon(icon = LightIcons.LOOP)
                    if (repeat == RepeatMode.ONE) {
                        LightText(text = "1", variant = LightTextVariant.Superfine)
                    }
                }
            }

            LightText(
                text = "queue",
                variant = LightTextVariant.Detail,
                lighten = true,
                align = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable { navigateTo({ QueueScreen(it) }) }
                    .padding(bottom = 1f.gridUnitsAsDp()),
            )
        }
    }
}

class QueueViewModel : LightViewModel<Unit>()

class QueueScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, QueueViewModel>(sealedActivity) {

    override val viewModelClass: Class<QueueViewModel>
        get() = QueueViewModel::class.java

    override fun createViewModel() = QueueViewModel()

    @Composable
    override fun Content() {
        val queue by TidePlayer.queue.collectAsState()
        val index by TidePlayer.index.collectAsState()

        TideScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("queue"),
            )
            if (queue.isEmpty()) {
                EmptyText("queue is empty")
            } else {
                LightLazyScrollView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    uniformItemHeightGridUnits = ROW_UNITS,
                ) {
                    items(queue.size) { i ->
                        TrackRow(
                            track = queue[i],
                            active = i == index,
                            onClick = { TidePlayer.jumpTo(i) },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(0.5f.gridUnitsAsDp()))
        }
    }
}
