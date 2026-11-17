package io.github.mohnstollen.news.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.github.mohnstollen.news.data.USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Loads pictures for the reader. Responses go to a disk cache, so that pictures seen once stay
 * visible offline (like the Android WebView's cache), and decoded pictures to a small memory cache.
 */
class ImageLoader(base: OkHttpClient, cacheDir: File) {
    private val client = base.newBuilder()
        .cache(Cache(cacheDir, 100L * 1024 * 1024))
        // Pictures rarely change; keep them even if the server says otherwise.
        .addNetworkInterceptor { chain ->
            chain.proceed(chain.request()).newBuilder()
                .header("Cache-Control", "public, max-age=${TimeUnit.DAYS.toSeconds(30)}")
                .removeHeader("Pragma")
                .build()
        }
        .build()

    private val memory = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 80
    }
    private val parallel = Semaphore(4)

    fun cached(url: String): ImageBitmap? = synchronized(memory) { memory[url] }

    /** Returns null if the picture cannot be loaded or decoded (e.g. SVG). */
    suspend fun load(url: String): ImageBitmap? {
        cached(url)?.let { return it }
        if (!url.startsWith("http")) return null
        return parallel.withPermit {
            withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = fetch(url, null) ?: return@runCatching null
                    org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                }.getOrNull()?.also { synchronized(memory) { memory[url] = it } }
            }
        }
    }

    private fun fetch(url: String, cacheControl: CacheControl?): ByteArray? {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .apply { if (cacheControl != null) cacheControl(cacheControl) }.build()
        val bytes = runCatching {
            client.newCall(request).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
        }.getOrNull()
        if (bytes != null) return bytes
        // Offline: whatever the cache still has.
        val offline = request.newBuilder().cacheControl(CacheControl.FORCE_CACHE).build()
        return runCatching {
            client.newCall(offline).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
        }.getOrNull()
    }
}
