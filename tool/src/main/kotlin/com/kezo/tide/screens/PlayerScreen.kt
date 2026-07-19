package com.kezo.tide.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.kezo.tide.api.Tidal
import com.kezo.tide.api.Track
import com.kezo.tide.player.RepeatMode
import com.kezo.tide.player.TidePlayer
import com.kezo.tide.ui.EmptyText
import com.kezo.tide.ui.NumberedTrackRow
import com.kezo.tide.ui.ROW_UNITS
import com.kezo.tide.ui.SolidProgress
import com.kezo.tide.ui.TideScreen
import com.kezo.tide.ui.TimeRow
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.designVerticalPxToDp
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

/**
 * Echo's Playing screen: centered title/artist, solid progress line with times,
 * a space-between controls row (shuffle · prev · play · next · repeat) with
 * underline indicators on the toggles, and the favorite control centered below.
 */
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
                center = LightTopBarCenter.Text("Now Playing"),
                rightButton = LightBarButton.LightIcon(
                    LightIcons.LIST,
                    onClick = { navigateTo({ a -> QueueScreen(a) }) },
                ),
            )

            val t = track
            if (t == null) {
                EmptyText("Nothing playing")
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
                    variant = LightTextVariant.Fine,
                    align = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                LightText(
                    text = t.artist,
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                    align = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 0.4f.gridUnitsAsDp()),
                )
                val statusLine = when {
                    isLoading -> "Loading..."
                    playbackError != null -> playbackError
                    else -> null
                }
                statusLine?.let {
                    LightText(
                        text = it,
                        variant = LightTextVariant.Superfine,
                        lighten = true,
                        align = TextAlign.Center,
                        modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                    )
                }
            }

            Column(modifier = Modifier.padding(horizontal = 1.5f.gridUnitsAsDp())) {
                SolidProgress(
                    fraction = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                    onSeekFraction = TidePlayer::seekToFraction,
                )
                TimeRow(positionMs = positionMs, durationMs = durationMs)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 1.5f.gridUnitsAsDp())
                    .padding(top = 0.6f.gridUnitsAsDp()),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToggleIcon(
                    icon = LightIcons.SHUFFLE,
                    active = shuffle,
                    onClick = { TidePlayer.toggleShuffle() },
                )
                Box(modifier = Modifier.lightClickable { TidePlayer.previous() }) {
                    LightIcon(icon = LightIcons.REWIND, size = 2.6f)
                }
                Box(modifier = Modifier.lightClickable { TidePlayer.toggle() }) {
                    LightIcon(
                        icon = if (isPlaying) LightIcons.PAUSE else LightIcons.PLAY,
                        size = 2.6f,
                    )
                }
                Box(modifier = Modifier.lightClickable { TidePlayer.next() }) {
                    LightIcon(icon = LightIcons.FAST_FORWARD, size = 2.6f)
                }
                ToggleIcon(
                    icon = LightIcons.LOOP,
                    active = repeat != RepeatMode.OFF,
                    badge = if (repeat == RepeatMode.ONE) "1" else null,
                    onClick = { TidePlayer.cycleRepeat() },
                )
            }

            // echo's musicControlsExtra: favorite centered below the controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 0.8f.gridUnitsAsDp(), bottom = 1f.gridUnitsAsDp()),
                horizontalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier.lightClickable {
                        viewModel.toggleFavorite(t)
                    },
                ) {
                    LightIcon(
                        icon = if (favorite == true) LightIcons.STAR else LightIcons.STAR_OUTLINE,
                        size = 1.5f,
                    )
                }
            }
        }
    }

    /** Icon with echo's thin active-state underline indicator beneath it. */
    @Composable
    private fun ToggleIcon(
        icon: LightIconConfiguration,
        active: Boolean,
        onClick: () -> Unit,
        badge: String? = null,
    ) {
        val color = LightThemeTokens.colors.content
        Column(
            modifier = Modifier.lightClickable(onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                LightIcon(icon = icon, size = 1.5f)
                badge?.let {
                    LightText(text = it, variant = LightTextVariant.Micro)
                }
            }
            Spacer(modifier = Modifier.height(0.15f.gridUnitsAsDp()))
            Box(
                modifier = Modifier
                    .width(1.5f.gridUnitsAsDp())
                    .height(3f.designVerticalPxToDp())
                    .background(if (active) color else color.copy(alpha = 0f)),
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
                center = LightTopBarCenter.Text("Queue"),
            )
            if (queue.isEmpty()) {
                EmptyText("Queue is empty")
            } else {
                LightLazyScrollView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    uniformItemHeightGridUnits = ROW_UNITS,
                ) {
                    items(queue.size) { i ->
                        NumberedTrackRow(
                            number = i + 1,
                            track = queue[i],
                            active = i == index,
                            onClick = { TidePlayer.jumpTo(i) },
                        )
                    }
                }
            }
        }
    }
}
