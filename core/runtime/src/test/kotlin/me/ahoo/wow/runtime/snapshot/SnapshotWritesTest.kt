/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.runtime.snapshot

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotSaveOutcome
import me.ahoo.wow.spi.snapshot.SnapshotStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/** The batched snapshot writes of a partition (decision D248 ②). */
class SnapshotWritesTest {
    private val type = AggregateType("shop", "counter")

    private fun snapshot(id: String, version: Int = 1) =
        SnapshotRecord(type.ref("t", id), version, "f", """{"id":"$id"}""", firstEventTime = 0, lastEventTime = 0)

    /**
     * Records each batch it is given; while [gate] is set, a batch waits for it first. [failAll] fails a whole call,
     * [failing] fails the snapshots of those ids alone, as saves too.
     */
    private class Store(private val delegate: SnapshotStore = InMemorySnapshotStore()) : SnapshotStore by delegate {
        val batches = CopyOnWriteArrayList<List<String>>()
        val saves = CopyOnWriteArrayList<String>()

        @Volatile
        var gate: CompletableDeferred<Unit>? = null

        @Volatile
        var failAll: Exception? = null

        @Volatile
        var failing: Set<String> = emptySet()

        override suspend fun saveAll(snapshots: List<SnapshotRecord>): List<SnapshotSaveOutcome> {
            batches += snapshots.map { "${it.ref.id}@${it.version}" }
            gate?.await()
            failAll?.let { throw it }
            return snapshots.map {
                if (it.ref.id in failing) {
                    SnapshotSaveOutcome.Failed(IllegalArgumentException(it.ref.id))
                } else {
                    SnapshotSaveOutcome.of(delegate.save(it))
                }
            }
        }

        override suspend fun save(snapshot: SnapshotRecord): Boolean {
            saves += snapshot.ref.id
            if (snapshot.ref.id in failing) throw IllegalArgumentException(snapshot.ref.id)
            return delegate.save(snapshot)
        }
    }

    private val store = Store()

    /** Holds each call until the test releases it, by its index, or [releaseAll] opens it for good. */
    private class Held(private val delegate: SnapshotStore = InMemorySnapshotStore()) : SnapshotStore by delegate {
        class Call(val ids: List<String>) {
            val gate = CompletableDeferred<Unit>()
        }

        val calls = CopyOnWriteArrayList<Call>()

        @Volatile
        private var open = false

        override suspend fun saveAll(snapshots: List<SnapshotRecord>): List<SnapshotSaveOutcome> {
            val call = Call(snapshots.map { "${it.ref.id}@${it.version}" }).also { calls += it }
            if (!open) call.gate.await()
            return delegate.saveAll(snapshots)
        }

        fun release(index: Int) {
            calls[index].gate.complete(Unit)
        }

        fun releaseAll() {
            open = true
            calls.forEach { it.gate.complete(Unit) }
        }

        val written: List<List<String>> get() = calls.map { it.ids }
    }

    @Test
    fun `a leader ending while another gathers leaves no backlog behind a single write`(): Unit = runBlocking {
        val held = Held()
        val writes = SnapshotWrites(held, maxBatch = 2, committers = 2)
        val a = async { writes.save(snapshot("a")) }
        eventually { held.calls.size == 1 }
        // b leads the second write and gathers while c to f arrive, and a's write ends meanwhile, its place given up.
        val b = async { writes.save(snapshot("b")) }
        val rest = listOf("c", "d", "e", "f").map { id -> async { writes.save(snapshot(id)) } }
        held.release(0)
        // b's batch holds two; the saves it left get the second write at once, though no save arrives.
        eventually { held.calls.size == 3 }
        held.written.assert().containsExactly(listOf("a@1"), listOf("b@1", "c@1"), listOf("d@1", "e@1"))
        writes.waiting.assert().isEqualTo(1)
        held.releaseAll()
        (listOf(a, b) + rest).awaitAll().assert().containsOnly(true)
        held.written.last().assert().containsExactly("f@1")
    }

    @Test
    fun `a save handed the lead and cancelled before its first take hands it on`(): Unit = runBlocking {
        val held = Held()
        val writes = SnapshotWrites(held, maxBatch = 8, committers = 2)
        val a = async { writes.save(snapshot("a")) }
        eventually { held.calls.size == 1 }
        val b = async { writes.save(snapshot("b")) }
        eventually { held.calls.size == 2 }
        // Two writes run: c and d wait.
        val c = async { writes.save(snapshot("c")) }
        val d = async { writes.save(snapshot("d")) }
        eventually { writes.waiting == 2 }
        // a's write ends and hands its place to c, which is cancelled before it takes a batch: d leads then.
        held.release(0)
        yield()
        c.cancel()
        eventually { held.calls.size == 3 }
        held.written.last().assert().containsExactly("d@1")
        held.releaseAll()
        listOf(a, b, d).awaitAll().assert().containsOnly(true)
        held.load(type.ref("t", "c")).assert().isNull()
        // No place is lost: a save arriving now leads and is written.
        writes.save(snapshot("e")).assert().isTrue()
    }

    @Test
    fun `a leader cancelled at its yield while a second write runs hands its place to the save waiting`(): Unit =
        runBlocking {
            val held = Held()
            val writes = SnapshotWrites(held, maxBatch = 8, committers = 2)
            val a = async { writes.save(snapshot("a")) }
            eventually { held.calls.size == 1 }
            // x leads the second write and gathers; y waits for it; x is cancelled before it takes its batch.
            val x = async { writes.save(snapshot("x")) }
            val y = async { writes.save(snapshot("y")) }
            yield()
            x.cancel()
            eventually { held.calls.size == 2 }
            held.written.last().assert().containsExactly("y@1")
            held.releaseAll()
            listOf(a, y).awaitAll().assert().containsOnly(true)
            held.load(type.ref("t", "x")).assert().isNull()
            writes.save(snapshot("z")).assert().isTrue()
        }

    @Test
    fun `an earlier version landing after a later one in another call ends refused, not failed`(): Unit =
        runBlocking {
            val held = Held()
            val writes = SnapshotWrites(held, maxBatch = 8, committers = 2)
            // Two versions of one aggregate in two calls at once, which the subscription never sends (see SnapshotWrites).
            val later = async { writes.save(snapshot("a", 2)) }
            eventually { held.calls.size == 1 }
            val earlier = async { writes.save(snapshot("a", 1)) }
            eventually { held.calls.size == 2 }
            held.release(0)
            later.await().assert().isTrue()
            held.release(1)
            earlier.await().assert().isFalse()
            held.load(type.ref("t", "a"))!!.version.assert().isEqualTo(2)
        }

    @Test
    fun `fewer than one committer is refused`() {
        assertThrows<IllegalArgumentException> { SnapshotWrites(store, 8, committers = 0) }
    }

    @Test
    fun `a save with no write running is written at once, alone`(): Unit = runBlocking {
        val writes = SnapshotWrites(store, 8)
        writes.save(snapshot("a")).assert().isTrue()
        writes.save(snapshot("a")).assert().isFalse()
        store.batches.assert().containsExactly(listOf("a@1"), listOf("a@1"))
        writes.waiting.assert().isEqualTo(0)
    }

    @Test
    fun `the saves arriving while a write runs are written together next, at most a batch at a time`(): Unit =
        runBlocking {
            val writes = SnapshotWrites(store, 2, committers = 1)
            val gate = CompletableDeferred<Unit>().also { store.gate = it }
            val first = async { writes.save(snapshot("a")) }
            eventually { store.batches.size == 1 }
            val waiting = listOf("b", "c", "d").map { id -> async { writes.save(snapshot(id)) } }
            eventually { writes.waiting == 3 }
            store.gate = null
            gate.complete(Unit)
            (listOf(first) + waiting).awaitAll().assert().containsExactly(true, true, true, true)
            store.batches.assert().containsExactly(listOf("a@1"), listOf("b@1", "c@1"), listOf("d@1"))
        }

    @Test
    fun `with two committers a second write carries what arrived while the first runs, and the rest wait`(): Unit =
        runBlocking {
            val writes = SnapshotWrites(store, 8, committers = 2)
            val gate = CompletableDeferred<Unit>().also { store.gate = it }
            val first = async { writes.save(snapshot("a")) }
            eventually { store.batches.size == 1 }
            // b leads the second write; c, ready while b gathers, joins it.
            val second = listOf("b", "c").map { id -> async { writes.save(snapshot(id)) } }
            eventually { store.batches.size == 2 }
            // Both writes run: d waits for one of them to end.
            val last = async { writes.save(snapshot("d")) }
            eventually { writes.waiting == 1 }
            store.batches.size.assert().isEqualTo(2)
            store.gate = null
            gate.complete(Unit)
            (listOf(first) + second + last).awaitAll().assert().containsExactly(true, true, true, true)
            store.batches.assert().containsExactly(listOf("a@1"), listOf("b@1", "c@1"), listOf("d@1"))
            writes.waiting.assert().isEqualTo(0)
        }

    @Test
    fun `the saves ready together are one batch`(): Unit = runBlocking {
        val writes = SnapshotWrites(store, 8)
        listOf("a", "b", "c").map { id -> async { writes.save(snapshot(id)) } }.awaitAll()
            .assert().containsExactly(true, true, true)
        store.batches.assert().containsExactly(listOf("a@1", "b@1", "c@1"))
    }

    @Test
    fun `of one aggregate's snapshots in a batch only the highest version is written`(): Unit = runBlocking {
        val writes = SnapshotWrites(store, 8)
        val saved = listOf(snapshot("a", 2), snapshot("a", 3), snapshot("a", 1), snapshot("b"))
            .map { async { writes.save(it) } }.awaitAll()
        saved.assert().containsExactly(false, true, false, true)
        store.batches.assert().containsExactly(listOf("a@3", "b@1"))
        store.load(type.ref("t", "a"))?.version.assert().isEqualTo(3)
    }

    @Test
    fun `a snapshot the store fails alone fails its own save, and its lower versions with it`(): Unit = runBlocking {
        val writes = SnapshotWrites(store, 8)
        store.failing = setOf("bad")
        val saves = listOf(snapshot("a"), snapshot("bad", 1), snapshot("bad", 2)).map {
            async { runCatching { writes.save(it) } }
        }
        val outcomes = saves.awaitAll()
        outcomes[0].getOrNull().assert().isEqualTo(true)
        outcomes[1].exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        outcomes[2].exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a batch whose call failed is saved again one by one, so a refused snapshot fails alone`(): Unit = runBlocking {
        val writes = SnapshotWrites(store, 8)
        store.failAll = IllegalStateException("The batch failed.")
        store.failing = setOf("bad")
        val outcomes = listOf(
            snapshot("a"),
            snapshot("bad")
        ).map { async { runCatching { writes.save(it) } } }.awaitAll()
        outcomes[0].getOrNull().assert().isEqualTo(true)
        outcomes[1].exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        store.saves.assert().containsExactly("a", "bad")
    }

    @Test
    fun `a batch failing for an unavailable store fails every save of it`(): Unit = runBlocking {
        val writes = SnapshotWrites(store, 8)
        store.failAll = StorageUnavailableException("Down.")
        listOf(snapshot("a"), snapshot("b")).map { async { runCatching { writes.save(it) } } }.awaitAll().forEach {
            it.exceptionOrNull().assert().isInstanceOf(StorageUnavailableException::class.java)
        }
        store.saves.assert().isEmpty()
        // Alone, the call's failure is the snapshot's.
        store.failAll = IllegalStateException("Refused.")
        assertThrows<IllegalStateException> { writes.save(snapshot("c")) }
        store.saves.assert().isEmpty()
    }

    @Test
    fun `a cancellation the store met fails the saves, not the lanes waiting`(): Unit = runBlocking {
        val writes = SnapshotWrites(store, 8)
        store.failAll = CancellationException("The store's own timeout.")
        assertThrows<IllegalStateException> { writes.save(snapshot("a")) }.message
            .assert().isEqualTo("The store's own timeout.")
    }

    @Test
    fun `an error the store throws fails every save of the batch`(): Unit = runBlocking {
        val writes = SnapshotWrites(store, 8)
        store.failAll = null
        val failing = object : SnapshotStore by store {
            override suspend fun saveAll(snapshots: List<SnapshotRecord>): List<SnapshotSaveOutcome> =
                throw NotImplementedError("Not yet.")
        }
        val erring = SnapshotWrites(failing, 8)
        listOf(snapshot("a"), snapshot("b")).map { async { runCatching { erring.save(it) } } }.awaitAll().forEach {
            it.exceptionOrNull().assert().isInstanceOf(NotImplementedError::class.java)
        }
        writes.save(snapshot("c")).assert().isTrue()
    }

    @Test
    fun `a save cancelled once a batch took it is still written`(): Unit = runBlocking {
        val writes = SnapshotWrites(store, 8)
        val gate = CompletableDeferred<Unit>().also { store.gate = it }
        val leader = async { writes.save(snapshot("a")) }
        val taken = async { writes.save(snapshot("b")) }
        eventually { store.batches.size == 1 }
        taken.cancel()
        gate.complete(Unit)
        leader.await().assert().isTrue()
        store.load(type.ref("t", "b"))?.version.assert().isEqualTo(1)
        writes.waiting.assert().isEqualTo(0)
    }

    @Test
    fun `a leader cancelled puts the saves it took back, and the next one writes them`(): Unit = runBlocking {
        val writes = SnapshotWrites(store, 8)
        val gate = CompletableDeferred<Unit>().also { store.gate = it }
        val leader = async { writes.save(snapshot("a")) }
        val waiting = async { writes.save(snapshot("b")) }
        // Both are taken in the leader's batch, which waits in the store.
        eventually { store.batches.size == 1 }
        store.batches.single().assert().containsExactly("a@1", "b@1")
        store.gate = null
        leader.cancel()
        waiting.await().assert().isTrue()
        store.batches.last().assert().containsExactly("b@1")
        writes.waiting.assert().isEqualTo(0)
    }

    @Test
    fun `a leader cancelled before its first batch leaves the queue, and the next save is written`(): Unit =
        runBlocking {
            val writes = SnapshotWrites(store, 8)
            // Undispatched: the leader runs to its yield before the batch, its own save queued.
            val leader = async(start = CoroutineStart.UNDISPATCHED) { writes.save(snapshot("a")) }
            writes.waiting.assert().isEqualTo(1)
            leader.cancelAndJoin()
            writes.waiting.assert().isEqualTo(0)
            withTimeout(5.seconds) { writes.save(snapshot("b")) }.assert().isTrue()
            store.batches.assert().containsExactly(listOf("b@1"))
            store.load(type.ref("t", "a")).assert().isNull()
        }

    @Test
    fun `a leader cancelled before its first batch hands the saves queued behind it to the next leader`(): Unit =
        runBlocking {
            val writes = SnapshotWrites(store, 8)
            val leader = async(start = CoroutineStart.UNDISPATCHED) { writes.save(snapshot("a")) }
            val waiting = listOf("b", "c").map { id ->
                async(start = CoroutineStart.UNDISPATCHED) { writes.save(snapshot(id)) }
            }
            writes.waiting.assert().isEqualTo(3)
            leader.cancelAndJoin()
            withTimeout(5.seconds) { waiting.awaitAll() }.assert().containsExactly(true, true)
            store.batches.assert().containsExactly(listOf("b@1", "c@1"))
            store.load(type.ref("t", "a")).assert().isNull()
            writes.waiting.assert().isEqualTo(0)
        }

    @Test
    fun `a waiting save cancelled leaves the queue, handed the lead or not`(): Unit = runBlocking {
        val writes = SnapshotWrites(store, 8, committers = 1)
        val gate = CompletableDeferred<Unit>().also { store.gate = it }
        val leader = async { writes.save(snapshot("a")) }
        eventually { store.batches.size == 1 }
        val next = async { writes.save(snapshot("b")) }
        val dropped = async { writes.save(snapshot("c")) }
        val last = async { writes.save(snapshot("d")) }
        eventually { writes.waiting == 3 }
        // Not handed the lead: it only leaves the queue.
        dropped.cancel()
        eventually { writes.waiting == 2 }
        store.gate = null
        gate.complete(Unit)
        // The leader ends and hands the lead to b, which is cancelled before it takes it: d leads then.
        yield()
        next.cancel()
        leader.await().assert().isTrue()
        last.await().assert().isTrue()
        store.batches.last().assert().containsExactly("d@1")
        store.load(type.ref("t", "c")).assert().isNull()
    }
}
