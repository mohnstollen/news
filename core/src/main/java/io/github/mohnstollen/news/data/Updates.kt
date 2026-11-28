package io.github.mohnstollen.news.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** A published version of the app. [downloadUrl] is its APK, else the release page. */
data class AppRelease(val version: String, val downloadUrl: String, val pageUrl: String)

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    @SerialName("html_url") val page: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
private data class GitHubAsset(val name: String, @SerialName("browser_download_url") val url: String)

/**
 * Looks for a newer version among the app's releases on GitHub (tags "podfang-v1.2" and
 * "news-v1.9" in the one repository). Only on request or once a day, without an account.
 */
class UpdateChecker(private val http: OkHttpClient) {
    /** The repository's release list; tests point it elsewhere. */
    internal var releasesUrl = "https://api.github.com/repos/mohnstollen/news/releases?per_page=50"

    private val json = Json { ignoreUnknownKeys = true }

    /** The newest release of the app with [prefix]; null if there is none. Throws [IOException]. */
    suspend fun latest(prefix: String): AppRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(releasesUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        val body = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub antwortet mit HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        val tag = "$prefix-v"
        json.decodeFromString(ListSerializer(GitHubRelease.serializer()), body)
            .filter { !it.draft && !it.prerelease && it.tag.startsWith(tag) }
            .maxWithOrNull { a, b -> compareVersions(a.tag.removePrefix(tag), b.tag.removePrefix(tag)) }
            ?.let { release ->
                val apk = release.assets.firstOrNull { it.name.endsWith(".apk") }?.url
                AppRelease(release.tag.removePrefix(tag), apk ?: release.page, release.page)
            }
    }

    companion object {
        /** "1.10" is newer than "1.9"; missing parts count as 0. */
        fun compareVersions(a: String, b: String): Int {
            val x = a.split('.').map { it.toIntOrNull() ?: 0 }
            val y = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }

        fun isNewer(candidate: String, installed: String) = compareVersions(candidate, installed) > 0
    }
}
