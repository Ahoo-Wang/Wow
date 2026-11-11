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

package me.ahoo.wow.runtime.sink

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.source.orderRecord
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.sink.Sink
import me.ahoo.wow.spi.state.StateEventRecord
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/** The batched writes of a sink subscription (D413): a group commit of the runs of all its lanes. */
class SinkWritesTest {
    /**
     * Records each write it is given, by `id@version`; while [gate] is set, a write waits for it first. [failAll]
     * fails every write, [refusing] fails any write holding one of those records.
     */
    private class Recording : Sink {
        val writes = CopyOnWriteArrayList<List<String>>()

        @Volatile
        var gate: CompletableDeferred<Unit>? = null

        @Volatile
        var failAll: Throwable? = null

        @Volatile
        var refusing: Set<String> = emptySet()

        override suspend fun write(records: List<StateEventRecord>) {
            val names = records.map { "${it.ref.id}@${it.version}" }
            writes += names
            gate?.await()
            failAll?.let { throw it }
            names.firstOrNull { it in refusing }?.let { throw IllegalArgumentException(it) }
        }
    }

    private val sink = Recording()

    private fun run(id: String, vararg versions: Int) = versions.map { orderRecord(id, it) }

    @Test
    fun `a run with no write running is written at once, as one write`(): Unit = runBlocking {
        val writes = SinkWrites(sink, 8)
        val written = writes.write(run("a", 1, 2, 3))
        written.done.assert().isEqualTo(3)
        written.failure.assert().isNull()
        sink.writes.assert().containsExactly(listOf("a@1", "a@2", "a@3"))
        writes.write(emptyList()).done.assert().isEqualTo(0)
        sink.writes.size.assert().isEqualTo(1)
    }

    @Test
    fun `the runs arriving while a write runs are written together next, whole, at most a batch of records`(): Unit =
        runBlocking {
            val writes = SinkWrites(sink, 4)
            val gate = CompletableDeferred<Unit>().also { sink.gate = it }
            val first = async { writes.write(run("a", 1)) }
            eventually { sink.writes.size == 1 }
            val waiting = listOf(run("b", 1, 2), run("c", 1, 2), run("d", 1)).map { async { writes.write(it) } }
            eventually { writes.waiting == 3 }
            sink.gate = null
            gate.complete(Unit)
            (listOf(first) + waiting).awaitAll().map { it.done }.assert().containsExactly(1, 2, 2, 1)
            // b and c fill a batch of four; d waits for the next: a run is never split between batches.
            sink.writes.assert().containsExactly(listOf("a@1"), listOf("b@1", "b@2", "c@1", "c@2"), listOf("d@1"))
        }

    @Test
    fun `a run is not durable, and its write does not return, before the sink's write returned`(): Unit = runBlocking {
        val writes = SinkWrites(sink, 8)
        val gate = CompletableDeferred<Unit>().also { sink.gate = it }
        val written = async { writes.write(run("a", 1)) }
        eventually { sink.writes.size == 1 }
        written.isCompleted.assert().isFalse()
        gate.complete(Unit)
        written.await().done.assert().isEqualTo(1)
    }

    @Test
    fun `one refused record fails only its run, from that record`(): Unit = runBlocking {
        val writes = SinkWrites(sink, 8)
        sink.refusing = setOf("b@3")
        val gate = CompletableDeferred<Unit>().also { sink.gate = it }
        val first = async { writes.write(run("a", 1)) }
        eventually { sink.writes.size == 1 }
        val waiting = listOf(run("b", 2, 3, 4), run("c", 1)).map { async { writes.write(it) } }
        eventually { writes.waiting == 2 }
        sink.gate = null
        gate.complete(Unit)
        first.await().done.assert().isEqualTo(1)
        val (b, c) = waiting.awaitAll()
        // The batch failed: each run alone; b failed alone: record by record, so b@2 is written and b@3 fails.
        b.done.assert().isEqualTo(1)
        b.failure!!.message.assert().isEqualTo("b@3")
        c.done.assert().isEqualTo(1)
        c.failure.assert().isNull()
        sink.writes.drop(1).assert().containsExactly(
            listOf("b@2", "b@3", "b@4", "c@1"),
            listOf("b@2", "b@3", "b@4"),
            listOf("b@2"),
            listOf("b@3"),
            listOf("c@1"),
        )
    }

    @Test
    fun `a run of one record failing is its failure, written once`(): Unit = runBlocking {
        sink.refusing = setOf("a@1")
        val written = SinkWrites(sink, 8).write(run("a", 1))
        written.done.assert().isEqualTo(0)
        written.failure!!.message.assert().isEqualTo("a@1")
        sink.writes.size.assert().isEqualTo(1)
    }

    @Test
    fun `a batch failing for an unavailable sink fails every run of it, none written alone`(): Unit = runBlocking {
        val writes = SinkWrites(sink, 8)
        sink.failAll = StorageUnavailableException("down")
        val written = listOf(run("a", 1, 2), run("b", 1)).map { async { writes.write(it) } }.awaitAll()
        written.map { it.done }.assert().containsExactly(0, 0)
        written.forEach { it.failure.assert().isInstanceOf(StorageUnavailableException::class.java) }
        sink.writes.size.assert().isEqualTo(1)
    }

    @Test
    fun `a cancellation the sink met fails the runs, not the lanes waiting`(): Unit = runBlocking {
        sink.failAll = kotlinx.coroutines.CancellationException("the sink's own")
        val written = SinkWrites(sink, 8).write(run("a", 1))
        written.failure.assert().isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `a leader cancelled puts the runs it took back, and the next one writes them`(): Unit = runBlocking {
        val writes = SinkWrites(sink, 8)
        val gate = CompletableDeferred<Unit>().also { sink.gate = it }
        val leader = async { writes.write(run("a", 1)) }
        val waiting = async { writes.write(run("b", 1)) }
        eventually { sink.writes.size == 1 }
        sink.writes.single().assert().containsExactly("a@1", "b@1")
        sink.gate = null
        leader.cancel()
        waiting.await().done.assert().isEqualTo(1)
        sink.writes.last().assert().containsExactly("b@1")
        writes.waiting.assert().isEqualTo(0)
        gate.complete(Unit)
    }

    @Test
    fun `a leader cancelled while its batch is written run by run puts back only the runs not ended`(): Unit =
        runBlocking {
            // The three runs together fail; written alone, a's and b's succeed and c's waits for the gate.
            val gate = CompletableDeferred<Unit>()
            val writes = CopyOnWriteArrayList<List<String>>()
            val sink = Sink { records ->
                val names = records.map { "${it.ref.id}@${it.version}" }
                writes += names
                require(names.size != 3) { "refused together" }
                if (names == listOf("c@1") && !gate.isCompleted) gate.await()
            }
            val sinkWrites = SinkWrites(sink, 8)
            val leader = async { sinkWrites.write(run("a", 1)) }
            val b = async { sinkWrites.write(run("b", 1)) }
            val c = async { sinkWrites.write(run("c", 1)) }
            eventually { writes.lastOrNull() == listOf("c@1") }
            // Cancelled inside c's write alone, b's already ended: b is not the leader's own, and must not go back.
            leader.cancelAndJoin()
            b.await().done.assert().isEqualTo(1)
            gate.complete(Unit)
            withTimeout(5.seconds) { c.await() }.done.assert().isEqualTo(1)
            // b is not written again: the next leader writes c, and a write arriving afterwards is written too.
            writes.assert().containsExactly(
                listOf("a@1", "b@1", "c@1"),
                listOf("a@1"),
                listOf("b@1"),
                listOf("c@1"),
                listOf("c@1"),
            )
            withTimeout(5.seconds) { sinkWrites.write(run("d", 1)) }.done.assert().isEqualTo(1)
            writes.last().assert().containsExactly("d@1")
            sinkWrites.waiting.assert().isEqualTo(0)
        }

    @Test
    fun `a leader cancelled before its first batch leaves the queue, and the next run is written`(): Unit =
        runBlocking {
            val writes = SinkWrites(sink, 8)
            val leader = async(start = CoroutineStart.UNDISPATCHED) { writes.write(run("a", 1)) }
            writes.waiting.assert().isEqualTo(1)
            leader.cancelAndJoin()
            writes.waiting.assert().isEqualTo(0)
            withTimeout(5.seconds) { writes.write(run("b", 1)) }.done.assert().isEqualTo(1)
            sink.writes.assert().containsExactly(listOf("b@1"))
        }
}
