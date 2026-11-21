package io.github.mohnstollen.news

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.github.mohnstollen.news.data.NcCredentials
import io.github.mohnstollen.news.data.NcLogin
import io.github.mohnstollen.news.data.QrDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class NcLoginTest {
    @Test
    fun parsesTheLoginQrCodeOfAnAppPassword() {
        // As Nextcloud writes it: user first, server last, values URL-encoded.
        assertEquals(
            NcCredentials("https://cloud.example.org/nextcloud", "erika@example.org", "AbCdE-fGhIj-KlMnO-pQrSt-UvWxY"),
            NcLogin.parse(
                "nc://login/user:erika%40example.org&password:AbCdE-fGhIj-KlMnO-pQrSt-UvWxY" +
                    "&server:https%3A%2F%2Fcloud.example.org%2Fnextcloud%2F",
            ),
        )
        // Any order, unencoded values, other spellings of the prefix, surrounding blanks.
        assertEquals(
            NcCredentials("https://cloud.example.org", "Erika Muster", "geheim:mit:doppelpunkt"),
            NcLogin.parse("  NC://LOGIN/server:https://cloud.example.org&password:geheim:mit:doppelpunkt&user:Erika+Muster\n"),
        )
        // A malformed escape is taken as written.
        assertEquals("100%sicher", NcLogin.parse("nc://login/server:x.de&user:u&password:100%sicher")!!.password)
    }

    @Test
    fun rejectsOtherCodes() {
        listOf(
            "",
            "https://cloud.example.org",
            "nc://login/",
            "nc://login/server:https://x.de&user:erika",
            "nc://login/server:https://x.de&user:&password:p",
            "nc://login/server:&user:u&password:p",
            "WIFI:S:Heimnetz;T:WPA;P:geheim;;",
            "xnc://login/server:a&user:b&password:c",
        ).forEach { assertNull(it, NcLogin.parse(it)) }
    }

    private val loginText = "nc://login/user:erika&password:AbCdE-fGhIj-KlMnO-pQrSt-UvWxY&server:https%3A%2F%2Fcloud.example.org"

    /** A camera-like brightness plane: [rowStride] wider than the picture, padding filled with noise. */
    private fun frame(text: String, size: Int = 360, rowStride: Int = size + 24, inverted: Boolean = false, rotate: Boolean = false): ByteArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 4))
        val random = Random(1)
        val bytes = ByteArray(rowStride * size) { random.nextInt(256).toByte() }
        for (y in 0 until size) for (x in 0 until size) {
            val dark = if (rotate) matrix[y, size - 1 - x] else matrix[x, y]
            val value = if (dark != inverted) 30 else 225
            bytes[y * rowStride + x] = value.toByte()
        }
        return bytes
    }

    @Test
    fun findsTheCodeInACameraFrame() {
        val decoder = QrDecoder()
        assertEquals(loginText, decoder.decode(frame(loginText), 384, 360, 360))
        assertEquals(loginText, decoder.decode(frame(loginText, inverted = true), 384, 360, 360))
        assertEquals(loginText, decoder.decode(frame(loginText, rotate = true), 384, 360, 360))
        // Taken from the frame, the text gives the login data.
        assertEquals("erika", NcLogin.parse(decoder.decode(frame(loginText), 384, 360, 360)!!)!!.user)

        // The last row of a camera buffer often ends right after the picture.
        val tight = frame(loginText).copyOf(384 * 359 + 360)
        assertEquals(loginText, decoder.decode(tight, 384, 360, 360))

        // Nothing to find.
        assertNull(decoder.decode(ByteArray(384 * 360) { 200.toByte() }, 384, 360, 360))
    }
}
