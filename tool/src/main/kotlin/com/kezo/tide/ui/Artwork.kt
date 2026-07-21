package com.kezo.tide.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import com.kezo.tide.api.Tidal
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Loads TIDAL cover art (full color — the device renders it in grayscale when
 * LightOS grayscale is on) with a small in-memory LRU plus a disk cache under
 * the tool's files dir, so art also shows for downloaded tracks offline once
 * it's been seen.
 */
object Artwork {
    private const val MEM_CACHE = 80

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
    }

    private var dir: File? = null

    private val memory = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
            size > MEM_CACHE
    }

    fun init(filesDir: File) {
        if (dir == null) dir = File(filesDir, "artwork").apply { runCatching { mkdirs() } }
    }

    /** Nearest TIDAL-served square size for a target pixel dimension. */
    fun pickSize(px: Int): Int = when {
        px <= 80 -> 80
        px <= 160 -> 160
        px <= 320 -> 320
        px <= 640 -> 640
        else -> 1280
    }

    private fun coverUrl(cover: String, size: Int): String =
        "https://resources.tidal.com/images/${cover.replace("-", "/")}/${size}x$size.jpg"

    private fun diskFile(cover: String, size: Int): File? =
        dir?.let { File(it, "${cover.replace("/", "_")}_$size.jpg") }

    suspend fun load(cover: String, size: Int): ImageBitmap? {
        if (cover.isBlank()) return null
        val key = "$cover@$size"
        synchronized(memory) { memory[key] }?.let { return it }

        val bytes = readDisk(cover, size) ?: fetch(cover, size) ?: return null
        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            ?: return null
        val image = bitmap.asImageBitmap()
        synchronized(memory) { memory[key] = image }
        return image
    }

    private fun readDisk(cover: String, size: Int): ByteArray? {
        val f = diskFile(cover, size) ?: return null
        return if (f.exists()) runCatching { f.readBytes() }.getOrNull() else null
    }

    private suspend fun fetch(cover: String, size: Int): ByteArray? = runCatching {
        val rsp = client.get(coverUrl(cover, size))
        if (!rsp.status.isSuccess()) return null
        val data: ByteArray = rsp.body()
        diskFile(cover, size)?.let { f -> runCatching { f.writeBytes(data) } }
        data
    }.getOrNull()
}

/**
 * Square album art. Shows a faint placeholder while loading or when [cover] is
 * blank, so rows stay aligned.
 */
@Composable
fun AlbumArt(cover: String?, sizeUnits: Float, modifier: Modifier = Modifier) {
    val dim = sizeUnits.gridUnitsAsDp()
    val placeholder = LightThemeTokens.colors.content.copy(alpha = 0.08f)
    val px = with(LocalDensity.current) { dim.roundToPx() }
    val reqSize = Artwork.pickSize(px)

    val image by produceState<ImageBitmap?>(initialValue = null, cover, reqSize) {
        value = if (cover.isNullOrBlank()) null
        else withContext(Dispatchers.IO) { Artwork.load(cover, reqSize) }
    }

    Box(modifier = modifier.size(dim).background(placeholder)) {
        val img = image
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(dim),
            )
        }
    }
}

/**
 * Album art that fills whatever size [modifier] gives it (e.g. a weighted,
 * square box), so it can flex with the available space instead of a fixed size.
 */
@Composable
fun AlbumArtBox(cover: String?, modifier: Modifier = Modifier) {
    val placeholder = LightThemeTokens.colors.content.copy(alpha = 0.08f)
    val image by produceState<ImageBitmap?>(initialValue = null, cover) {
        value = if (cover.isNullOrBlank()) null
        else withContext(Dispatchers.IO) { Artwork.load(cover, 640) }
    }
    Box(modifier = modifier.background(placeholder)) {
        val img = image
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Returns [cover] if present, otherwise resolves the album's cover by id (so
 * tracks whose payload lacked a cover still show artwork). Cached in Tidal.
 */
@Composable
fun rememberCover(cover: String, albumId: Long): String {
    if (cover.isNotBlank() || albumId == 0L) return cover
    val resolved by produceState(initialValue = "", albumId) {
        value = withContext(Dispatchers.IO) { Tidal.albumCover(albumId) }
    }
    return resolved
}
