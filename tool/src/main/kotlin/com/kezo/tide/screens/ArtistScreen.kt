package com.kezo.tide.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.kezo.tide.api.Artist
import com.kezo.tide.api.Tidal
import com.kezo.tide.ui.TideScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ArtistViewModel(private val artist: Artist) : LightViewModel<Unit>() {
    /** null = unknown, true/false = favorite state */
    val favorite = MutableStateFlow<Boolean?>(null)
    val status = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                favorite.value = Tidal.favoriteArtists().any { it.id == artist.id }
            } catch (_: Exception) {
                // leave unknown; toggle will still work optimistically
            }
        }
    }

    fun toggleFavorite() {
        val currently = favorite.value ?: false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (currently) Tidal.removeFavoriteArtist(artist.id)
                else Tidal.addFavoriteArtist(artist.id)
                favorite.value = !currently
                status.value = null
            } catch (e: Exception) {
                status.value = e.message ?: "couldn't update favorite"
            }
        }
    }
}

class ArtistScreen(
    sealedActivity: SealedLightActivity,
    private val artist: Artist,
) : LightScreen<Unit, ArtistViewModel>(sealedActivity) {

    override val viewModelClass: Class<ArtistViewModel>
        get() = ArtistViewModel::class.java

    override fun createViewModel() = ArtistViewModel(artist)

    @Composable
    override fun Content() {
        val favorite by viewModel.favorite.collectAsState()
        val status by viewModel.status.collectAsState()

        TideScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(artist.name),
            )
            Column(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                Item("top tracks") {
                    navigateTo({
                        TrackListScreen(it, artist.name) { Tidal.artistTopTracks(artist.id) }
                    })
                }
                Item("albums") {
                    navigateTo({
                        AlbumListScreen(it, artist.name) { Tidal.artistAlbums(artist.id) }
                    })
                }
                Item(
                    when (favorite) {
                        true -> "unfavorite"
                        false -> "favorite"
                        null -> "favorite…"
                    }
                ) { viewModel.toggleFavorite() }

                status?.let {
                    LightText(
                        text = it,
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }

    @Composable
    private fun Item(label: String, onClick: () -> Unit) {
        LightText(
            text = label,
            variant = LightTextVariant.Copy,
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable(onClick = onClick)
                .padding(vertical = 0.6f.gridUnitsAsDp()),
        )
    }
}
