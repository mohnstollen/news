package io.github.mohnstollen.news.data

import org.json.JSONArray
import org.json.JSONObject

/** A chapter of an episode: where it starts and what it is called. */
data class Chapter(val startMs: Long, val title: String)

/**
 * Chapters from the three usual sources: Podlove Simple Chapters in the feed, Podcasting 2.0
 * JSON files the feed links to, and ID3 tags in the file (read by the player). Stored per episode
 * as a small JSON array.
 */
object Chapters {
    /** Podlove "HH:MM:SS.mmm", also "MM:SS", "SS" and fractions. Null if unreadable. */
    fun parseTime(text: String): Long? {
        val parts = text.trim().split(':')
        if (parts.isEmpty() || parts.size > 3) return null
        var seconds = 0.0
        for (part in parts) {
            val value = part.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return null
            seconds = seconds * 60 + value
        }
        return (seconds * 1000).toLong()
    }

    /** Podcasting 2.0 JSON chapters (`{"chapters":[{"startTime":12.5,"title":"…"}]}`). */
    fun parseJson(json: String): List<Chapter> {
        val array = JSONObject(json).optJSONArray("chapters") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            // Chapters only meant for the table of contents of other apps are still worth showing.
            val start = o.optDouble("startTime", Double.NaN).takeIf { it.isFinite() && it >= 0 } ?: return@mapNotNull null
            Chapter((start * 1000).toLong(), o.optString("title").trim())
        }.let(::clean)
    }

    /** In order, without duplicates of a start time, untitled ones numbered. */
    fun clean(chapters: List<Chapter>): List<Chapter> =
        chapters.sortedBy { it.startMs }.distinctBy { it.startMs }
            .mapIndexed { i, c -> if (c.title.isBlank()) c.copy(title = "Kapitel ${i + 1}") else c }

    fun encode(chapters: List<Chapter>): String =
        JSONArray(chapters.map { JSONObject().put("t", it.startMs).put("title", it.title) }).toString()

    fun decode(json: String?): List<Chapter> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Chapter(o.getLong("t"), o.optString("title"))
            }
        }.getOrDefault(emptyList())
    }

    /** Index of the chapter playing at [positionMs]; -1 before the first. */
    fun current(chapters: List<Chapter>, positionMs: Long): Int = chapters.indexOfLast { it.startMs <= positionMs }
}
