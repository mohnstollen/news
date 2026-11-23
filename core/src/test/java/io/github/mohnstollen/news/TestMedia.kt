package io.github.mohnstollen.news

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Silence as a WAV file (half a second by default), which the player can play without a decoder. */
fun silenceFile(context: Context, name: String, seconds: Double = 0.5): File {
    val rate = 4000
    val samples = (rate * seconds).toInt()
    val data = ByteArray(samples * 2)
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2)
        putShort(2); putShort(16)
        put("data".toByteArray()); putInt(data.size)
    }.array()
    return File(context.filesDir, name).apply { writeBytes(header + data) }
}
