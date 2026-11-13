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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotSaveOutcome
import me.ahoo.wow.spi.snapshot.SnapshotStore
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * The snapshot writes behind ([SnapshotWrites], `catchUp`): full takes with saves still waiting raise the writes in
 * flight to the catch-up count, small takes lower them to the committers again.
 */
class SnapshotWritesCatchUpTest {
    private val type = AggregateType("shop", "counter")

    private fun snapshot(id: String) =
        SnapshotRecord(type.ref("t", id), 1, "f", """{"id":"$id"}""", firstEventTime = 0, lastEventTime = 0)

    @Test
    fun `full takes with saves still waiting should start more writes, up to the catch-up count`(): Unit =
        runBlocking {
            val store = HeldStore()
            val writes = SnapshotWrites(store, maxBatch = 2, committers = 1, catchUp = 3, retireBelow = 1)
            val counts = writes.counts()
            val first = async(Dispatchers.Default) { writes.save(snapshot("a")) }
            eventually(5.seconds) { store.inFlight.get() == 1 }
            // Ten saves wait behind the one write the committer runs: every take of two is now full with more waiting.
            val waiting = (0 until 10).map { async(Dispatchers.Default) { writes.save(snapshot("w$it")) } }
            eventually(5.seconds) { writes.waiting == 10 }
            store.first.complete(Unit)
            // The calls after the first are held: each full take starts one more leader, whose call waits, up to three.
            eventually(5.seconds) { store.inFlight.get() == 3 }
            counts.allowed.assert().isEqualTo(3)
            store.rest.complete(Unit)
            (listOf(first) + waiting).awaitAll()
            // Certain: the limit is three, so never more calls at once than three, whatever the interleaving.
            store.most.get().assert().isEqualTo(3)
        }

    @Test
    fun `takes that are never full should keep the committers' count`(): Unit = runBlocking {
        val store = HeldStore()
        val writes = SnapshotWrites(store, maxBatch = 8, committers = 1, catchUp = 3)
        val counts = writes.counts()
        val first = async(Dispatchers.Default) { writes.save(snapshot("a")) }
        eventually(5.seconds) { store.inFlight.get() == 1 }
        val waiting = (0 until 6).map { async(Dispatchers.Default) { writes.save(snapshot("w$it")) } }
        eventually(5.seconds) { writes.waiting == 6 }
        store.first.complete(Unit)
        // The six are taken together into the next call, which is held: nothing waits any more.
        eventually(5.seconds) { writes.waiting == 0 && store.inFlight.get() == 1 }
        store.rest.complete(Unit)
        (listOf(first) + waiting).awaitAll()
        // Certain: six saves fit one take of eight, which is not full, so the limit never left one.
        store.most.get().assert().isEqualTo(1)
        counts.allowed.assert().isEqualTo(1)
    }

    @Test
    fun `a small take should lower the limit, and the writes over it should give their places up`(): Unit =
        runBlocking {
            val store = HeldStore()
            val writes = SnapshotWrites(store, maxBatch = 2, committers = 1, catchUp = 3, retireBelow = 2)
            val counts = writes.counts()
            val first = async(Dispatchers.Default) { writes.save(snapshot("a")) }
            eventually(5.seconds) { store.inFlight.get() == 1 }
            val waiting = (0 until 10).map { async(Dispatchers.Default) { writes.save(snapshot("w$it")) } }
            eventually(5.seconds) { writes.waiting == 10 }
            store.first.complete(Unit)
            eventually(5.seconds) { store.inFlight.get() == 3 }
            store.rest.complete(Unit)
            (listOf(first) + waiting).awaitAll()

            // One save alone: its take of one is under two, so the writes are caught up.
            writes.save(snapshot("alone")).assert().isTrue()
            counts.allowed.assert().isEqualTo(1)
            // Held again: one write runs, and a save behind it waits rather than leading a second.
            store.most.set(0)
            store.rest = CompletableDeferred()
            val again = async(Dispatchers.Default) { writes.save(snapshot("b")) }
            eventually(5.seconds) { store.inFlight.get() == 1 }
            val behind = async(Dispatchers.Default) { writes.save(snapshot("c")) }
            eventually(5.seconds) { writes.waiting == 1 }
            store.inFlight.get().assert().isEqualTo(1)
            store.rest.complete(Unit)
            listOf(again, behind).awaitAll()
            store.most.get().assert().isEqualTo(1)
        }

    @Test
    fun `a store that says no catch-up count should keep its writes in flight whatever the takes`(): Unit = runBlocking {
        val store = HeldStore()
        val writes = SnapshotWrites(store, maxBatch = 2, committers = 1)
        val first = async(Dispatchers.Default) { writes.save(snapshot("a")) }
        eventually(5.seconds) { store.inFlight.get() == 1 }
        val waiting = (0 until 10).map { async(Dispatchers.Default) { writes.save(snapshot("w$it")) } }
        eventually(5.seconds) { writes.waiting == 10 }
        store.first.complete(Unit)
        store.rest.complete(Unit)
        (listOf(first) + waiting).awaitAll()
        // Certain: the catch-up count is the committers' one, so a full take raises the limit to where it is.
        store.most.get().assert().isEqualTo(1)
    }

    /**
     * Holds its first call until [first] and every later one until [rest], counting the calls in flight and the most at
     * once.
     */
    private class HeldStore(private val delegate: SnapshotStore = InMemorySnapshotStore()) : SnapshotStore by delegate {
        val first = CompletableDeferred<Unit>()

        @Volatile
        var rest = CompletableDeferred<Unit>()
        val inFlight = AtomicInteger()
        val most = AtomicInteger()
        private val calls = AtomicInteger()

        override suspend fun saveAll(snapshots: List<SnapshotRecord>): List<SnapshotSaveOutcome> {
            val gate = if (calls.getAndIncrement() == 0) first else rest
            most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            try {
                gate.await()
                return delegate.saveAll(snapshots)
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }
}
