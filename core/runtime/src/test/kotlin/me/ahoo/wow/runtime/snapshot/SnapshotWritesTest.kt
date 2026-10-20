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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
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
                if (it.ref.id in failing) SnapshotSaveOutcome.Failed(IllegalArgumentException(it.ref.id)) else
                    SnapshotSaveOutcome.of(delegate.save(it))
            }
        }

        override suspend fun save(snapshot: SnapshotRecord): Boolean {
            saves += snapshot.ref.id
            if (snapshot.ref.id in failing) throw IllegalArgumentException(snapshot.ref.id)
            return delegate.save(snapshot)
        }
    }

    private val store = Store()

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
            val writes = SnapshotWrites(store, 2)
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
        val saves = listOf(snapshot("a"), snapshot("bad", 1), snapshot("bad", 2)).map { async { runCatching { writes.save(it) } } }
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
        val outcomes = listOf(snapshot("a"), snapshot("bad")).map { async { runCatching { writes.save(it) } } }.awaitAll()
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
    fun `a waiting save cancelled leaves the queue, handed the lead or not`(): Unit = runBlocking {
        val writes = SnapshotWrites(store, 8)
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
