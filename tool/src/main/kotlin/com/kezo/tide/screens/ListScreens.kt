package com.kezo.tide.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.kezo.tide.api.Album
import com.kezo.tide.api.Artist
import com.kezo.tide.api.Mix
import com.kezo.tide.api.Tidal
import com.kezo.tide.api.Track
import com.kezo.tide.player.Downloads
import com.kezo.tide.player.TidePlayer
import com.kezo.tide.ui.EmptyText
import com.kezo.tide.ui.ErrorRetry
import com.kezo.tide.ui.LoadingText
import com.kezo.tide.ui.MediaRow
import com.kezo.tide.ui.NumberedTrackRow
import com.kezo.tide.ui.PlayerPresence
import com.kezo.tide.ui.ROW_UNITS
import com.kezo.tide.ui.TideScreen
import com.kezo.tide.ui.UiState
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

/** Loads any list of [T] through a suspend loader with retry support. */
class ListViewModel<T>(private val loader: suspend () -> List<T>) : LightViewModel<Unit>() {
    val state = MutableStateFlow<UiState<List<T>>>(UiState.Loading)

    init {
        load()
    }

    fun load() {
        state.value = UiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            state.value = try {
                UiState.Data(loader())
            } catch (e: Exception) {
                UiState.Failed(e.message ?: "Something went wrong")
            }
        }
    }
}

/** Track list VM; when [albumId] is set it also tracks album follow state. */
class TrackListViewModel(
    private val loader: suspend () -> List<Track>,
    private val albumId: Long,
) : LightViewModel<Unit>() {
    val state = MutableStateFlow<UiState<List<Track>>>(UiState.Loading)

    /** null = unknown / not an album */
    val albumFav = MutableStateFlow<Boolean?>(null)

    init {
        load()
        if (albumId != 0L) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    Tidal.ensureFavIds()
                    albumFav.value = albumId in Tidal.favAlbumIds
                } catch (_: Exception) {
                    // toggle still works optimistically
                }
            }
        }
    }

    fun load() {
        state.value = UiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            state.value = try {
                UiState.Data(loader())
            } catch (e: Exception) {
                UiState.Failed(e.message ?: "Something went wrong")
            }
        }
    }

    fun toggleAlbumFav() {
        if (albumId == 0L) return
        val currently = albumFav.value ?: false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (currently) Tidal.removeFavoriteAlbum(albumId)
                else Tidal.addFavoriteAlbum(albumId)
                albumFav.value = !currently
            } catch (_: Exception) {
                // leave state as-is; user can retry
            }
        }
    }
}

/** Any screen that shows a tappable list of tracks: an album, playlist, or top tracks. */
class TrackListScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val numbered: Boolean = false,
    private val albumId: Long = 0L,
    private val shuffleable: Boolean = false,
    private val loader: suspend () -> List<Track>,
) : LightScreen<Unit, TrackListViewModel>(sealedActivity) {

    override val viewModelClass: Class<TrackListViewModel>
        get() = TrackListViewModel::class.java

    override fun createViewModel() = TrackListViewModel(loader, albumId)

    /** Albums and playlists get top-of-list Play (and playlists, Shuffle). */
    private val playable: Boolean get() = albumId != 0L || shuffleable

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val current by TidePlayer.current.collectAsState()
        val albumFav by viewModel.albumFav.collectAsState()
        val downloadedIds by Downloads.downloadedIds.collectAsState()

        TideScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(title),
                rightButton = if (albumId != 0L) {
                    // follow/unfollow the album, like the artist page's star
                    LightBarButton.LightIcon(
                        if (albumFav == true) LightIcons.STAR else LightIcons.STAR_OUTLINE,
                        onClick = { viewModel.toggleAlbumFav() },
                    )
                } else {
                    LightBarButton.LightIcon(
                        LightIcons.AUDIO_MESSAGE,
                        onClick = {
                            if (PlayerPresence.openCount > 0) goBack()
                            else navigateTo({ a -> PlayerScreen(a) })
                        },
                    )
                },
            )
            when (val s = state) {
                is UiState.Loading -> LoadingText()
                is UiState.Failed -> ErrorRetry(s.message, onRetry = viewModel::load)
                is UiState.Data -> {
                    if (s.value.isEmpty()) {
                        EmptyText("Nothing here yet")
                    } else {
                        LightLazyScrollView(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            uniformItemHeightGridUnits = ROW_UNITS,
                        ) {
                            if (playable) {
                                item {
                                    PlayHeader(tracks = s.value, showShuffle = shuffleable)
                                }
                            }
                            items(s.value.size) { i ->
                                val track = s.value[i]
                                NumberedTrackRow(
                                    number = if (numbered) i + 1 else null,
                                    track = track,
                                    active = current?.id == track.id,
                                    downloaded = track.id in downloadedIds,
                                    onClick = {
                                        TidePlayer.play(s.value, i)
                                        navigateTo({ PlayerScreen(it) })
                                    },
                                    onLongClick = {
                                        navigateTo({ TrackOptionsScreen(it, track) })
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** Play / Shuffle / Download actions at the top of an album or playlist. */
    @Composable
    private fun PlayHeader(tracks: List<Track>, showShuffle: Boolean) {
        val downloadedIds by Downloads.downloadedIds.collectAsState()
        val downloadingIds by Downloads.downloadingIds.collectAsState()
        val allDownloaded = tracks.isNotEmpty() && tracks.all { it.id in downloadedIds }
        val anyDownloading = tracks.any { it.id in downloadingIds }
        fun openPlayer() {
            if (PlayerPresence.openCount > 0) goBack()
            else navigateTo({ a -> PlayerScreen(a) })
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ROW_UNITS.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayAction(
                modifier = Modifier.weight(1f),
                icon = LightIcons.PLAY,
                label = "Play",
            ) {
                TidePlayer.playInOrder(tracks)
                openPlayer()
            }
            if (showShuffle) {
                PlayAction(
                    modifier = Modifier.weight(1f),
                    icon = LightIcons.SHUFFLE,
                    label = "Shuffle",
                ) {
                    TidePlayer.playShuffled(tracks)
                    openPlayer()
                }
            }
            PlayAction(
                modifier = Modifier.weight(1f),
                icon = if (allDownloaded) LightIcons.DOWNLOADED_ARROW else LightIcons.DOWNLOAD_ARROW,
                label = when {
                    anyDownloading -> "Saving…"
                    allDownloaded -> "Downloaded"
                    else -> "Download"
                },
            ) {
                when {
                    anyDownloading -> Unit
                    allDownloaded -> Downloads.removeThese(tracks)
                    else -> Downloads.downloadAll(tracks)
                }
            }
        }
    }

    @Composable
    private fun PlayAction(
        modifier: Modifier,
        icon: com.thelightphone.sdk.ui.LightIconConfiguration,
        label: String,
        onClick: () -> Unit,
    ) {
        Row(
            modifier = modifier.lightClickable(onClick = onClick),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightIcon(icon = icon, size = 1.35f, modifier = Modifier.padding(end = 0.4f.gridUnitsAsDp()))
            LightText(text = label, variant = LightTextVariant.Detail)
        }
    }
}

/** The user's favorite artists (reached from Settings, echo-style). */
class ArtistListScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, ListViewModel<Artist>>(sealedActivity) {

    @Suppress("UNCHECKED_CAST")
    override val viewModelClass: Class<ListViewModel<Artist>>
        get() = ListViewModel::class.java as Class<ListViewModel<Artist>>

    override fun createViewModel() = ListViewModel { Tidal.favoriteArtists() }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        TideScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("Artists"),
                rightButton = LightBarButton.LightIcon(
                    LightIcons.AUDIO_MESSAGE,
                    onClick = {
                        if (PlayerPresence.openCount > 0) goBack()
                        else navigateTo({ a -> PlayerScreen(a) })
                    },
                ),
            )
            when (val s = state) {
                is UiState.Loading -> LoadingText()
                is UiState.Failed -> ErrorRetry(s.message, onRetry = viewModel::load)
                is UiState.Data -> {
                    if (s.value.isEmpty()) {
                        EmptyText("Nothing here yet")
                    } else {
                        LightLazyScrollView(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            uniformItemHeightGridUnits = ROW_UNITS,
                        ) {
                            items(s.value.size) { i ->
                                val artist = s.value[i]
                                MediaRow(
                                    primary = artist.name,
                                    secondary = "",
                                    onClick = { navigateTo({ ArtistScreen(it, artist) }) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


/** A tappable list of albums (artist discography, new releases). */
class AlbumListScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val loader: suspend () -> List<Album>,
) : LightScreen<Unit, ListViewModel<Album>>(sealedActivity) {

    @Suppress("UNCHECKED_CAST")
    override val viewModelClass: Class<ListViewModel<Album>>
        get() = ListViewModel::class.java as Class<ListViewModel<Album>>

    override fun createViewModel() = ListViewModel(loader)

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        TideScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(title),
                rightButton = LightBarButton.LightIcon(
                    LightIcons.AUDIO_MESSAGE,
                    onClick = {
                        if (PlayerPresence.openCount > 0) goBack()
                        else navigateTo({ a -> PlayerScreen(a) })
                    },
                ),
            )
            when (val s = state) {
                is UiState.Loading -> LoadingText()
                is UiState.Failed -> ErrorRetry(s.message, onRetry = viewModel::load)
                is UiState.Data -> {
                    if (s.value.isEmpty()) {
                        EmptyText("Nothing here yet")
                    } else {
                        LightLazyScrollView(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            uniformItemHeightGridUnits = ROW_UNITS,
                        ) {
                            items(s.value.size) { i ->
                                val album = s.value[i]
                                MediaRow(
                                    primary = album.title,
                                    secondary = listOf(album.artist, album.year)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" · "),
                                    onClick = {
                                        navigateTo({
                                            TrackListScreen(
                                                it, album.title,
                                                numbered = true,
                                                albumId = album.id,
                                            ) { Tidal.albumTracks(album.id) }
                                        })
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** All of the user's TIDAL mixes. */
class MixListScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, ListViewModel<Mix>>(sealedActivity) {

    @Suppress("UNCHECKED_CAST")
    override val viewModelClass: Class<ListViewModel<Mix>>
        get() = ListViewModel::class.java as Class<ListViewModel<Mix>>

    override fun createViewModel() = ListViewModel { Tidal.myMixes() }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        TideScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("My Mixes"),
                rightButton = LightBarButton.LightIcon(
                    LightIcons.AUDIO_MESSAGE,
                    onClick = {
                        if (PlayerPresence.openCount > 0) goBack()
                        else navigateTo({ a -> PlayerScreen(a) })
                    },
                ),
            )
            when (val s = state) {
                is UiState.Loading -> LoadingText()
                is UiState.Failed -> ErrorRetry(s.message, onRetry = viewModel::load)
                is UiState.Data -> {
                    if (s.value.isEmpty()) {
                        EmptyText("Nothing here yet")
                    } else {
                        LightLazyScrollView(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            uniformItemHeightGridUnits = ROW_UNITS,
                        ) {
                            items(s.value.size) { i ->
                                val mix = s.value[i]
                                MediaRow(
                                    primary = mix.title,
                                    secondary = mix.subtitle,
                                    onClick = {
                                        navigateTo({
                                            TrackListScreen(it, mix.title) { Tidal.mixTracks(mix.id) }
                                        })
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
