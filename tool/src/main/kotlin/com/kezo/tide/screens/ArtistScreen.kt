package com.kezo.tide.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.kezo.tide.api.Album
import com.kezo.tide.api.Artist
import com.kezo.tide.api.Tidal
import com.kezo.tide.api.Track
import com.kezo.tide.player.TidePlayer
import com.kezo.tide.ui.ErrorRetry
import com.kezo.tide.ui.LoadingText
import com.kezo.tide.ui.MediaRow
import com.kezo.tide.ui.NumberedTrackRow
import com.kezo.tide.ui.PlayerPresence
import com.kezo.tide.ui.SectionHeader
import com.kezo.tide.ui.TideScreen
import com.kezo.tide.ui.UiState
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private const val TOP_SONGS_SHOWN = 5

class ArtistViewModel(private val artist: Artist) : LightViewModel<Unit>() {
    val topTracks = MutableStateFlow<UiState<List<Track>>>(UiState.Loading)
    val albums = MutableStateFlow<UiState<List<Album>>>(UiState.Loading)
    val similar = MutableStateFlow<List<Artist>>(emptyList())

    /** null = unknown, true/false = favorite state */
    val favorite = MutableStateFlow<Boolean?>(null)

    init {
        load()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                favorite.value = Tidal.favoriteArtists().any { it.id == artist.id }
            } catch (_: Exception) {
                // leave unknown; toggle will still work optimistically
            }
        }
    }

    fun load() {
        topTracks.value = UiState.Loading
        albums.value = UiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            topTracks.value = try {
                UiState.Data(Tidal.artistTopTracks(artist.id))
            } catch (e: Exception) {
                UiState.Failed(e.message ?: "Something went wrong")
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            albums.value = try {
                UiState.Data(Tidal.artistAlbums(artist.id))
            } catch (e: Exception) {
                UiState.Failed(e.message ?: "Something went wrong")
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            similar.value = Tidal.similarArtists(artist.id).take(8)
        }
    }

    fun toggleFavorite() {
        val currently = favorite.value ?: false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (currently) Tidal.removeFavoriteArtist(artist.id)
                else Tidal.addFavoriteArtist(artist.id)
                favorite.value = !currently
            } catch (_: Exception) {
                // leave state as-is; user can retry
            }
        }
    }
}

/**
 * Artist page: Top Songs and Albums inline (no menu), favorite star in the
 * header.
 */
class ArtistScreen(
    sealedActivity: SealedLightActivity,
    private val artist: Artist,
) : LightScreen<Unit, ArtistViewModel>(sealedActivity) {

    override val viewModelClass: Class<ArtistViewModel>
        get() = ArtistViewModel::class.java

    override fun createViewModel() = ArtistViewModel(artist)

    @Composable
    override fun Content() {
        val topTracks by viewModel.topTracks.collectAsState()
        val albums by viewModel.albums.collectAsState()
        val favorite by viewModel.favorite.collectAsState()
        val current by TidePlayer.current.collectAsState()

        TideScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(artist.name),
                rightButton = LightBarButton.LightIcon(
                    if (favorite == true) LightIcons.STAR else LightIcons.STAR_OUTLINE,
                    onClick = { viewModel.toggleFavorite() },
                ),
            )
            LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth()) {
                SectionHeader("Top Songs")
                when (val s = topTracks) {
                    is UiState.Loading -> LoadingText()
                    is UiState.Failed -> ErrorRetry(s.message, onRetry = viewModel::load)
                    is UiState.Data -> {
                        val shown = s.value.take(TOP_SONGS_SHOWN)
                        shown.forEachIndexed { i, track ->
                            NumberedTrackRow(
                                number = null,
                                track = track,
                                active = current?.id == track.id,
                                onClick = {
                                    TidePlayer.play(s.value, i)
                                    if (PlayerPresence.openCount == 0) {
                                        navigateTo({ PlayerScreen(it) })
                                    } else {
                                        goBack()
                                    }
                                },
                                onLongClick = {
                                    navigateTo({ TrackOptionsScreen(it, track) })
                                },
                            )
                        }
                    }
                }

                SectionHeader("Albums")
                when (val s = albums) {
                    is UiState.Loading -> LoadingText()
                    is UiState.Failed -> ErrorRetry(s.message, onRetry = viewModel::load)
                    is UiState.Data -> {
                        s.value.forEach { album ->
                            MediaRow(
                                primary = album.title,
                                secondary = album.year,
                                onClick = {
                                    navigateTo({
                                        TrackListScreen(it, album.title, numbered = true) {
                                            Tidal.albumTracks(album.id)
                                        }
                                    })
                                },
                            )
                        }
                    }
                }
                val similarArtists by viewModel.similar.collectAsState()
                if (similarArtists.isNotEmpty()) {
                    SectionHeader("Similar Artists")
                    similarArtists.forEach { other ->
                        MediaRow(
                            primary = other.name,
                            secondary = "",
                            onClick = { navigateTo({ ArtistScreen(it, other) }) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
            }
        }
    }
}
