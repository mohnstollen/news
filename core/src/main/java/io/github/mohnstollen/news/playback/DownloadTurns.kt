package io.github.mohnstollen.news.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One download at a time: on a slow connection, parallel downloads share it and none is done soon;
 * one after the other, the next episode to hear is ready first. Whose turn is next decides
 * [rank] (smaller first): the queue order; among equals, who came first.
 *
 * Each call waits with a token of its own, so a worker for the same episode that starts while an
 * old one is still being stopped cannot take or lose the other's place.
 */
class DownloadTurns(private val rank: suspend (articleIds: List<Long>) -> Map<Long, Int>) {
    private class Waiter(val articleId: Long) {
        val turn = CompletableDeferred<Unit>()
    }

    private val lock = Mutex()
    private var current: Waiter? = null
    private val waiting = mutableListOf<Waiter>()

    /** Whether a download holds the turn now; for tests. */
    internal suspend fun busy(): Boolean = lock.withLock { current != null }

    /** Runs [block] for [articleId] when it is its turn; [onWait] first, if it has to wait. */
    suspend fun <T> run(articleId: Long, onWait: suspend () -> Unit = {}, block: suspend () -> T): T {
        val me = Waiter(articleId)
        lock.withLock { waiting += me }
        grantNext()
        try {
            if (!me.turn.isCompleted) onWait()
            me.turn.await()
        } catch (e: CancellationException) {
            // Stopped while waiting (cancelled, or the system ended the job): out of the line,
            // and if the turn had just come, on to the next.
            withContext(NonCancellable) { leave(me) }
            throw e
        }
        try {
            return block()
        } finally {
            withContext(NonCancellable) { leave(me) }
        }
    }

    private suspend fun leave(me: Waiter) {
        lock.withLock {
            waiting.remove(me)
            if (current === me) current = null
        }
        grantNext()
    }

    private suspend fun grantNext() = lock.withLock {
        if (current != null || waiting.isEmpty()) return@withLock
        val ranks = rank(waiting.map { it.articleId }.distinct())
        // minBy keeps the first of equals, and the list keeps the order of arrival.
        val next = waiting.minBy { ranks[it.articleId] ?: Int.MAX_VALUE }
        waiting.remove(next)
        current = next
        next.turn.complete(Unit)
    }
}
