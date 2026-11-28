package io.github.mohnstollen.news.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.mohnstollen.news.ui.container
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Covers for Android Auto, which loads pictures only from content URIs of the app. Serves the
 * cover of a podcast or an episode by id: fetched once, made small and kept in the cache, so it
 * shows offline, too. Not exported: the car gets read access to each URI it is given.
 */
class CoverProvider : ContentProvider() {

    sealed interface Cover {
        data class Podcast(val feedId: Long) : Cover
        data class Episode(val articleId: Long) : Cover
    }

    override fun onCreate() = true

    override fun getType(uri: Uri) = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        val context = context ?: throw FileNotFoundException()
        val cover = parse(uri) ?: throw FileNotFoundException("$uri")
        val file = runBlocking { file(context, cover) } ?: throw FileNotFoundException("$uri")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        /** Long side of the stored pictures: enough for a car screen. */
        private const val SIZE_PX = 512

        fun authority(context: Context) = context.packageName + ".covers"

        fun uri(context: Context, cover: Cover): Uri = Uri.Builder()
            .scheme("content")
            .authority(authority(context))
            .appendPath(if (cover is Cover.Podcast) "podcast" else "episode")
            .appendPath(if (cover is Cover.Podcast) cover.feedId.toString() else (cover as Cover.Episode).articleId.toString())
            .build()

        /** [uri] for [packageName] (the car), which may read it then. */
        fun grantedUri(context: Context, cover: Cover, packageName: String): Uri {
            val uri = uri(context, cover)
            if (packageName != context.packageName) {
                runCatching { context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            return uri
        }

        fun parse(uri: Uri): Cover? {
            val (kind, id) = uri.pathSegments.takeIf { it.size == 2 } ?: return null
            val number = id.toLongOrNull() ?: return null
            return when (kind) {
                "podcast" -> Cover.Podcast(number)
                "episode" -> Cover.Episode(number)
                else -> null
            }
        }

        /** The stored picture of [cover]; fetched first if need be. Null if there is none. */
        suspend fun file(context: Context, cover: Cover): File? {
            val repo = context.container.repository
            val url = when (cover) {
                is Cover.Podcast -> repo.feedDao.byId(cover.feedId)?.imageUrl
                is Cover.Episode -> repo.articleDao.byId(cover.articleId)?.let { article ->
                    article.imageUrl ?: repo.feedDao.byId(article.feedId)?.imageUrl
                }
            } ?: return null
            val dir = File(context.cacheDir, "covers").apply { mkdirs() }
            val file = File(dir, sha256(url) + ".jpg")
            if (file.isFile && file.length() > 0) return file
            val bytes = runCatching {
                context.container.http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (response.isSuccessful) response.body?.bytes() else null
                }
            }.getOrNull() ?: return null
            val bitmap = decodeSmall(bytes) ?: return null
            val part = File(dir, file.name + ".part")
            part.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            return file.takeIf { part.renameTo(it) }
        }

        private fun decodeSmall(bytes: ByteArray): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE_PX) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val scale = SIZE_PX.toFloat() / maxOf(bitmap.width, bitmap.height)
            if (scale >= 1f) return bitmap
            return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                .also { if (it !== bitmap) bitmap.recycle() }
        }

        private fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
