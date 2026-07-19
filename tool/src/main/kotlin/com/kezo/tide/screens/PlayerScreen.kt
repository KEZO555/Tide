package com.kezo.tide.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.kezo.tide.api.Artist
import com.kezo.tide.api.Tidal
import com.kezo.tide.api.Track
import com.kezo.tide.player.RepeatMode
import com.kezo.tide.player.TidePlayer
import com.kezo.tide.ui.ActionRow
import com.kezo.tide.ui.EmptyText
import com.kezo.tide.ui.NumberedTrackRow
import com.kezo.tide.ui.ROW_UNITS
import com.kezo.tide.ui.TideScreen
import com.kezo.tide.ui.formatTime
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
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

/**
 * Now Playing in the style of the LightOS music tool (as replicated by Phono):
 * centered artist / large title / duration, a scrubbable line, three transport
 * icons, and shuffle · save-circle · repeat pinned along the bottom.
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
            val t = track
            LightTopBar(
                leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(" "),
                rightButton = if (t != null) {
                    LightBarButton.LightIcon(
                        LightIcons.LIST,
                        onClick = { navigateTo({ a -> QueueScreen(a) }) },
                    )
                } else {
                    null
                },
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .padding(bottom = 1.3f.gridUnitsAsDp()),
                    ) {
                        if (t != null) {
                            LightText(
                                text = t.artist,
                                variant = LightTextVariant.Fine,
                                lighten = true,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                align = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (t.artistId != 0L) {
                                            Modifier.lightClickable {
                                                navigateTo({ a ->
                                                    ArtistScreen(a, Artist(t.artistId, t.artist))
                                                })
                                            }
                                        } else {
                                            Modifier
                                        }
                                    ),
                            )
                            LightText(
                                text = t.title,
                                variant = LightTextVariant.Subheading,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                align = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (t.albumId != 0L) {
                                            Modifier.lightClickable {
                                                navigateTo({ a ->
                                                    TrackListScreen(a, t.albumTitle.ifBlank { "Album" }, numbered = true) {
                                                        Tidal.albumTracks(t.albumId)
                                                    }
                                                })
                                            }
                                        } else {
                                            Modifier
                                        }
                                    ),
                            )
                            LightText(
                                text = formatTime(durationMs),
                                variant = LightTextVariant.Detail,
                                align = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 0.25f.gridUnitsAsDp()),
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
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        } else {
                            LightText(
                                text = "No song playing",
                                variant = LightTextVariant.Copy,
                                align = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            LightText(
                                text = "Go back and play something!",
                                variant = LightTextVariant.Detail,
                                align = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    if (t != null) {
                        ScrubProgress(positionMs = positionMs, durationMs = durationMs)
                        TransportControls(isPlaying = isPlaying)
                    }
                }

                if (t != null) {
                    SecondaryControls(
                        shuffleActive = shuffle,
                        repeatMode = repeat,
                        saved = favorite == true,
                        saveEnabled = favorite != null,
                        onSaveTap = { viewModel.toggleFavorite(t) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = 1.3f.gridUnitsAsDp(),
                                vertical = 1.3f.gridUnitsAsDp(),
                            ),
                    )
                } else {
                    Spacer(modifier = Modifier.height(3.2f.gridUnitsAsDp()))
                }
            }
        }
    }

    /** Phono's ProgressBar: thin full line + thicker filled line, drag to scrub. */
    @Composable
    private fun ScrubProgress(positionMs: Int, durationMs: Int) {
        val colors = LightThemeTokens.colors
        val duration = durationMs.coerceAtLeast(1)
        var scrubMs by remember { mutableIntStateOf(-1) }
        val displayMs = if (scrubMs >= 0) scrubMs else positionMs
        val fraction = (displayMs.toFloat() / duration).coerceIn(0f, 1f)

        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .defaultMinSize(minHeight = 2.55f.gridUnitsAsDp())
                .pointerInput(duration) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        fun seekAt(x: Float) {
                            val f = (x / size.width).coerceIn(0f, 1f)
                            scrubMs = (duration * f).toInt()
                        }
                        seekAt(down.position.x)
                        drag(down.id) { change ->
                            change.consume()
                            seekAt(change.position.x)
                        }
                        TidePlayer.seekTo(scrubMs.coerceAtLeast(0))
                        scrubMs = -1
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.13f.gridUnitsAsDp())
                    .align(Alignment.Center)
                    .background(colors.content),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(0.38f.gridUnitsAsDp())
                    .align(Alignment.CenterStart)
                    .background(colors.content),
            )
        }
    }

    @Composable
    private fun TransportControls(isPlaying: Boolean) {
        val colors = LightThemeTokens.colors
        val iconSize = 2.55f.gridUnitsAsDp()
        Row(
            modifier = Modifier.padding(
                top = 0.75f.gridUnitsAsDp(),
                bottom = 1.3f.gridUnitsAsDp(),
            ),
            horizontalArrangement = Arrangement.spacedBy(3.3f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.SkipPrevious,
                contentDescription = "Previous",
                tint = colors.content,
                modifier = Modifier
                    .size(iconSize)
                    .lightClickable { TidePlayer.previous() },
            )
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = "Play/Pause",
                tint = colors.content,
                modifier = Modifier
                    .size(iconSize)
                    .lightClickable { TidePlayer.toggle() },
            )
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = "Next",
                tint = colors.content,
                modifier = Modifier
                    .size(iconSize)
                    .lightClickable { TidePlayer.next() },
            )
        }
    }

    @Composable
    private fun SecondaryControls(
        shuffleActive: Boolean,
        repeatMode: RepeatMode,
        saved: Boolean,
        saveEnabled: Boolean,
        onSaveTap: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlaybackModeIcon(
                icon = Icons.Filled.Shuffle,
                active = shuffleActive,
                contentDescription = "Shuffle",
                onClick = { TidePlayer.toggleShuffle() },
            )
            SaveControl(
                saved = saved,
                enabled = saveEnabled,
                onClick = onSaveTap,
            )
            PlaybackModeIcon(
                icon = if (repeatMode == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                active = repeatMode != RepeatMode.OFF,
                contentDescription = "Repeat",
                onClick = { TidePlayer.cycleRepeat() },
            )
        }
    }

    /** Circle save control: outlined + for save, filled ✓ once liked. */
    @Composable
    private fun SaveControl(saved: Boolean, enabled: Boolean, onClick: () -> Unit) {
        val colors = LightThemeTokens.colors
        val circleSize = 1.9f.gridUnitsAsDp()
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(circleSize)
                    .then(
                        if (saved) {
                            Modifier.background(colors.content, CircleShape)
                        } else {
                            Modifier.border(0.13f.gridUnitsAsDp(), colors.content, CircleShape)
                        }
                    )
                    .lightClickable(enabled = enabled, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (saved) Icons.Filled.Check else Icons.Filled.Add,
                    contentDescription = if (saved) "Unlike" else "Save to Liked Songs",
                    tint = if (saved) colors.background else colors.content,
                    modifier = Modifier.size(1.27f.gridUnitsAsDp()),
                )
            }
            Spacer(modifier = Modifier.height(0.25f.gridUnitsAsDp()))
            Spacer(modifier = Modifier.height(0.13f.gridUnitsAsDp()))
        }
    }

    /** Small mode icon with the active-state underline dash beneath. */
    @Composable
    private fun PlaybackModeIcon(
        icon: ImageVector,
        active: Boolean,
        contentDescription: String,
        onClick: () -> Unit,
    ) {
        val colors = LightThemeTokens.colors
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = colors.content,
                modifier = Modifier
                    .size(1.9f.gridUnitsAsDp())
                    .lightClickable(onClick = onClick),
            )
            Spacer(modifier = Modifier.height(0.25f.gridUnitsAsDp()))
            if (active) {
                Box(
                    modifier = Modifier
                        .width(0.76f.gridUnitsAsDp())
                        .height(0.13f.gridUnitsAsDp())
                        .background(colors.content),
                )
            } else {
                Spacer(modifier = Modifier.height(0.13f.gridUnitsAsDp()))
            }
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
                LightText(
                    text = "Hold a track to remove it",
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                    modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                )
                LightLazyScrollView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    uniformItemHeightGridUnits = ROW_UNITS,
                ) {
                    items(queue.size) { i ->
                        NumberedTrackRow(
                            number = null,
                            track = queue[i],
                            active = i == index,
                            onClick = { TidePlayer.jumpTo(i) },
                            onLongClick = { TidePlayer.removeFromQueue(i) },
                        )
                    }
                }
                if (queue.size > 1) {
                    ActionRow(text = "Clear Queue", onClick = { TidePlayer.clearUpcoming() })
                }
            }
        }
    }
}
