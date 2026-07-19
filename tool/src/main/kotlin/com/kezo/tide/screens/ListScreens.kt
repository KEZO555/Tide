package com.kezo.tide.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.kezo.tide.api.Artist
import com.kezo.tide.api.Tidal
import com.kezo.tide.api.Track
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
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
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

/** Any screen that shows a tappable list of tracks: an album, playlist, or top tracks. */
class TrackListScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val numbered: Boolean = false,
    private val loader: suspend () -> List<Track>,
) : LightScreen<Unit, ListViewModel<Track>>(sealedActivity) {

    @Suppress("UNCHECKED_CAST")
    override val viewModelClass: Class<ListViewModel<Track>>
        get() = ListViewModel::class.java as Class<ListViewModel<Track>>

    override fun createViewModel() = ListViewModel(loader)

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val current by TidePlayer.current.collectAsState()

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
                                val track = s.value[i]
                                NumberedTrackRow(
                                    number = if (numbered) i + 1 else null,
                                    track = track,
                                    active = current?.id == track.id,
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
