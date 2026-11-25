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

package me.ahoo.wow.mongo.log

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClients
import com.mongodb.client.model.Filters
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import com.mongodb.event.CommandSucceededEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.mongo.MongoNames
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.ExecutionLease
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A MongoDB log retires its current block of a partition when the probe ([MongoEventLog.appendedElsewhere]) finds
 * another node's block past it (#7085): the remainder is closed, as on a hand-off, and this node's next append reserves
 * past the other node's block. Otherwise its own commits stay contiguous in its block, and a tail its node keeps busy
 * reads the other node's entry only once the block is used up.
 */
class MongoForeignBlockRetireTest : MongoLogFixtures() {
    @Test
    fun `another node's block past this node's block retires it, so this node's next append lands past it`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val order = AggregateType(context, "order")
            val owner = MongoLogFixture.log(1, context)
            val other = MongoLogFixture.log(1, context)
            val first = owner.append(stream("mine", 1, aggregate = order)) as AppendOutcome.Committed
            val foreign = foreignAppend(other, order, "theirs")
            foreign.position.sequence.assert().isGreaterThan(MongoEventLog.BLOCK)
            owner.appendedElsewhere(mapOf(0 to first.position.sequence)).assert().containsExactly(0)
            val next = owner.append(stream("mine", 2, aggregate = order)) as AppendOutcome.Committed
            next.position.sequence.assert().isGreaterThan(foreign.position.sequence)
            // The owner's tail reads the other node's entry at once, over the closed remainder, then its own.
            val read = withTimeout(TAIL_MS) { owner.tail(0, first.position.sequence).take(2).toList() }
            read.map { it.position.sequence }.assert()
                .containsExactly(foreign.position.sequence, next.position.sequence)
            closings(context).assert().isEqualTo(1L)
        }

    @Test
    fun `another node's block below this node's block retires nothing, which the tail reaches as it is`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val order = AggregateType(context, "order")
            val other = MongoLogFixture.log(1, context)
            val owner = MongoLogFixture.log(1, context)
            // The other node's block comes first; this node's, reserved after it, lies past it.
            val foreign = other.append(stream("theirs", 1, aggregate = order)) as AppendOutcome.Committed
            val first = owner.append(stream("mine", 1, aggregate = order)) as AppendOutcome.Committed
            first.position.sequence.assert().isGreaterThan(foreign.position.sequence)
            // Seen for the first time, below this node's block: named, and this node keeps its block.
            owner.appendedElsewhere(mapOf(0 to 0L)).assert().containsExactly(0)
            val next = owner.append(stream("mine", 2, aggregate = order)) as AppendOutcome.Committed
            next.position.sequence.assert().isEqualTo(first.position.sequence + 1)
            closings(context).assert().isZero()
        }

    @Test
    fun `this node's commit in flight in the block it retired is delivered before the other node's entry`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val order = AggregateType(context, "order")
            val gate = HeldInsert(HELD)
            MongoClients.create(
                MongoClientSettings.builder()
                    .applyConnectionString(ConnectionString(WowContainers.mongoUrl))
                    .addCommandListener(gate)
                    .build(),
            ).use { client ->
                val owner = MongoEventLog(client, MongoLogFixture.DATABASE, context, 1).also { it.createSchema() }
                val other = MongoLogFixture.log(1, context)
                val first = owner.append(stream("mine", 1, aggregate = order)) as AppendOutcome.Committed
                // Its sequence allocated from the owner's block, its insert held before it reaches the server.
                val held = async(Dispatchers.IO) { owner.append(stream(HELD, 1, aggregate = order)) }
                withTimeout(TAIL_MS) { gate.held.await() }
                val foreign = foreignAppend(other, order, "theirs")
                owner.appendedElsewhere(mapOf(0 to first.position.sequence)).assert().containsExactly(0)
                val next = owner.append(stream("mine", 2, aggregate = order)) as AppendOutcome.Committed
                val seen = Channel<LogEntry>(Channel.UNLIMITED)
                val tail = launch(Dispatchers.IO) { owner.tail(0, first.position.sequence).collect { seen.send(it) } }
                // A read of the tail made while the commit is in flight, with both later entries in the store.
                withTimeout(TAIL_MS) { gate.readWhile { tail.isActive }.await() }
                gate.release()
                (held.await() as AppendOutcome.Committed).position.sequence.assert()
                    .isEqualTo(first.position.sequence + 1)
                withTimeout(TAIL_MS) { List(3) { seen.receive().position.sequence } }.assert().containsExactly(
                    first.position.sequence + 1,
                    foreign.position.sequence,
                    next.position.sequence,
                )
                tail.cancel()
            }
        }

    @Test
    fun `a node appending past this node's block retires at most one of its blocks a call, however often it appends`():
        Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val order = AggregateType(context, "order")
        val owner = MongoLogFixture.log(1, context)
        val other = MongoLogFixture.log(1, context)
        var cursor = (owner.append(stream("mine", 1, aggregate = order)) as AppendOutcome.Committed).position.sequence
        repeat(APPENDS) { foreignAppend(other, order, "theirs-$it") }
        owner.appendedElsewhere(mapOf(0 to cursor)).assert().containsExactly(0)
        closings(context).assert().describedAs("after the first call").isEqualTo(1L)
        // Its block retired, this node has none until it appends: the other node's appends retire nothing more.
        repeat(APPENDS) { foreignAppend(other, order, "again-$it") }
        owner.appendedElsewhere(mapOf(0 to cursor)).assert().containsExactly(0)
        closings(context).assert().describedAs("with no block of this node's").isEqualTo(1L)
        // Its next appends reserve one block, which the next call retires once.
        repeat(APPENDS) {
            cursor = (owner.append(stream("mine", it + 2, aggregate = order)) as AppendOutcome.Committed)
                .position.sequence
        }
        repeat(APPENDS) { foreignAppend(other, order, "later-$it") }
        owner.appendedElsewhere(mapOf(0 to cursor)).assert().containsExactly(0)
        closings(context).assert().describedAs("after its next block").isEqualTo(2L)
    }

    /** An append of [log] without the partition's lease: exactly its own sequence, past every block reserved before. */
    private suspend fun foreignAppend(log: MongoEventLog, order: AggregateType, id: String): AppendOutcome.Committed =
        withContext(ExecutionLease.NOT_HELD) { log.append(stream(id, 1, aggregate = order)) as AppendOutcome.Committed }

    /** The closed remainders of blocks in [context]'s `order` collection. */
    private fun closings(context: String): Long = MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE)
        .getCollection(MongoNames.of(MongoEventLog.PREFIX, context, "order"))
        .countDocuments(Filters.eq(MongoEventLog.GAP, MongoEventLog.GAP_CLOSED))

    /**
     * Holds the insert of [aggregateId]'s stream before it is sent until [release], and tells when a read of the
     * partition (an aggregation, the tail's) answered after a point the test names.
     */
    private class HeldInsert(private val aggregateId: String) : CommandListener {
        val held = CompletableDeferred<Unit>()
        private val released = CountDownLatch(1)

        @Volatile
        private var watching: (() -> Boolean)? = null
        private val read = CompletableDeferred<Unit>()

        /** Completes once an aggregation answers while [alive], from now on. */
        fun readWhile(alive: () -> Boolean): CompletableDeferred<Unit> {
            watching = alive
            return read
        }

        fun release() = released.countDown()

        override fun commandStarted(event: CommandStartedEvent) {
            if (event.commandName != "insert") return
            val document = event.command.getArray("documents", null)?.firstOrNull()?.asDocument() ?: return
            if (document.getString(MongoEventLog.AGGREGATE_ID, null)?.value == aggregateId) {
                held.complete(Unit)
                released.await(HOLD_SECONDS, TimeUnit.SECONDS)
            }
        }

        override fun commandSucceeded(event: CommandSucceededEvent) {
            if (event.commandName == "aggregate" && watching?.invoke() == true) {
                read.complete(Unit)
            }
        }
    }

    private companion object {
        const val HELD = "held"
        const val APPENDS = 3
        const val TAIL_MS = 15_000L
        const val HOLD_SECONDS = 30L
    }
}
