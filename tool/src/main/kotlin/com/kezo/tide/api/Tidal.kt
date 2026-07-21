package com.kezo.tide.api

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

// ---------- domain models ----------

@kotlinx.serialization.Serializable
data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val artistId: Long,
    val albumTitle: String,
    val albumId: Long,
    val durationSec: Int,
    val explicit: Boolean,
    val quality: String,
)

data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val numberOfTracks: Int,
    val year: String,
    val releaseDate: String = "",
)

data class Artist(val id: Long, val name: String)

data class Mix(val id: String, val title: String, val subtitle: String)

data class Playlist(
    val uuid: String,
    val title: String,
    val numberOfTracks: Int,
    val creator: String,
)

data class SearchResults(
    val tracks: List<Track>,
    val albums: List<Album>,
    val artists: List<Artist>,
    val playlists: List<Playlist>,
)

data class DeviceLink(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val intervalSec: Int,
    val expiresInSec: Int,
)

class AuthPendingException : Exception("authorization pending")

// ---------- json helpers ----------

private val json = Json { ignoreUnknownKeys = true }

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.arr(key: String): JsonArray = (this[key] as? JsonArray) ?: JsonArray(emptyList())
private fun JsonObject.prim(key: String): JsonPrimitive? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }

private fun JsonObject.str(key: String): String? = prim(key)?.content
private fun JsonObject.long(key: String): Long? = prim(key)?.longOrNull
private fun JsonObject.int(key: String): Int? = prim(key)?.intOrNull
private fun JsonObject.bool(key: String): Boolean? = prim(key)?.booleanOrNull

// ---------- client ----------

/**
 * Unofficial TIDAL client speaking the same API the open-source ecosystem
 * (python-tidal and friends) uses. Requires an active TIDAL subscription;
 * sign-in happens through TIDAL's own device-link page on another device.
 */
object Tidal {
    private const val AUTH = "https://auth.tidal.com/v1/oauth2"
    private const val API = "https://api.tidal.com/v1"
    private const val SCOPE = "r_usr w_usr w_sub"

    // The device-flow client credentials published across open-source TIDAL
    // clients (identical to the ones shipped in python-tidal).
    private const val CLIENT_ID = "fX2JxdmntZWK0ixT"
    private const val CLIENT_SECRET = "1Nn9AfDAjxrgJFJbKNWLeAyKGVGmINuXPPLHVXAvxAg="

    private val KEY_ACCESS = stringPreferencesKey("accessToken")
    private val KEY_REFRESH = stringPreferencesKey("refreshToken")
    private val KEY_USER = longPreferencesKey("userId")
    private val KEY_COUNTRY = stringPreferencesKey("countryCode")
    private val KEY_QUALITY = stringPreferencesKey("quality")

    private val http = HttpClient(OkHttp) {
        install(HttpTimeout) {
            // No total request cap so large downloads can run; fail fast on a
            // dead connection or a stalled response instead of hanging forever.
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
    }

    /** Serializes token refresh so parallel 401s trigger only one refresh. */
    private val refreshMutex = Mutex()

    private var store: DataStore<Preferences>? = null

    private var accessToken: String? = null
    private var refreshToken: String? = null
    var userId: Long = 0L; private set
    var countryCode: String = "US"; private set

    /** LOW (96 kbps AAC) / HIGH (320 kbps AAC) / LOSSLESS (FLAC) */
    var quality: String = "HIGH"; private set

    val loggedIn: Boolean get() = refreshToken != null

    fun init(dataStore: DataStore<Preferences>) {
        if (store == null) store = dataStore
    }

    /** Loads persisted tokens into memory. Returns true when an account is linked. */
    suspend fun restore(): Boolean {
        val p = store?.data?.first() ?: return false
        accessToken = p[KEY_ACCESS]
        refreshToken = p[KEY_REFRESH]
        userId = p[KEY_USER] ?: 0L
        countryCode = p[KEY_COUNTRY] ?: "US"
        quality = p[KEY_QUALITY] ?: "HIGH"
        return loggedIn
    }

    suspend fun setQuality(value: String) {
        quality = value
        store?.edit { it[KEY_QUALITY] = value }
    }

    suspend fun logout() {
        accessToken = null
        refreshToken = null
        userId = 0L
        favTrackIds.clear()
        favAlbumIds.clear()
        favArtistIds.clear()
        favIdsLoaded = false
        albumTracksCache.clear()
        artistTopTracksCache.clear()
        artistAlbumsCache.clear()
        mixTracksCache.clear()
        similarArtistsCache.clear()
        mixesCache = null
        newReleasesCache = null
        store?.edit { it.clear() }
    }

    private suspend fun persistTokens() {
        store?.edit { p ->
            accessToken?.let { p[KEY_ACCESS] = it }
            refreshToken?.let { p[KEY_REFRESH] = it }
            p[KEY_USER] = userId
            p[KEY_COUNTRY] = countryCode
        }
    }

    // ---------- oauth device flow ----------

    suspend fun startDeviceLogin(): DeviceLink {
        val rsp = http.post("$AUTH/device_authorization") {
            setBody(FormDataContent(Parameters.build {
                append("client_id", CLIENT_ID)
                append("scope", SCOPE)
            }))
        }
        val body = json.parseToJsonElement(rsp.bodyAsText()) as JsonObject
        if (!rsp.status.isSuccess()) {
            throw IOException(body.str("error_description") ?: "device link failed (${rsp.status.value})")
        }
        return DeviceLink(
            deviceCode = body.str("deviceCode") ?: throw IOException("bad device auth response"),
            userCode = body.str("userCode") ?: "",
            verificationUri = body.str("verificationUriComplete") ?: body.str("verificationUri") ?: "link.tidal.com",
            intervalSec = body.int("interval") ?: 2,
            expiresInSec = body.int("expiresIn") ?: 300,
        )
    }

    /** One poll of the token endpoint; throws [AuthPendingException] until the user confirms. */
    suspend fun pollDeviceLogin(deviceCode: String) {
        val rsp = http.post("$AUTH/token") {
            setBody(FormDataContent(Parameters.build {
                append("client_id", CLIENT_ID)
                append("client_secret", CLIENT_SECRET)
                append("device_code", deviceCode)
                append("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                append("scope", SCOPE)
            }))
        }
        val body = json.parseToJsonElement(rsp.bodyAsText()) as JsonObject
        if (!rsp.status.isSuccess()) {
            val err = body.str("error")
            if (err == "authorization_pending" || err == "slow_down") throw AuthPendingException()
            throw IOException(body.str("error_description") ?: "sign in failed ($err)")
        }
        applyTokenResponse(body)
        loadSession()
        persistTokens()
    }

    private fun applyTokenResponse(body: JsonObject) {
        accessToken = body.str("access_token")
        body.str("refresh_token")?.let { refreshToken = it }
        body.obj("user")?.let { u ->
            u.long("userId")?.let { userId = it }
            u.str("countryCode")?.let { countryCode = it }
        }
    }

    /**
     * Refreshes the access token. [staleToken] is the token the caller had when
     * it got a 401; if another caller already refreshed while this one waited
     * for the lock, we skip the network call and let the caller retry.
     */
    private suspend fun refreshAccessToken(staleToken: String?): Boolean = refreshMutex.withLock {
        if (accessToken != null && accessToken != staleToken) return@withLock true
        val rt = refreshToken ?: return@withLock false
        val rsp = http.post("$AUTH/token") {
            setBody(FormDataContent(Parameters.build {
                append("client_id", CLIENT_ID)
                append("client_secret", CLIENT_SECRET)
                append("refresh_token", rt)
                append("grant_type", "refresh_token")
                append("scope", SCOPE)
            }))
        }
        if (!rsp.status.isSuccess()) return@withLock false
        applyTokenResponse(json.parseToJsonElement(rsp.bodyAsText()) as JsonObject)
        persistTokens()
        true
    }

    private suspend fun loadSession() {
        try {
            val s = apiObject("sessions")
            s.long("userId")?.let { userId = it }
            s.str("countryCode")?.let { countryCode = it }
        } catch (_: Exception) {
            // userId/country usually arrive with the token response; not fatal
        }
    }

    // ---------- generic request ----------

    private suspend fun api(
        path: String,
        params: Map<String, String> = emptyMap(),
        method: HttpMethod = HttpMethod.Get,
        form: Map<String, String>? = null,
        retry: Boolean = true,
    ): String {
        val used = accessToken
        val rsp = http.request("$API/$path") {
            this.method = method
            parameter("countryCode", countryCode)
            params.forEach { (k, v) -> parameter(k, v) }
            header("Authorization", "Bearer ${used ?: ""}")
            if (form != null) {
                setBody(FormDataContent(Parameters.build {
                    form.forEach { (k, v) -> append(k, v) }
                }))
            }
        }
        if (rsp.status.value == 401 && retry && refreshAccessToken(used)) {
            return api(path, params, method, form, retry = false)
        }
        val text = rsp.bodyAsText()
        if (!rsp.status.isSuccess()) {
            val message = try {
                (json.parseToJsonElement(text) as? JsonObject)?.str("userMessage")
            } catch (_: Exception) {
                null
            }
            throw IOException(message ?: "request failed (${rsp.status.value})")
        }
        return text
    }

    private suspend fun apiObject(path: String, params: Map<String, String> = emptyMap()): JsonObject =
        json.parseToJsonElement(api(path, params)) as JsonObject

    /**
     * Pages through a list endpoint (capped so huge libraries stay bounded).
     * The first page reveals the total; the remaining pages are fetched
     * concurrently so long lists load in one round-trip's extra time.
     */
    private suspend fun paged(
        path: String,
        extra: Map<String, String> = emptyMap(),
        cap: Int = 1000,
    ): List<JsonObject> = coroutineScope {
        val limit = 50
        val first = apiObject(path, extra + mapOf("limit" to "$limit", "offset" to "0"))
        val firstItems = first.arr("items").mapNotNull { it as? JsonObject }
        val total = (first.int("totalNumberOfItems") ?: firstItems.size).coerceAtMost(cap)
        if (firstItems.size >= total || firstItems.size < limit) return@coroutineScope firstItems
        val rest = (limit until total step limit).map { offset ->
            async {
                apiObject(path, extra + mapOf("limit" to "$limit", "offset" to "$offset"))
                    .arr("items").mapNotNull { it as? JsonObject }
            }
        }.awaitAll()
        firstItems + rest.flatten()
    }

    // ---------- parsing ----------

    private fun parseTrack(o: JsonObject): Track? {
        val t = o.obj("item") ?: o
        val album = t.obj("album")
        return Track(
            id = t.long("id") ?: return null,
            title = t.str("title") ?: "unknown",
            artist = t.obj("artist")?.str("name")
                ?: (t.arr("artists").firstOrNull() as? JsonObject)?.str("name") ?: "",
            artistId = t.obj("artist")?.long("id")
                ?: (t.arr("artists").firstOrNull() as? JsonObject)?.long("id") ?: 0L,
            albumTitle = album?.str("title") ?: "",
            albumId = album?.long("id") ?: 0L,
            durationSec = t.int("duration") ?: 0,
            explicit = t.bool("explicit") ?: false,
            quality = t.str("audioQuality") ?: "",
        )
    }

    private fun parseAlbum(o: JsonObject): Album? {
        val a = o.obj("item") ?: o
        return Album(
            id = a.long("id") ?: return null,
            title = a.str("title") ?: "unknown",
            artist = a.obj("artist")?.str("name")
                ?: (a.arr("artists").firstOrNull() as? JsonObject)?.str("name") ?: "",
            numberOfTracks = a.int("numberOfTracks") ?: 0,
            year = (a.str("releaseDate") ?: "").take(4),
            releaseDate = a.str("releaseDate") ?: "",
        )
    }

    private fun parseArtist(o: JsonObject): Artist? {
        val a = o.obj("item") ?: o
        return Artist(id = a.long("id") ?: return null, name = a.str("name") ?: "unknown")
    }

    private fun parsePlaylist(o: JsonObject): Playlist? {
        val p = o.obj("playlist") ?: o.obj("item") ?: o
        return Playlist(
            uuid = p.str("uuid") ?: return null,
            title = p.str("title") ?: "unknown",
            numberOfTracks = p.int("numberOfTracks") ?: 0,
            creator = p.obj("creator")?.str("name") ?: "",
        )
    }

    // ---------- library ----------

    private val recentOrder = mapOf("order" to "DATE", "orderDirection" to "DESC")

    suspend fun favoriteTracks(): List<Track> =
        paged("users/$userId/favorites/tracks", recentOrder).mapNotNull(::parseTrack)

    suspend fun favoriteAlbums(): List<Album> =
        paged("users/$userId/favorites/albums", recentOrder).mapNotNull(::parseAlbum)

    suspend fun favoriteArtists(): List<Artist> =
        paged("users/$userId/favorites/artists", recentOrder).mapNotNull(::parseArtist)

    suspend fun playlists(): List<Playlist> =
        paged("users/$userId/playlistsAndFavoritePlaylists").mapNotNull(::parsePlaylist)

    private val albumTracksCache = ConcurrentHashMap<Long, List<Track>>()
    private val artistTopTracksCache = ConcurrentHashMap<Long, List<Track>>()
    private val artistAlbumsCache = ConcurrentHashMap<Long, List<Album>>()

    suspend fun albumTracks(albumId: Long): List<Track> =
        albumTracksCache[albumId] ?: paged("albums/$albumId/tracks")
            .mapNotNull(::parseTrack)
            .also { albumTracksCache[albumId] = it }

    suspend fun playlistTracks(uuid: String): List<Track> =
        paged("playlists/$uuid/tracks").mapNotNull(::parseTrack)

    suspend fun artistTopTracks(artistId: Long): List<Track> =
        artistTopTracksCache[artistId] ?: paged("artists/$artistId/toptracks", cap = 20)
            .mapNotNull(::parseTrack)
            .also { artistTopTracksCache[artistId] = it }

    suspend fun artistAlbums(artistId: Long): List<Album> =
        artistAlbumsCache[artistId] ?: paged("artists/$artistId/albums")
            .mapNotNull(::parseAlbum)
            .also { artistAlbumsCache[artistId] = it }

    suspend fun search(query: String): SearchResults {
        val r = apiObject(
            "search", mapOf(
                "query" to query,
                "limit" to "20",
                "types" to "ARTISTS,ALBUMS,TRACKS,PLAYLISTS",
            )
        )
        fun items(key: String): List<JsonObject> =
            r.obj(key)?.arr("items")?.mapNotNull { it as? JsonObject } ?: emptyList()
        return SearchResults(
            tracks = items("tracks").mapNotNull(::parseTrack),
            albums = items("albums").mapNotNull(::parseAlbum),
            artists = items("artists").mapNotNull(::parseArtist),
            playlists = items("playlists").mapNotNull(::parsePlaylist),
        )
    }

    // ---------- discovery ----------

    private val mixTracksCache = ConcurrentHashMap<String, List<Track>>()
    private val similarArtistsCache = ConcurrentHashMap<Long, List<Artist>>()
    @Volatile
    private var mixesCache: List<Mix>? = null
    @Volatile
    private var newReleasesCache: List<Album>? = null

    /**
     * The user's personalized mixes (Daily Discovery, My Mix 1..n) via the
     * pages API. Best-effort: any parsing surprise returns an empty list.
     */
    suspend fun myMixes(): List<Mix> {
        mixesCache?.let { return it }
        return try {
            val page = apiObject("pages/my_collection_my_mixes", mapOf("deviceType" to "BROWSER"))
            val out = ArrayList<Mix>()
            page.arr("rows").forEach { row ->
                (row as? JsonObject)?.arr("modules")?.forEach { module ->
                    (module as? JsonObject)?.obj("pagedList")?.arr("items")?.forEach { item ->
                        (item as? JsonObject)?.let { o ->
                            val id = o.str("id")
                            val title = o.str("title")
                            if (id != null && title != null) {
                                out.add(Mix(id, title, o.str("subTitle") ?: ""))
                            }
                        }
                    }
                }
            }
            out.also { mixesCache = it }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun mixTracks(mixId: String): List<Track> =
        mixTracksCache[mixId] ?: paged("mixes/$mixId/items")
            .mapNotNull(::parseTrack)
            .also { mixTracksCache[mixId] = it }

    /** Tracks similar to the given one (TIDAL track radio). */
    suspend fun trackRadio(trackId: Long): List<Track> =
        paged("tracks/$trackId/radio", cap = 100).mapNotNull(::parseTrack)

    suspend fun similarArtists(artistId: Long): List<Artist> =
        similarArtistsCache[artistId] ?: try {
            paged("artists/$artistId/similar", cap = 50)
                .mapNotNull(::parseArtist)
                .also { similarArtistsCache[artistId] = it }
        } catch (_: Exception) {
            emptyList()
        }

    /**
     * Albums released in the last ~90 days by the user's favorite artists.
     * Fans out over the (cached) discographies of up to 30 artists.
     */
    suspend fun newReleases(): List<Album> {
        newReleasesCache?.let { return it }
        val cutoff = java.time.LocalDate.now().minusDays(90).toString()
        val artists = favoriteArtists().take(30)
        val albums = coroutineScope {
            artists.map { artist ->
                async {
                    try {
                        artistAlbums(artist.id)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll()
        }
        return albums.flatten()
            .filter { it.releaseDate >= cutoff }
            .distinctBy { it.id }
            .sortedByDescending { it.releaseDate }
            .take(20)
            .also { newReleasesCache = it }
    }

    // ---------- favorites (write) ----------

    val favTrackIds = LinkedHashSet<Long>()
    val favAlbumIds = LinkedHashSet<Long>()
    val favArtistIds = LinkedHashSet<Long>()
    private var favIdsLoaded = false

    /**
     * Loads all favorite id sets (tracks, albums, artists) in a single request.
     * Cached for the session so membership checks on the artist and album pages
     * don't each re-page the full favorites lists.
     */
    suspend fun ensureFavIds() {
        if (favIdsLoaded) return
        try {
            val ids = apiObject("users/$userId/favorites/ids")
            fun collect(key: String, into: LinkedHashSet<Long>) {
                ids.arr(key).forEach { el ->
                    (el as? JsonPrimitive)?.content?.toLongOrNull()?.let(into::add)
                }
            }
            collect("TRACK", favTrackIds)
            collect("ALBUM", favAlbumIds)
            collect("ARTIST", favArtistIds)
            favIdsLoaded = true
        } catch (_: Exception) {
            // favorite state stays unknown until this succeeds
        }
    }

    /** Kept for existing callers; loads every favorite id set. */
    suspend fun ensureFavTrackIds() = ensureFavIds()

    suspend fun addFavoriteTrack(id: Long) {
        api("users/$userId/favorites/tracks", method = HttpMethod.Post, form = mapOf("trackIds" to "$id"))
        favTrackIds.add(id)
    }

    suspend fun removeFavoriteTrack(id: Long) {
        api("users/$userId/favorites/tracks/$id", method = HttpMethod.Delete)
        favTrackIds.remove(id)
    }

    suspend fun addFavoriteAlbum(id: Long) {
        api("users/$userId/favorites/albums", method = HttpMethod.Post, form = mapOf("albumIds" to "$id"))
        favAlbumIds.add(id)
    }

    suspend fun removeFavoriteAlbum(id: Long) {
        api("users/$userId/favorites/albums/$id", method = HttpMethod.Delete)
        favAlbumIds.remove(id)
    }

    suspend fun addFavoriteArtist(id: Long) {
        api("users/$userId/favorites/artists", method = HttpMethod.Post, form = mapOf("artistIds" to "$id"))
        favArtistIds.add(id)
    }

    suspend fun removeFavoriteArtist(id: Long) {
        api("users/$userId/favorites/artists/$id", method = HttpMethod.Delete)
        favArtistIds.remove(id)
    }

    suspend fun addFavoritePlaylist(uuid: String) {
        api("users/$userId/favorites/playlists", method = HttpMethod.Post, form = mapOf("uuids" to uuid))
    }

    suspend fun removeFavoritePlaylist(uuid: String) {
        api("users/$userId/favorites/playlists/$uuid", method = HttpMethod.Delete)
    }

    // ---------- playback ----------

    /**
     * Resolves a direct stream URL. Tries the configured quality first, stepping
     * down whenever the service answers with a DASH manifest (hi-res tiers) that
     * the simple progressive player can't consume.
     */
    suspend fun streamUrl(trackId: Long): String {
        val ladder = listOf(quality, "HIGH", "LOW").distinct()
        for (q in ladder) {
            val info = apiObject(
                "tracks/$trackId/playbackinfopostpaywall", mapOf(
                    "audioquality" to q,
                    "playbackmode" to "STREAM",
                    "assetpresentation" to "FULL",
                )
            )
            val mime = info.str("manifestMimeType") ?: ""
            val manifestB64 = info.str("manifest") ?: continue
            if (!mime.contains("vnd.tidal.bts")) continue
            val manifest = json.parseToJsonElement(
                String(Base64.getMimeDecoder().decode(manifestB64))
            ) as JsonObject
            (manifest.arr("urls").firstOrNull() as? JsonPrimitive)?.content?.let { return it }
        }
        throw IOException("no playable stream")
    }

    /**
     * Downloads a track's audio to [file] for offline playback, at the currently
     * configured quality. Returns the number of bytes written.
     */
    suspend fun downloadTrackTo(trackId: Long, file: File): Long {
        val url = streamUrl(trackId)
        val rsp = http.get(url)
        if (!rsp.status.isSuccess()) {
            throw IOException("download failed (${rsp.status.value})")
        }
        val bytes: ByteArray = rsp.body()
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file.length()
    }
}
