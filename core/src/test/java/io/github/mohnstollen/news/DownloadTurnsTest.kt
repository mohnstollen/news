package io.github.mohnstollen.news

import io.github.mohnstollen.news.playback.DownloadTurns
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/** One download at a time, the next by queue order. */
class DownloadTurnsTest {
    @Test
    fun oneAtATimeInQueueOrder() = runBlocking {
        // Places in the queue: 2 first, then 3, then 1; 4 is not queued and comes last.
        val places = mapOf(2L to 0, 3L to 1, 1L to 2)
        val turns = DownloadTurns { ids -> ids.associateWith { places[it] ?: Int.MAX_VALUE } }
        val order = mutableListOf<Long>()
        val waited = mutableListOf<Long>()
        val gate = CompletableDeferred<Unit>()

        val running = launch { turns.run(9) { order += 9; gate.await() } }
        yield()
        val jobs = listOf(4L, 1L, 2L, 3L).map { id ->
            launch { turns.run(id, onWait = { waited += id }) { order += id } }
        }
        yield()
        assertEquals("all wait while one runs", listOf(4L, 1L, 2L, 3L), waited)
        assertEquals(listOf(9L), order)

        gate.complete(Unit)
        (jobs + running).joinAll()
        assertEquals(listOf(9L, 2L, 3L, 1L, 4L), order)
    }

    @Test
    fun aCancelledWaiterLeavesTheLine() = runBlocking {
        val turns = DownloadTurns { ids -> ids.associateWith { it.toInt() } }
        val order = mutableListOf<Long>()
        val gate = CompletableDeferred<Unit>()
        val running = launch { turns.run(9) { order += 9; gate.await() } }
        yield()
        val one = launch { turns.run(1) { order += 1 } }
        val two = launch { turns.run(2) { order += 2 } }
        yield()
        one.cancel()
        yield()
        gate.complete(Unit)
        listOf(running, two).joinAll()
        assertEquals(listOf(9L, 2L), order)

        // And a cancelled running one hands its turn on.
        val stuck = CompletableDeferred<Unit>()
        val first = launch { turns.run(5) { stuck.await() } }
        yield()
        val next = launch { turns.run(6) { order += 6 } }
        yield()
        first.cancel()
        next.join()
        assertEquals(listOf(9L, 2L, 6L), order)
    }

    @Test
    fun aNewWorkerForTheSameEpisodeKeepsItsPlaceWhenTheOldOneStops() = runBlocking {
        // The system stops a waiting worker and starts a new one for the same episode; the old one
        // only leaves after the new one is in line. The new one must still get its turn.
        val turns = DownloadTurns { ids -> ids.associateWith { 0 } }
        val order = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val running = launch { turns.run(9) { order += "9"; gate.await() } }
        yield()
        val old = launch { turns.run(1) { order += "old 1" } }
        yield()
        val new = launch { turns.run(1) { order += "new 1" } }
        yield()
        old.cancel()
        yield()
        gate.complete(Unit)
        listOf(running, new).joinAll()
        assertEquals(listOf("9", "new 1"), order)
        assertEquals("nobody holds the turn afterwards", false, turns.busy())
    }
}
