package io.github.mohnstollen.news.data

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.net.URLDecoder

data class NcCredentials(val server: String, val user: String, val password: String)

/**
 * The login data in the QR code that Nextcloud shows for a new app password
 * (Personal settings → Security → "Create new app password" → "Show QR code for mobile apps"):
 * `nc://login/user:<login>&password:<app password>&server:<address>`, values URL-encoded,
 * in any order.
 */
object NcLogin {
    private const val PREFIX = "nc://login/"

    /** Returns null for any other text. */
    fun parse(text: String): NcCredentials? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(PREFIX, ignoreCase = true)) return null
        val values = HashMap<String, String>()
        for (part in trimmed.substring(PREFIX.length).split('&')) {
            val colon = part.indexOf(':')
            if (colon <= 0) continue
            val raw = part.substring(colon + 1)
            // Like the official Nextcloud app: URL-decoded, the raw value if that fails.
            values[part.substring(0, colon).lowercase()] =
                try { URLDecoder.decode(raw, "UTF-8") } catch (e: IllegalArgumentException) { raw }
        }
        val server = values["server"]?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        val user = values["user"]?.takeIf { it.isNotEmpty() } ?: return null
        val password = values["password"]?.takeIf { it.isNotEmpty() } ?: return null
        return NcCredentials(server, user, password)
    }
}

/** Finds a QR code in a camera frame. Not thread-safe; use one instance per analysis thread. */
class QrDecoder {
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    /**
     * [luminance] is the frame's brightness plane (the Y plane of a YUV image), [rowStride] bytes
     * per row. Also finds light-on-dark codes. Returns the code's text, or null.
     */
    fun decode(luminance: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        return decode(source) ?: decode(source.invert())
    }

    private fun decode(source: LuminanceSource): String? =
        try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (e: ReaderException) {
            null
        } finally {
            reader.reset()
        }
}
