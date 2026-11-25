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
 */
class DownloadTurns(private val rank: suspend (articleIds: List<Long>) -> Map<Long, Int>) {
    private val lock = Mutex()
    private var current: Long? = null
    private val waiting = LinkedHashMap<Long, CompletableDeferred<Unit>>()

    /** Runs [block] for [articleId] when it is its turn; [onWait] first, if it has to wait. */
    suspend fun <T> run(articleId: Long, onWait: suspend () -> Unit = {}, block: suspend () -> T): T {
        val turn = CompletableDeferred<Unit>()
        lock.withLock { waiting[articleId] = turn }
        grantNext()
        try {
            if (!turn.isCompleted) onWait()
            turn.await()
        } catch (e: CancellationException) {
            // Stopped while waiting (cancelled, or the system ended the job): out of the line.
            withContext(NonCancellable) {
                lock.withLock {
                    waiting.remove(articleId)
                    if (current == articleId) current = null
                }
                grantNext()
            }
            throw e
        }
        try {
            return block()
        } finally {
            withContext(NonCancellable) {
                lock.withLock { if (current == articleId) current = null }
                grantNext()
            }
        }
    }

    private suspend fun grantNext() = lock.withLock {
        if (current != null || waiting.isEmpty()) return@withLock
        val ranks = rank(waiting.keys.toList())
        // minBy keeps the first of equals, and the map keeps the order of arrival.
        val next = waiting.keys.minBy { ranks[it] ?: Int.MAX_VALUE }
        current = next
        waiting.remove(next)!!.complete(Unit)
    }
}
