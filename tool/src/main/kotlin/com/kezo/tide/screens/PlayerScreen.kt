package com.kezo.tide.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewModelScope
import com.kezo.tide.api.Artist
import com.kezo.tide.api.Tidal
import com.kezo.tide.api.Track
import com.kezo.tide.TidePrefs
import com.kezo.tide.player.Downloads
import com.kezo.tide.player.RepeatMode
import com.kezo.tide.player.TidePlayer
import com.kezo.tide.ui.ActionRow
import com.kezo.tide.ui.AlbumArt
import com.kezo.tide.ui.AlbumArtBox
import com.kezo.tide.ui.rememberCover
import com.kezo.tide.ui.EmptyText
import com.kezo.tide.ui.NumberedTrackRow
import com.kezo.tide.ui.PlayerPresence
import com.kezo.tide.ui.ROW_UNITS
import com.kezo.tide.ui.TideScreen
import com.kezo.tide.ui.formatTime
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
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

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
 * Now Playing in the style of the Light podcast/music tools: back-only header,
 * centered artist / large title, a scrubbable line with elapsed/total time,
 * LightOS transport glyphs, and utilities along the bottom.
 */
class PlayerScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, PlayerViewModel>(sealedActivity) {

    init {
        PlayerPresence.openCount++
    }

    override fun onScreenDestroy() {
        PlayerPresence.openCount--
    }

    override val viewModelClass: Class<PlayerViewModel>
        get() = PlayerViewModel::class.java

    override fun createViewModel() = PlayerViewModel()

    @Composable
    override fun Content() {
        val track by TidePlayer.current.collectAsState()
        val isPlaying by TidePlayer.isPlaying.collectAsState()
        val positionMs by TidePlayer.positionMs.collectAsState()
        val durationMs by TidePlayer.durationMs.collectAsState()
        val shuffle by TidePlayer.shuffle.collectAsState()
        val repeat by TidePlayer.repeat.collectAsState()
        val playbackError by TidePlayer.error.collectAsState()
        val favorite by viewModel.favorite.collectAsState()
        val downloadedIds by Downloads.downloadedIds.collectAsState()
        val downloadingIds by Downloads.downloadingIds.collectAsState()

        LaunchedEffect(track?.id) {
            track?.id?.let(viewModel::refreshFavorite)
        }

        TideScreen {
            val t = track
            LightTopBar(
                leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                val showArt by TidePrefs.artworkNowPlaying.collectAsState()
                val cover = rememberCover(t?.albumCover ?: "", t?.albumId ?: 0L)
                val hasArt = t != null && showArt && cover.isNotBlank()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = if (hasArt) Arrangement.Top else Arrangement.Center,
                ) {
                    if (hasArt) {
                        Spacer(modifier = Modifier.height(0.4f.gridUnitsAsDp()))
                        AlbumArtBox(
                            cover = cover,
                            modifier = Modifier
                                .weight(6f)
                                .aspectRatio(1f)
                                .padding(bottom = 0.9f.gridUnitsAsDp()),
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth(0.9f),
                    ) {
                        if (t != null) {
                            LightText(
                                text = t.artist,
                                variant = LightTextVariant.Detail,
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
                                                    TrackListScreen(
                                                        a, t.albumTitle.ifBlank { "Album" },
                                                        numbered = true,
                                                        albumId = t.albumId,
                                                    ) { Tidal.albumTracks(t.albumId) }
                                                })
                                            }
                                        } else {
                                            Modifier
                                        }
                                    ),
                            )
                            val statusLine = playbackError
                            statusLine?.let {
                                LightText(
                                    text = it,
                                    variant = LightTextVariant.Superfine,
                                    lighten = true,
                                    align = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 0.6f.gridUnitsAsDp()),
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
                        Spacer(modifier = Modifier.height(0.4f.gridUnitsAsDp()))
                        ScrubProgress(positionMs = positionMs, durationMs = durationMs)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .padding(top = 0.3f.gridUnitsAsDp()),
                        ) {
                            LightText(
                                text = formatTime(positionMs),
                                variant = LightTextVariant.Detail,
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            LightText(
                                text = formatTime(durationMs),
                                variant = LightTextVariant.Detail,
                                align = TextAlign.End,
                            )
                        }
                        if (hasArt) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                        TransportControls(isPlaying = isPlaying)
                        if (hasArt) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                if (t != null) {
                    SecondaryControls(
                        shuffleActive = shuffle,
                        repeatMode = repeat,
                        saved = favorite == true,
                        saveEnabled = favorite != null,
                        onSaveTap = { viewModel.toggleFavorite(t) },
                        downloaded = t.id in downloadedIds,
                        downloading = t.id in downloadingIds,
                        onDownloadTap = {
                            if (t.id in downloadedIds) Downloads.remove(t.id)
                            else Downloads.download(t)
                        },
                        onOpenQueue = { navigateTo({ a -> QueueScreen(a) }) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = 1.3f.gridUnitsAsDp(),
                                vertical = 0.7f.gridUnitsAsDp(),
                            ),
                    )
                } else {
                    Spacer(modifier = Modifier.height(3.2f.gridUnitsAsDp()))
                }
            }
        }
    }

    /** Thin full line + thicker filled line, drag to scrub. */
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

    /** LightOS transport glyphs: rewind · play/pause · fast-forward. */
    @Composable
    private fun TransportControls(isPlaying: Boolean) {
        Row(
            modifier = Modifier.padding(
                top = 0.5f.gridUnitsAsDp(),
                bottom = 0.5f.gridUnitsAsDp(),
            ),
            horizontalArrangement = Arrangement.spacedBy(3.6f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.lightClickable { TidePlayer.previous() }) {
                LightIcon(icon = LightIcons.REWIND, size = 2.2f)
            }
            Box(modifier = Modifier.lightClickable { TidePlayer.toggle() }) {
                LightIcon(
                    icon = if (isPlaying) LightIcons.PAUSE else LightIcons.PLAY,
                    size = 2.6f,
                )
            }
            Box(modifier = Modifier.lightClickable { TidePlayer.next() }) {
                LightIcon(icon = LightIcons.FAST_FORWARD, size = 2.2f)
            }
        }
    }

    @Composable
    private fun SecondaryControls(
        shuffleActive: Boolean,
        repeatMode: RepeatMode,
        saved: Boolean,
        saveEnabled: Boolean,
        onSaveTap: () -> Unit,
        downloaded: Boolean,
        downloading: Boolean,
        onDownloadTap: () -> Unit,
        onOpenQueue: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlaybackModeIcon(
                icon = LightIcons.SHUFFLE,
                active = shuffleActive,
                onClick = { TidePlayer.toggleShuffle() },
            )
            SaveControl(
                saved = saved,
                enabled = saveEnabled,
                onClick = onSaveTap,
            )
            DownloadControl(
                downloaded = downloaded,
                downloading = downloading,
                onClick = onDownloadTap,
            )
            PlaybackModeIcon(
                icon = LightIcons.LOOP,
                active = repeatMode != RepeatMode.OFF,
                badge = if (repeatMode == RepeatMode.ONE) "1" else null,
                onClick = { TidePlayer.cycleRepeat() },
            )
            Box(modifier = Modifier.lightClickable(onClick = onOpenQueue)) {
                LightIcon(icon = LightIcons.LIST, size = 1.9f)
            }
        }
    }

    /** Bottom-bar download toggle for the current track. */
    @Composable
    private fun DownloadControl(downloaded: Boolean, downloading: Boolean, onClick: () -> Unit) {
        Column(
            modifier = Modifier.lightClickable(onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LightIcon(
                icon = if (downloaded) LightIcons.DOWNLOADED_ARROW else LightIcons.DOWNLOAD_ARROW,
                size = 1.9f,
                modifier = Modifier.alpha(if (downloading) 0.4f else 1f),
            )
            Spacer(modifier = Modifier.height(0.25f.gridUnitsAsDp()))
            Spacer(modifier = Modifier.height(0.13f.gridUnitsAsDp()))
        }
    }

    /** Circle save control: outlined + to like, filled ✓ once liked. */
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

    /** LightOS mode glyph with the active-state underline dash beneath. */
    @Composable
    private fun PlaybackModeIcon(
        icon: LightIconConfiguration,
        active: Boolean,
        onClick: () -> Unit,
        badge: String? = null,
    ) {
        val colors = LightThemeTokens.colors
        Column(
            modifier = Modifier.lightClickable(onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                LightIcon(icon = icon, size = 1.9f)
                badge?.let {
                    LightText(text = it, variant = LightTextVariant.Micro)
                }
            }
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

/**
 * Queue with drag-to-reorder: hold a row and drag vertically to move it,
 * or fling it sideways while dragging to remove it from the queue.
 */
class QueueScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, QueueViewModel>(sealedActivity) {

    override val viewModelClass: Class<QueueViewModel>
        get() = QueueViewModel::class.java

    override fun createViewModel() = QueueViewModel()

    @Composable
    override fun Content() {
        val queue by TidePlayer.queue.collectAsState()
        val index by TidePlayer.index.collectAsState()
        val positionMs by TidePlayer.positionMs.collectAsState()
        val downloadedIds by Downloads.downloadedIds.collectAsState()
        val haptics = LocalHapticFeedback.current

        TideScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("Queue"),
            )
            if (queue.isEmpty()) {
                EmptyText("Queue is empty")
            } else {
                val remainingSec = queue.drop(index.coerceAtLeast(0)).sumOf { it.durationSec } -
                    positionMs / 1000
                LightText(
                    text = "${(index + 1).coerceAtLeast(1)} of ${queue.size} · " +
                        "${(remainingSec / 60).coerceAtLeast(0)} min left · " +
                        "Hold then drag to reorder, fling aside to remove",
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                    modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                )

                val itemHeightPx = with(LocalDensity.current) { ROW_UNITS.gridUnitsAsDp().toPx() }
                val listState = rememberLazyListState(
                    initialFirstVisibleItemIndex = (index - 1).coerceAtLeast(0),
                )
                var draggingIndex by remember { mutableIntStateOf(-1) }
                var dragDy by remember { mutableFloatStateOf(0f) }
                var dragDx by remember { mutableFloatStateOf(0f) }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    items(queue.size) { i ->
                        val dragging = i == draggingIndex
                        Box(
                            modifier = Modifier
                                .zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer {
                                    if (dragging) {
                                        translationY = dragDy
                                        translationX = dragDx
                                        alpha = 0.85f
                                    }
                                }
                                .pointerInput(i) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            draggingIndex = i
                                            dragDy = 0f
                                            dragDx = 0f
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragDy += amount.y
                                            dragDx += amount.x
                                            while (dragDy > itemHeightPx / 2 &&
                                                draggingIndex < TidePlayer.queue.value.size - 1
                                            ) {
                                                TidePlayer.moveInQueue(draggingIndex, draggingIndex + 1)
                                                draggingIndex++
                                                dragDy -= itemHeightPx
                                            }
                                            while (dragDy < -itemHeightPx / 2 && draggingIndex > 0) {
                                                TidePlayer.moveInQueue(draggingIndex, draggingIndex - 1)
                                                draggingIndex--
                                                dragDy += itemHeightPx
                                            }
                                        },
                                        onDragEnd = {
                                            if (abs(dragDx) > size.width * 0.35f &&
                                                abs(dragDy) < itemHeightPx / 2
                                            ) {
                                                TidePlayer.removeFromQueue(draggingIndex)
                                            }
                                            draggingIndex = -1
                                            dragDy = 0f
                                            dragDx = 0f
                                        },
                                        onDragCancel = {
                                            draggingIndex = -1
                                            dragDy = 0f
                                            dragDx = 0f
                                        },
                                    )
                                },
                        ) {
                            NumberedTrackRow(
                                number = null,
                                track = queue[i],
                                active = i == index,
                                downloaded = queue[i].id in downloadedIds,
                                onClick = { TidePlayer.jumpTo(i) },
                            )
                        }
                    }
                }
                if (queue.size > 1) {
                    ActionRow(text = "Clear Queue", onClick = { TidePlayer.clearUpcoming() })
                }
            }
        }
    }
}
