package com.kezo.tide.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.kezo.tide.api.AuthPendingException
import com.kezo.tide.api.Tidal
import com.kezo.tide.player.TidePlayer
import com.kezo.tide.ui.TideScreen
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(dataStore: DataStore<Preferences>) : LightViewModel<Unit>() {

    sealed interface State {
        data object Loading : State
        data class LoggedOut(val error: String? = null) : State
        data class Linking(val code: String, val uri: String) : State
        data object Menu : State
    }

    val state = MutableStateFlow<State>(State.Loading)
    val nowPlaying = TidePlayer.current

    init {
        Tidal.init(dataStore)
        viewModelScope.launch {
            state.value = if (Tidal.restore()) State.Menu else State.LoggedOut()
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        // reflect a sign-out done from the settings screen
        if (state.value is State.Menu && !Tidal.loggedIn) {
            state.value = State.LoggedOut()
        }
    }

    fun startLink() {
        state.value = State.Loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val link = Tidal.startDeviceLogin()
                state.value = State.Linking(link.userCode, link.verificationUri)
                var waitedMs = 0L
                while (waitedMs < link.expiresInSec * 1000L) {
                    delay(link.intervalSec * 1000L)
                    waitedMs += link.intervalSec * 1000L
                    try {
                        Tidal.pollDeviceLogin(link.deviceCode)
                        state.value = State.Menu
                        return@launch
                    } catch (_: AuthPendingException) {
                        // user hasn't confirmed on the other device yet
                    }
                }
                state.value = State.LoggedOut("link expired — try again")
            } catch (e: Exception) {
                state.value = State.LoggedOut(e.message ?: "couldn't reach tidal")
            }
        }
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HomeViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeViewModel>
        get() = HomeViewModel::class.java

    override fun createViewModel() = HomeViewModel(lightContext.dataStore)

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val nowPlaying by viewModel.nowPlaying.collectAsState()

        TideScreen {
            LightTopBar(center = LightTopBarCenter.Text("tide"))
            when (val s = state) {
                is HomeViewModel.State.Loading -> {
                    Spacer(modifier = Modifier.height(2f.gridUnitsAsDp()))
                    LightText(
                        text = "one moment…",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                    )
                }

                is HomeViewModel.State.LoggedOut -> LoggedOutContent(s.error)
                is HomeViewModel.State.Linking -> LinkingContent(s.code, s.uri)
                is HomeViewModel.State.Menu -> MenuContent(nowPlayingTitle = nowPlaying?.title)
            }
        }
    }

    @Composable
    private fun LoggedOutContent(error: String?) {
        Column(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
            Spacer(modifier = Modifier.height(2f.gridUnitsAsDp()))
            LightText(
                text = "listen with your tidal subscription",
                variant = LightTextVariant.Paragraph,
                lighten = true,
            )
            error?.let {
                LightText(
                    text = it,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                )
            }
            LightText(
                text = "link account",
                variant = LightTextVariant.Copy,
                underline = true,
                modifier = Modifier
                    .padding(top = 2f.gridUnitsAsDp())
                    .lightClickable { viewModel.startLink() },
            )
        }
    }

    @Composable
    private fun LinkingContent(code: String, uri: String) {
        Column(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
            Spacer(modifier = Modifier.height(2f.gridUnitsAsDp()))
            LightText(
                text = "on another device, visit",
                variant = LightTextVariant.Paragraph,
                lighten = true,
            )
            LightText(
                text = uri.removePrefix("https://"),
                variant = LightTextVariant.Subheading,
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
            )
            LightText(
                text = "code",
                variant = LightTextVariant.Paragraph,
                lighten = true,
                modifier = Modifier.padding(top = 2f.gridUnitsAsDp()),
            )
            LightText(
                text = code,
                variant = LightTextVariant.Subtitle,
                monospace = true,
            )
            LightText(
                text = "waiting for confirmation…",
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 2f.gridUnitsAsDp()),
            )
        }
    }

    @Composable
    private fun MenuContent(nowPlayingTitle: String?) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            MenuItem("search") { navigateTo({ SearchScreen(it) }) }
            MenuItem("favorites") {
                navigateTo({ TrackListScreen(it, "favorites") { Tidal.favoriteTracks() } })
            }
            MenuItem("playlists") { navigateTo({ PlaylistListScreen(it) }) }
            MenuItem("albums") {
                navigateTo({ AlbumListScreen(it, "albums") { Tidal.favoriteAlbums() } })
            }
            MenuItem("artists") { navigateTo({ ArtistListScreen(it) }) }
            if (nowPlayingTitle != null) {
                MenuItem("now playing") { navigateTo({ PlayerScreen(it) }) }
            }
            MenuItem("settings") { navigateTo({ SettingsScreen(it) }) }

            if (nowPlayingTitle != null) {
                LightText(
                    text = "· $nowPlayingTitle",
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = 1f.gridUnitsAsDp())
                        .padding(top = 1f.gridUnitsAsDp())
                        .lightClickable { navigateTo({ PlayerScreen(it) }) },
                )
            }
        }
    }

    @Composable
    private fun MenuItem(label: String, onClick: () -> Unit) {
        LightText(
            text = label,
            variant = LightTextVariant.Copy,
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable(onClick = onClick)
                .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.6f.gridUnitsAsDp()),
        )
    }
}
