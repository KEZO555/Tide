package com.kezo.tide.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.kezo.tide.TidePrefs
import com.kezo.tide.api.AuthPendingException
import com.kezo.tide.api.SearchResults
import com.kezo.tide.api.Tidal
import com.kezo.tide.api.Track
import com.kezo.tide.player.Recents
import com.kezo.tide.player.TidePlayer
import com.kezo.tide.ui.ActionRow
import com.kezo.tide.ui.EmptyText
import com.kezo.tide.ui.ErrorRetry
import com.kezo.tide.ui.HomeSectionHeader
import com.kezo.tide.ui.LoadingText
import com.kezo.tide.ui.MediaRow
import com.kezo.tide.ui.NumberedTrackRow
import com.kezo.tide.ui.PlayerPresence
import com.kezo.tide.ui.ROW_UNITS
import com.kezo.tide.ui.SectionHeader
import com.kezo.tide.ui.SectionLabel
import com.kezo.tide.ui.ShowAllRow
import com.kezo.tide.ui.TabBar
import com.kezo.tide.ui.TabIcon
import com.kezo.tide.ui.TextButton
import com.kezo.tide.ui.TideScreen
import com.kezo.tide.ui.ToggleRow
import com.kezo.tide.ui.UiState
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeColors
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.designVerticalPxToDp
import com.thelightphone.sdk.ui.designVerticalPxToSp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val QUALITIES = listOf("LOW", "HIGH", "LOSSLESS")

enum class MainTab { Home, Liked, Albums, Playlists, Search, Settings }

fun tabForId(id: String): MainTab = when (id) {
    "liked" -> MainTab.Liked
    "albums" -> MainTab.Albums
    "playlists" -> MainTab.Playlists
    "search" -> MainTab.Search
    "settings" -> MainTab.Settings
    else -> MainTab.Home
}

fun tabIcon(id: String): TabIcon = when (id) {
    "liked" -> TabIcon.Vector(Icons.Filled.Favorite)
    "albums" -> TabIcon.Vector(Icons.Filled.Album)
    "playlists" -> TabIcon.Light(LightIcons.LIST)
    "search" -> TabIcon.Vector(Icons.Filled.Search)
    "settings" -> TabIcon.Vector(Icons.Filled.MoreHoriz)
    else -> TabIcon.Vector(Icons.Filled.Home)
}

class MainViewModel(dataStore: DataStore<Preferences>) : LightViewModel<Unit>() {

    sealed interface Session {
        data object Loading : Session
        data class LoggedOut(val error: String? = null) : Session
        data class Linking(val code: String, val uri: String) : Session
        data object Ready : Session
    }

    sealed interface SearchMode {
        data object Input : SearchMode
        data class Searching(val query: String) : SearchMode
        data class Results(val query: String, val results: SearchResults) : SearchMode
        data class Failed(val query: String, val message: String) : SearchMode
    }

    val session = MutableStateFlow<Session>(Session.Loading)
    val tab = MutableStateFlow(MainTab.Home)

    val liked = MutableStateFlow<UiState<List<Track>>>(UiState.Loading)
    val albums = MutableStateFlow<UiState<List<com.kezo.tide.api.Album>>>(UiState.Loading)
    val playlists = MutableStateFlow<UiState<List<com.kezo.tide.api.Playlist>>>(UiState.Loading)

    val searchMode = MutableStateFlow<SearchMode>(SearchMode.Input)
    var searchSession = 0
        private set

    val quality = MutableStateFlow("HIGH")

    val homeArtists = MutableStateFlow<UiState<List<com.kezo.tide.api.Artist>>>(UiState.Loading)
    val homeMixes = MutableStateFlow<UiState<List<com.kezo.tide.api.Mix>>>(UiState.Loading)
    val homeReleases = MutableStateFlow<UiState<List<com.kezo.tide.api.Album>>>(UiState.Loading)

    private val loadedTabs = HashSet<MainTab>()

    init {
        Tidal.init(dataStore)
        Recents.init(dataStore)
        TidePrefs.init(dataStore)
        viewModelScope.launch {
            if (Tidal.restore()) {
                quality.value = Tidal.quality
                session.value = Session.Ready
                ensureLoaded(MainTab.Home)
                ensureLoaded(MainTab.Liked)
            } else {
                session.value = Session.LoggedOut()
            }
        }
    }

    fun selectTab(t: MainTab) {
        tab.value = t
        ensureLoaded(t)
    }

    private fun ensureLoaded(t: MainTab) {
        if (t in loadedTabs) return
        when (t) {
            MainTab.Home -> reloadHomeArtists()
            MainTab.Liked -> reloadLiked()
            MainTab.Albums -> reloadAlbums()
            MainTab.Playlists -> reloadPlaylists()
            else -> return
        }
        loadedTabs.add(t)
    }

    fun reloadHomeArtists() {
        homeArtists.value = UiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            homeArtists.value = try {
                UiState.Data(Tidal.favoriteArtists())
            } catch (e: Exception) {
                UiState.Failed(e.message ?: "Something went wrong")
            }
        }
        homeMixes.value = UiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            homeMixes.value = UiState.Data(Tidal.myMixes())
        }
        homeReleases.value = UiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            homeReleases.value = try {
                UiState.Data(Tidal.newReleases())
            } catch (_: Exception) {
                UiState.Data(emptyList())
            }
        }
    }

    fun reloadLiked() {
        liked.value = UiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            liked.value = try {
                UiState.Data(Tidal.favoriteTracks())
            } catch (e: Exception) {
                UiState.Failed(e.message ?: "Something went wrong")
            }
        }
    }

    fun reloadAlbums() {
        albums.value = UiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            albums.value = try {
                UiState.Data(Tidal.favoriteAlbums())
            } catch (e: Exception) {
                UiState.Failed(e.message ?: "Something went wrong")
            }
        }
    }

    fun reloadPlaylists() {
        playlists.value = UiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            playlists.value = try {
                UiState.Data(Tidal.playlists())
            } catch (e: Exception) {
                UiState.Failed(e.message ?: "Something went wrong")
            }
        }
    }

    // ---------- login ----------

    fun startLink() {
        session.value = Session.Loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val link = Tidal.startDeviceLogin()
                session.value = Session.Linking(link.userCode, link.verificationUri)
                var waitedMs = 0L
                while (waitedMs < link.expiresInSec * 1000L) {
                    delay(link.intervalSec * 1000L)
                    waitedMs += link.intervalSec * 1000L
                    try {
                        Tidal.pollDeviceLogin(link.deviceCode)
                        quality.value = Tidal.quality
                        session.value = Session.Ready
                        ensureLoaded(MainTab.Home)
                        ensureLoaded(MainTab.Liked)
                        return@launch
                    } catch (_: AuthPendingException) {
                        // user hasn't confirmed on the other device yet
                    }
                }
                session.value = Session.LoggedOut("Link expired, try again")
            } catch (e: Exception) {
                session.value = Session.LoggedOut(e.message ?: "Couldn't reach TIDAL")
            }
        }
    }

    // ---------- search ----------

    fun submitSearch(query: CharSequence) {
        val q = query.toString().trim()
        if (q.isEmpty()) return
        searchMode.value = SearchMode.Searching(q)
        viewModelScope.launch(Dispatchers.IO) {
            searchMode.value = try {
                SearchMode.Results(q, Tidal.search(q))
            } catch (e: Exception) {
                SearchMode.Failed(q, e.message ?: "Search failed")
            }
        }
    }

    fun newSearch() {
        searchSession++
        searchMode.value = SearchMode.Input
    }

    // ---------- settings ----------

    fun setQuality(value: String) {
        if (value !in QUALITIES) return
        quality.value = value
        viewModelScope.launch { Tidal.setQuality(value) }
    }

    fun signOut() {
        TidePlayer.stop()
        Recents.clear()
        viewModelScope.launch {
            Tidal.logout()
            loadedTabs.clear()
            liked.value = UiState.Loading
            albums.value = UiState.Loading
            playlists.value = UiState.Loading
            tab.value = MainTab.Home
            session.value = Session.LoggedOut()
        }
    }

    fun firstEnabledTab(): MainTab =
        TidePrefs.navTabs.value.firstOrNull { it.enabled }?.let { tabForId(it.id) } ?: MainTab.Home

    override fun onBackPressed(): Boolean {
        if (session.value !is Session.Ready) return false
        if (tab.value == MainTab.Search && searchMode.value !is SearchMode.Input) {
            newSearch()
            return true
        }
        val homeTab = firstEnabledTab()
        if (tab.value != homeTab) {
            tab.value = homeTab
            return true
        }
        return false
    }
}

@InitialScreen
class MainScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, MainViewModel>(sealedActivity) {

    override val viewModelClass: Class<MainViewModel>
        get() = MainViewModel::class.java

    override fun createViewModel() = MainViewModel(lightContext.dataStore)

    @Composable
    override fun Content() {
        val session by viewModel.session.collectAsState()

        TideScreen {
            when (val s = session) {
                is MainViewModel.Session.Loading -> {
                    LightTopBar(center = LightTopBarCenter.Text("Tide"))
                    LoadingText(modifier = Modifier.padding(top = 1f.gridUnitsAsDp()))
                }

                is MainViewModel.Session.LoggedOut -> LoggedOutContent(s.error)
                is MainViewModel.Session.Linking -> LinkingContent(s.code, s.uri)
                is MainViewModel.Session.Ready -> ReadyContent()
            }
        }
    }

    // ---------- login ----------

    @Composable
    private fun LoggedOutContent(error: String?) {
        Column(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
            Spacer(modifier = Modifier.height(3f.gridUnitsAsDp()))
            LightText(text = "Tide", variant = LightTextVariant.Subtitle)
            LightText(
                text = "A minimal TIDAL client for the Light Phone III.",
                variant = LightTextVariant.Fine,
                lighten = true,
                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
            )
            error?.let {
                LightText(
                    text = it,
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                )
            }
            Spacer(modifier = Modifier.height(2f.gridUnitsAsDp()))
            TextButton(text = "Link TIDAL Account", onClick = viewModel::startLink)
        }
    }

    @Composable
    private fun LinkingContent(code: String, uri: String) {
        Column(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
            Spacer(modifier = Modifier.height(2f.gridUnitsAsDp()))
            LightText(
                text = "On another device, visit",
                variant = LightTextVariant.Fine,
                lighten = true,
            )
            LightText(
                text = uri.removePrefix("https://"),
                variant = LightTextVariant.Subheading,
                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
            )
            LightText(
                text = "Code",
                variant = LightTextVariant.Fine,
                lighten = true,
                modifier = Modifier.padding(top = 2f.gridUnitsAsDp()),
            )
            LightText(
                text = code,
                variant = LightTextVariant.Subtitle,
                monospace = true,
            )
            LightText(
                text = "Waiting for confirmation...",
                variant = LightTextVariant.Superfine,
                lighten = true,
                modifier = Modifier.padding(top = 2f.gridUnitsAsDp()),
            )
        }
    }

    // ---------- tabs ----------

    @Composable
    private fun ColumnScope.ReadyContent() {
        val tab by viewModel.tab.collectAsState()
        val navPrefs by TidePrefs.navTabs.collectAsState()
        val enabled = navPrefs.filter { it.enabled }

        // if the current tab was hidden from settings, hop to the first shown one
        LaunchedEffect(enabled, tab) {
            if (enabled.none { tabForId(it.id) == tab }) {
                viewModel.selectTab(viewModel.firstEnabledTab())
            }
        }

        Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                MainTab.Home -> HomeTab()
                MainTab.Liked -> LikedTab()
                MainTab.Albums -> AlbumsTab()
                MainTab.Playlists -> PlaylistsTab()
                MainTab.Search -> SearchTab()
                MainTab.Settings -> SettingsTab()
            }
        }

        TabBar(
            tabs = enabled.map { pref -> tabIcon(pref.id) to (tabForId(pref.id) == tab) },
            onSelect = { i -> viewModel.selectTab(tabForId(enabled[i].id)) },
        )
    }

    @Composable
    private fun TabHeader(title: String) {
        // Phono-style header: centered title with the now-playing waveform
        // logo in the top-right on every screen.
        LightTopBar(
            center = LightTopBarCenter.Text(title),
            rightButton = LightBarButton.LightIcon(
                LightIcons.AUDIO_MESSAGE,
                onClick = {
                    if (PlayerPresence.openCount > 0) goBack()
                    else navigateTo({ a -> PlayerScreen(a) })
                },
            ),
        )
    }

    @Composable
    private fun ColumnScope.HomeTab() {
        val sections by TidePrefs.homeSections.collectAsState()
        TabHeader("Home")
        LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth()) {
            sections.filter { it.enabled }.forEach { pref ->
                when (pref.id) {
                    "recents" -> HomeRecents()
                    "artists" -> HomeArtists()
                    "mixes" -> HomeMixes()
                    "releases" -> HomeReleases()
                }
            }
            Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
        }
    }

    @Composable
    private fun HomeRecents() {
        val recents by Recents.tracks.collectAsState()
        val current by TidePlayer.current.collectAsState()
        HomeSectionHeader("Recently Played")
        if (recents.isEmpty()) {
            LightText(
                text = "Play something and it will show up here.",
                variant = LightTextVariant.Fine,
                lighten = true,
                modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
            )
        } else {
            recents.take(5).forEachIndexed { i, track ->
                NumberedTrackRow(
                    number = null,
                    track = track,
                    active = current?.id == track.id,
                    onClick = {
                        TidePlayer.play(recents, i)
                        navigateTo({ PlayerScreen(it) })
                    },
                    onLongClick = {
                        navigateTo({ TrackOptionsScreen(it, track) })
                    },
                )
            }
            if (recents.size > 5) {
                ShowAllRow {
                    navigateTo({
                        TrackListScreen(it, "Recently Played") { Recents.tracks.value }
                    })
                }
            }
        }
    }

    @Composable
    private fun HomeArtists() {
        val artists by viewModel.homeArtists.collectAsState()
        HomeSectionHeader("Artists")
        when (val s = artists) {
            is UiState.Loading -> LoadingText()
            is UiState.Failed -> ErrorRetry(s.message, onRetry = viewModel::reloadHomeArtists)
            is UiState.Data -> {
                if (s.value.isEmpty()) {
                    EmptyText("Favorite an artist and it will show up here.")
                } else {
                    s.value.take(5).forEach { artist ->
                        MediaRow(
                            primary = artist.name,
                            secondary = "",
                            onClick = { navigateTo({ ArtistScreen(it, artist) }) },
                        )
                    }
                    if (s.value.size > 5) {
                        ShowAllRow { navigateTo({ ArtistListScreen(it) }) }
                    }
                }
            }
        }
    }

    @Composable
    private fun HomeMixes() {
        val mixes by viewModel.homeMixes.collectAsState()
        val s = mixes
        // mixes come from a best-effort endpoint; hide the section when empty
        if (s is UiState.Data && s.value.isEmpty()) return
        HomeSectionHeader("My Mixes")
        when (s) {
            is UiState.Loading -> LoadingText()
            is UiState.Failed -> Unit
            is UiState.Data -> {
                s.value.take(4).forEach { mix ->
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
                if (s.value.size > 4) {
                    ShowAllRow { navigateTo({ MixListScreen(it) }) }
                }
            }
        }
    }

    @Composable
    private fun HomeReleases() {
        val releases by viewModel.homeReleases.collectAsState()
        val s = releases
        if (s is UiState.Data && s.value.isEmpty()) return
        HomeSectionHeader("New Releases")
        when (s) {
            is UiState.Loading -> LoadingText()
            is UiState.Failed -> Unit
            is UiState.Data -> {
                s.value.take(4).forEach { album ->
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
                if (s.value.size > 4) {
                    ShowAllRow {
                        navigateTo({
                            AlbumListScreen(it, "New Releases") { Tidal.newReleases() }
                        })
                    }
                }
            }
        }
    }

    @Composable
    private fun ColumnScope.LikedTab() {
        val state by viewModel.liked.collectAsState()
        val current by TidePlayer.current.collectAsState()
        TabHeader("Liked Songs")
        when (val s = state) {
            is UiState.Loading -> LoadingText()
            is UiState.Failed -> ErrorRetry(s.message, onRetry = viewModel::reloadLiked)
            is UiState.Data -> {
                if (s.value.isEmpty()) {
                    EmptyText("No liked songs yet")
                } else {
                    LightLazyScrollView(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        uniformItemHeightGridUnits = ROW_UNITS,
                    ) {
                        items(s.value.size) { i ->
                            val track = s.value[i]
                            NumberedTrackRow(
                                number = null,
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

    @Composable
    private fun ColumnScope.AlbumsTab() {
        val state by viewModel.albums.collectAsState()
        TabHeader("Albums")
        when (val s = state) {
            is UiState.Loading -> LoadingText()
            is UiState.Failed -> ErrorRetry(s.message, onRetry = viewModel::reloadAlbums)
            is UiState.Data -> {
                if (s.value.isEmpty()) {
                    EmptyText("No saved albums yet")
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

    @Composable
    private fun ColumnScope.PlaylistsTab() {
        val state by viewModel.playlists.collectAsState()
        TabHeader("Playlists")
        when (val s = state) {
            is UiState.Loading -> LoadingText()
            is UiState.Failed -> ErrorRetry(s.message, onRetry = viewModel::reloadPlaylists)
            is UiState.Data -> {
                if (s.value.isEmpty()) {
                    EmptyText("No playlists yet")
                } else {
                    LightLazyScrollView(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        uniformItemHeightGridUnits = ROW_UNITS,
                    ) {
                        items(s.value.size) { i ->
                            val playlist = s.value[i]
                            MediaRow(
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
    }

    // ---------- search ----------

    /** Inline search field using the device's own keyboard (system IME). */
    @Composable
    private fun SearchField() {
        val colors = LightThemeTokens.colors
        var query by remember(viewModel.searchSession) { mutableStateOf("") }
        val focusRequester = remember { FocusRequester() }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
        ) {
            LightText(text = "Search:", variant = LightTextVariant.Detail, lighten = true)
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                // LightTextField value size (Copy), scaled for screen height the
                // same way LightText scales its variants
                textStyle = LightThemeTokens.typography.copy.copy(
                    color = colors.content,
                    fontSize = 30f.designVerticalPxToSp(),
                    lineHeight = 45f.designVerticalPxToSp(),
                ),
                singleLine = true,
                cursorBrush = SolidColor(colors.content),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.submitSearch(query) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 0.5f.gridUnitsAsDp())
                    .focusRequester(focusRequester),
            )
            Spacer(modifier = Modifier.height(0.5f.gridUnitsAsDp()))
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3f.designVerticalPxToDp())
                    .background(colors.content),
            )
        }
        LaunchedEffect(viewModel.searchSession) {
            focusRequester.requestFocus()
        }
    }

    @Composable
    private fun ColumnScope.SearchTab() {
        val mode by viewModel.searchMode.collectAsState()
        val current by TidePlayer.current.collectAsState()
        when (val m = mode) {
            is MainViewModel.SearchMode.Input -> {
                TabHeader("Search")
                SearchField()
            }

            is MainViewModel.SearchMode.Searching -> {
                SearchHeader(m.query)
                LoadingText()
            }

            is MainViewModel.SearchMode.Failed -> {
                SearchHeader(m.query)
                ErrorRetry(m.message, onRetry = { viewModel.submitSearch(m.query) })
            }

            is MainViewModel.SearchMode.Results -> {
                SearchHeader(m.query)
                SearchResultsList(m.results, currentTrackId = current?.id)
            }
        }
    }

    @Composable
    private fun SearchHeader(query: String) {
        LightTopBar(
            center = LightTopBarCenter.Text(query),
            rightButton = LightBarButton.LightIcon(
                LightIcons.SEARCH,
                onClick = { viewModel.newSearch() },
            ),
        )
    }

    @Composable
    private fun ColumnScope.SearchResultsList(
        results: SearchResults,
        currentTrackId: Long?,
    ) {
        val empty = results.tracks.isEmpty() && results.albums.isEmpty() &&
            results.artists.isEmpty() && results.playlists.isEmpty()
        if (empty) {
            EmptyText("No results")
            return
        }
        LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (results.tracks.isNotEmpty()) {
                SectionHeader("Tracks")
                results.tracks.forEachIndexed { i, track ->
                    NumberedTrackRow(
                        number = null,
                        track = track,
                        active = currentTrackId == track.id,
                        onClick = {
                            TidePlayer.play(results.tracks, i)
                            navigateTo({ PlayerScreen(it) })
                        },
                        onLongClick = {
                            navigateTo({ TrackOptionsScreen(it, track) })
                        },
                    )
                }
            }
            if (results.artists.isNotEmpty()) {
                SectionHeader("Artists")
                results.artists.forEach { artist ->
                    MediaRow(
                        primary = artist.name,
                        secondary = "",
                        onClick = { navigateTo({ ArtistScreen(it, artist) }) },
                    )
                }
            }
            if (results.albums.isNotEmpty()) {
                SectionHeader("Albums")
                results.albums.forEach { album ->
                    MediaRow(
                        primary = album.title,
                        secondary = listOf(album.artist, album.year)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
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
            if (results.playlists.isNotEmpty()) {
                SectionHeader("Playlists")
                results.playlists.forEach { playlist ->
                    MediaRow(
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

    // ---------- settings ----------

    @Composable
    private fun ColumnScope.SettingsTab() {
        val quality by viewModel.quality.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        var confirmLogout by remember { mutableStateOf(false) }

        if (confirmLogout) {
            ConfirmContent(
                title = "Logout",
                message = "Are you sure you want to logout?",
                confirmText = "Logout",
                onConfirm = {
                    confirmLogout = false
                    viewModel.signOut()
                },
                onCancel = { confirmLogout = false },
            )
            return
        }

        TabHeader("Settings")
        LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp()),
            ) {
                SectionLabel("Appearance")
                ToggleRow(
                    label = "Dark mode",
                    checked = themeColors == LightThemeColors.Dark,
                    onToggle = { LightThemeController.toggle() },
                )

                SectionLabel("Audio quality")
                listOf(
                    "LOW" to "Low (96 kbps)",
                    "HIGH" to "High (320 kbps)",
                    "LOSSLESS" to "Lossless (FLAC)",
                ).forEach { (value, label) ->
                    ActionRow(
                        text = label,
                        selected = quality == value,
                        onClick = { viewModel.setQuality(value) },
                    )
                }

                SectionLabel("Library")
                ActionRow(text = "Artists", onClick = { navigateTo({ ArtistListScreen(it) }) })

                SectionLabel("Customize")
                ActionRow(text = "Home Sections", onClick = { navigateTo({ HomeSectionsScreen(it) }) })
                ActionRow(text = "Navigation Bar", onClick = { navigateTo({ NavBarScreen(it) }) })

                SectionLabel("Account")
                ActionRow(text = "Logout", onClick = { confirmLogout = true })

                LightText(
                    text = "Tide · Unofficial TIDAL client · User ${Tidal.userId}",
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                    modifier = Modifier.padding(top = 2f.gridUnitsAsDp()),
                )
                Spacer(modifier = Modifier.height(2.5f.gridUnitsAsDp()))
            }
        }
    }

    /** Full-screen confirm, Phono-style: title, message, confirm/cancel rows. */
    @Composable
    private fun ColumnScope.ConfirmContent(
        title: String,
        message: String,
        confirmText: String,
        onConfirm: () -> Unit,
        onCancel: () -> Unit,
    ) {
        TabHeader(title)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            Spacer(modifier = Modifier.height(1.5f.gridUnitsAsDp()))
            LightText(
                text = message,
                variant = LightTextVariant.Paragraph,
                lighten = true,
            )
            Spacer(modifier = Modifier.height(1.5f.gridUnitsAsDp()))
            ActionRow(text = confirmText, onClick = onConfirm)
            ActionRow(text = "Cancel", onClick = onCancel)
        }
    }
}
