package io.github.mohnstollen.news.playback

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Stops playback after a while or at the end of the episode. The screens set it; the playback
 * service carries it out (both live in the app's process).
 */
class SleepTimer {
    sealed interface Mode {
        /** Pause at this [SystemClock.elapsedRealtime]. */
        data class Until(val atElapsed: Long, val minutes: Int) : Mode

        /** Stop when the current episode ends, instead of going on with the queue. */
        data object EndOfEpisode : Mode
    }

    private val _mode = MutableStateFlow<Mode?>(null)
    val mode: StateFlow<Mode?> = _mode

    fun start(minutes: Int) {
        _mode.value = Mode.Until(SystemClock.elapsedRealtime() + minutes * 60_000L, minutes)
    }

    fun endOfEpisode() {
        _mode.value = Mode.EndOfEpisode
    }

    fun cancel() {
        _mode.value = null
    }

    /** Milliseconds left, or null without a running countdown. */
    fun remainingMs(): Long? =
        (_mode.value as? Mode.Until)?.let { (it.atElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0) }

    companion object {
        val CHOICES = listOf(5, 15, 30, 45, 60, 90)

        /** The last seconds before pausing, the volume goes down gradually. */
        const val FADE_MS = 10_000L
    }
}
