package com.kezo.tide.screens

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.kezo.tide.api.SearchResults
import com.kezo.tide.api.Tidal
import com.kezo.tide.player.TidePlayer
import com.kezo.tide.ui.EmptyText
import com.kezo.tide.ui.ErrorRetry
import com.kezo.tide.ui.LoadingText
import com.kezo.tide.ui.SectionHeader
import com.kezo.tide.ui.TideScreen
import com.kezo.tide.ui.TrackRow
import com.kezo.tide.ui.TwoLineRow
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class SearchViewModel : LightViewModel<Unit>() {

    sealed interface Mode {
        data object Input : Mode
        data class Searching(val query: String) : Mode
        data class Results(val query: String, val results: SearchResults) : Mode
        data class Failed(val query: String, val message: String) : Mode
    }

    val mode = MutableStateFlow<Mode>(Mode.Input)
    var inputSession = 0
        private set

    fun submit(query: CharSequence) {
        val q = query.toString().trim()
        if (q.isEmpty()) return
        mode.value = Mode.Searching(q)
        viewModelScope.launch(Dispatchers.IO) {
            mode.value = try {
                Mode.Results(q, Tidal.search(q))
            } catch (e: Exception) {
                Mode.Failed(q, e.message ?: "search failed")
            }
        }
    }

    fun newSearch() {
        inputSession++
        mode.value = Mode.Input
    }

    override fun onBackPressed(): Boolean {
        // back from results returns to the input, back from input leaves the screen
        return if (mode.value !is Mode.Input) {
            newSearch()
            true
        } else {
            false
        }
    }
}

class SearchScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SearchViewModel>(sealedActivity) {

    override val viewModelClass: Class<SearchViewModel>
        get() = SearchViewModel::class.java

    override fun createViewModel() = SearchViewModel()

    @Composable
    override fun Content() {
        val mode by viewModel.mode.collectAsState()
        val textFieldState = rememberTextFieldState("")
        val keyboardOptionsFlow = rememberKeyboardOptions()
        val current by TidePlayer.current.collectAsState()

        TideScreen {
            when (val m = mode) {
                is SearchViewModel.Mode.Input -> {
                    LightTextInputEditor(
                        title = "search",
                        editorKey = viewModel.inputSession,
                        state = textFieldState,
                        onSubmit = viewModel::submit,
                        onBack = { goBack() },
                        keyboardOptionsFlow = keyboardOptionsFlow,
                        submitIcon = LightIcons.SEARCH,
                        singleLine = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                is SearchViewModel.Mode.Searching -> {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                        center = LightTopBarCenter.Text(m.query),
                    )
                    LoadingText()
                }

                is SearchViewModel.Mode.Failed -> {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                        center = LightTopBarCenter.Text(m.query),
                    )
                    ErrorRetry(m.message, onRetry = { viewModel.submit(m.query) })
                }

                is SearchViewModel.Mode.Results -> {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                        center = LightTopBarCenter.Text(m.query),
                        rightButton = LightBarButton.LightIcon(
                            LightIcons.SEARCH,
                            onClick = { viewModel.newSearch() },
                        ),
                    )
                    ResultsContent(m.results, currentTrackId = current?.id)
                }
            }
        }
    }

    @Composable
    private fun ColumnScope.ResultsContent(results: SearchResults, currentTrackId: Long?) {
        val empty = results.tracks.isEmpty() && results.albums.isEmpty() &&
            results.artists.isEmpty() && results.playlists.isEmpty()
        if (empty) {
            EmptyText("no results")
            return
        }
        LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (results.tracks.isNotEmpty()) {
                SectionHeader("tracks")
                results.tracks.forEachIndexed { i, track ->
                    TrackRow(
                        track = track,
                        active = currentTrackId == track.id,
                        onClick = {
                            TidePlayer.play(results.tracks, i)
                            navigateTo({ PlayerScreen(it) })
                        },
                    )
                }
            }
            if (results.artists.isNotEmpty()) {
                SectionHeader("artists")
                results.artists.forEach { artist ->
                    TwoLineRow(
                        primary = artist.name,
                        secondary = "",
                        onClick = { navigateTo({ ArtistScreen(it, artist) }) },
                    )
                }
            }
            if (results.albums.isNotEmpty()) {
                SectionHeader("albums")
                results.albums.forEach { album ->
                    TwoLineRow(
                        primary = album.title,
                        secondary = listOf(album.artist, album.year)
                            .filter { it.isNotBlank() }
                            .joinToString("  ·  "),
                        onClick = {
                            navigateTo({
                                TrackListScreen(it, album.title) { Tidal.albumTracks(album.id) }
                            })
                        },
                    )
                }
            }
            if (results.playlists.isNotEmpty()) {
                SectionHeader("playlists")
                results.playlists.forEach { playlist ->
                    TwoLineRow(
                        primary = playlist.title,
                        secondary = "${playlist.numberOfTracks} tracks",
                        onClick = {
                            navigateTo({
                                TrackListScreen(it, playlist.title) {
                                    Tidal.playlistTracks(playlist.uuid)
                                }
                            })
                        },
                    )
                }
            }
        }
    }
}
