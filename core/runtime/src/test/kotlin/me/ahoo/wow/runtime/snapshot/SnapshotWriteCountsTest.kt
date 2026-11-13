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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotSaveOutcome
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.store.StoreCallMarks
import org.junit.jupiter.api.Test
import java.util.Collections
import kotlin.time.Duration.Companion.seconds

/** [SnapshotWrites.counts]: the saves taken, the snapshots written and each write's time, only once read. */
class SnapshotWriteCountsTest {
    private val type = AggregateType("shop", "counter")

    private fun snapshot(id: String, version: Int = 1) =
        SnapshotRecord(type.ref("t", id), version, "f", """{"id":"$id"}""", firstEventTime = 0, lastEventTime = 0)

    @Test
    fun `a clocked write should count its saves and its snapshots and be split into the steps its store marked`(): Unit =
        runBlocking {
            val store = MarkingStore()
            val writes = SnapshotWrites(store, maxBatch = 8, committers = 1)
            val counts = writes.counts()
            writes.counts().assert().isSameAs(counts)
            store.gate = CompletableDeferred()
            val first = async(Dispatchers.Default) { writes.save(snapshot("a")) }
            eventually(5.seconds) { counts.writing == 1 }
            // The one leader is in the first write: three saves wait, two of one aggregate, for the next write.
            val waiting = listOf(snapshot("b"), snapshot("c", 1), snapshot("c", 2))
                .map { async(Dispatchers.Default) { writes.save(it) } }
            eventually(5.seconds) { writes.waiting == 3 }
            store.gate?.complete(Unit)
            (listOf(first) + waiting).forEach { it.await() }

            // Two writes, certainly: one leader at a time (committers = 1), and the three saves all waited while the first
            // write was held, so the next took them in one batch (maxBatch = 8). A write is clocked once its saves
            // ended, after their savers resumed: waited for.
            eventually(5.seconds) { counts.clock.calls == 2L && counts.writing == 0 }
            counts.saves.assert().isEqualTo(4L)
            counts.snapshots.assert().isEqualTo(3L)
            counts.clock.marked.assert().isEqualTo(2L)
            counts.clock.commanded.assert().isEqualTo(2L)
            store.found.assert().containsOnly(true)
            val steps = with(counts.clock) { listOf(enter, hop, acquire, bind, statement, release, hopBack) }
            steps.sum().assert().isBetween(counts.clock.call - steps.size, counts.clock.call)
            counts.clock.statement.assert().isGreaterThanOrEqualTo(2 * 1_000L)
        }

    @Test
    fun `writes whose counts are not read should hand their store no marks`(): Unit = runBlocking {
        val store = MarkingStore()
        SnapshotWrites(store, maxBatch = 8).save(snapshot("a")).assert().isTrue()
        store.found.assert().containsExactly(false)
    }

    @Test
    fun `a write whose store call failed should be clocked to its failure`(): Unit = runBlocking {
        val failing = object : SnapshotStore by InMemorySnapshotStore() {
            override suspend fun saveAll(snapshots: List<SnapshotRecord>): List<SnapshotSaveOutcome> =
                throw StorageUnavailableException("The store is down.")
        }
        val writes = SnapshotWrites(failing, maxBatch = 8)
        val counts = writes.counts()
        runCatching { writes.save(snapshot("a")) }.exceptionOrNull()
            .assert().isInstanceOf(StorageUnavailableException::class.java)
        eventually(5.seconds) { counts.clock.calls == 1L }
        counts.writing.assert().isZero()
        counts.clock.marked.assert().isZero()
    }

    @Test
    fun `a leader cancelled in its write should leave no write in a call and none clocked`(): Unit = runBlocking {
        val store = MarkingStore()
        val writes = SnapshotWrites(store, maxBatch = 8)
        val counts = writes.counts()
        store.gate = CompletableDeferred()
        val saving = async(Dispatchers.Default) { writes.save(snapshot("a")) }
        eventually(5.seconds) { counts.writing == 1 }
        saving.cancelAndJoin()
        // The write's end ran in the cancelled leader before its join returned.
        counts.writing.assert().isZero()
        counts.clock.calls.assert().isZero()
    }

    /**
     * Marks each instant a store sees, its blocking work on a thread of its own, a millisecond's statement and one
     * command; while [gate] is set, a call waits for it in its statement. [found] says, per call, whether it had marks.
     */
    private class MarkingStore(
        private val delegate: SnapshotStore = InMemorySnapshotStore(),
    ) : SnapshotStore by delegate {
        val found: MutableList<Boolean> = Collections.synchronizedList(ArrayList())

        @Volatile
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun saveAll(snapshots: List<SnapshotRecord>): List<SnapshotSaveOutcome> {
            val marks = currentCoroutineContext()[StoreCallMarks]?.apply { entered() }
            found += marks != null
            withContext(Dispatchers.IO) {
                marks?.onThread()
                marks?.acquired()
                marks?.sent()
                Thread.sleep(1)
                marks?.commanded(COMMAND_NANOS)
                marks?.read()
            }
            gate?.await()
            marks?.released()
            return delegate.saveAll(snapshots)
        }
    }

    private companion object {
        const val COMMAND_NANOS = 500_000L
    }
}
