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
import com.mongodb.MongoException
import com.mongodb.client.ClientSession
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.TransactionBody
import com.mongodb.client.model.Filters
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.mongo.MongoNames
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.tck.containers.WowContainers
import me.ahoo.wow.tck.log.EventLogSpec
import org.bson.BsonDocument
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class MongoEventLogTest : EventLogSpec() {
    override fun createLog(context: String, partitions: Int): EventLog = MongoLogFixture.log(partitions, context)

    /** A decimal of 40 significant digits: MongoDB keeps at most 34 exactly. */
    override fun refused(stream: EventStreamRecord): EventStreamRecord = stream.copy(
        events = listOf(EventRecord("x.aggregate.Priced", 1, """{"amount":1.234567890123456789012345678901234567890}""")),
    )

    private fun collection(context: String, aggregate: String) = "wow_log__${context.replace('-', '_')}__$aggregate"

    @Test
    fun `creating the schema twice is harmless`() {
        val context = MongoLogFixture.newContext()
        MongoLogFixture.log(4, context, listOf("order"))
        MongoLogFixture.log(4, context, listOf("order")).partitions.assert().isEqualTo(4)
    }

    @Test
    fun `a log recorded in another format version is refused`() {
        val context = MongoLogFixture.newContext()
        MongoLogFixture.log(4, context)
        MongoLogFixture.bumpFormat(collection(context, "partition"))
        assertThrownBy<IllegalStateException> { MongoLogFixture.log(4, context) }
    }

    @Test
    fun `an aggregate collection recorded in another format version is refused`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        MongoLogFixture.log(4, context, listOf("order"))
        MongoLogFixture.bumpFormat(collection(context, "order"))
        assertThrownBy<IllegalStateException> { MongoLogFixture.log(4, context, listOf("order")) }
        val log = MongoLogFixture.log(4, context)
        assertThrownBy<IllegalStateException> {
            runBlocking { log.read(AggregateType(context, "order").ref("tenant", "a")).toList() }
        }
    }

    @Test
    fun `a log created by a build without format records is refused`() {
        val context = MongoLogFixture.newContext()
        MongoLogFixture.createUnrecorded(collection(context, "partition"))
        assertThrownBy<IllegalStateException> { MongoLogFixture.log(4, context) }
    }

    @Test
    fun `an aggregate named like the partition counters is refused`() {
        val context = MongoLogFixture.newContext()
        assertThrownBy<IllegalArgumentException> { MongoLogFixture.log(4, context, listOf("Partition")) }
    }

    @Test
    fun `two aggregates that take one collection name are refused`() {
        val context = MongoLogFixture.newContext()
        MongoLogFixture.log(4, context, listOf("order-item"))
        assertThrownBy<IllegalStateException> { MongoLogFixture.log(4, context, listOf("order_item")) }
    }

    @Test
    fun `a long aggregate name is cut and hashed as PostgreSQL's identifiers are`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val long = AggregateType(context, "A".repeat(80))
        val log = MongoLogFixture.log(1, context)
        log.append(stream("a", 1, aggregate = long)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        log.append(stream("a", 1, aggregate = long)).assert().isEqualTo(AppendOutcome.Conflict(1))
        val name = MongoNames.of("wow_log", context, long.name)
        name.length.assert().isEqualTo(MongoNames.MAX_LENGTH)
        name.assert().startsWith(collection(context, "a").take(MongoNames.MAX_LENGTH - 9))
        MongoNames.limited(collection(context, "a".repeat(80)) + "x")
            .assert().isNotEqualTo(MongoNames.limited(collection(context, "a".repeat(80)) + "y"))
        MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE).listCollectionNames().toList()
            .assert().contains(name)
    }

    @Test
    fun `a tail finds the aggregate collections another log created after it started`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val reader = MongoLogFixture.log(1, context, listOf("order"))
        val writer = MongoLogFixture.log(1, context)
        val order = AggregateType(context, "order")
        val cart = AggregateType(context, "cart")
        writer.append(stream("a", 1, aggregate = order))
        reader.tail(0).first().stream.ref.assert().isEqualTo(order.ref("tenant", "a"))
        writer.append(stream("b", 1, aggregate = cart))
        writer.append(stream("a", 2, aggregate = order))
        val entries = withTimeout(TAIL_TIMEOUT_MS) { reader.tail(0).take(3).toList() }
        entries.map { it.stream.ref.type }.assert().containsExactly(order, cart, order)
        reader.findByRequestId("tenant", entries[1].stream.requestId)?.ref.assert().isEqualTo(cart.ref("tenant", "b"))
    }

    @Test
    fun `a tail stops at a missing sequence rather than skip it`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val log = MongoLogFixture.log(1, context)
        (1..3).forEach { log.append(stream("a", it, aggregate = AggregateType(context, "order"))) }
        MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE).getCollection(collection(context, "order"))
            .deleteOne(Filters.eq("sequence", 2L))
        withTimeoutOrNull(TAIL_STALL_MS) { log.tail(0).take(2).toList() }.assert().isNull()
        withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).first() }.position.sequence.assert().isEqualTo(1L)
    }

    @Test
    fun `a payload decimal beyond 34 digits is refused at append, never rounded`(): Unit = runBlocking {
        val valid = stream("a", 1)
        val log = createLog(valid.ref.type.context, 1)
        val tooPrecise = valid.copy(
            events = listOf(EventRecord("x.aggregate.Priced", 1, """{"amount":1.2345678901234567890123456789012345}""")),
        )
        (log.append(tooPrecise) as AppendOutcome.Rejected).cause.assert().isInstanceOf(IllegalArgumentException::class.java)
        log.head(0).assert().isNull()
        log.read(tooPrecise.ref).toList().assert().isEmpty()
        // An integer of 35 digits has no exact Decimal128 of scale 0 either.
        val tooLarge = tooPrecise.copy(
            events = listOf(EventRecord("x.aggregate.Priced", 1, """{"amount":12345678901234567890123456789012345}""")),
        )
        log.append(tooLarge).assert().isInstanceOf(AppendOutcome.Rejected::class.java)
        log.append(valid).assert().isInstanceOf(AppendOutcome.Committed::class.java)
    }

    /** A client whose transactions run [transaction], given the commit they would make. */
    private fun clientWith(transaction: (() -> Any?) -> Any?): MongoClient {
        val real = MongoLogFixture.client
        return object : MongoClient by real {
            override fun startSession(): ClientSession = spyk(real.startSession()).also { session ->
                every { session.withTransaction(any<TransactionBody<Any>>(), any()) } answers {
                    transaction { callOriginal() }!!
                }
            }
        }
    }

    private fun twoInPartitionZero(context: String): List<EventStreamRecord> {
        val type = AggregateType(context, "order")
        return generateSequence(1) { it + 1 }.map { "g-$it" }
            .filter { Partitioner.partitionOf(type.ref("tenant", it), 4) == 0 }
            .take(2)
            .map { stream(it, 1, aggregate = type) }
            .toList()
    }

    @Test
    fun `a group whose commit is unknown is unknown for each stream, never appended again`(): Unit = runBlocking {
        // Finding A2-6: the group committed, its result unknown; appended again one by one, each met a conflict.
        val context = MongoLogFixture.newContext()
        val committing = clientWith { commit ->
            commit()
            throw MongoException("commit result unknown").apply {
                addLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)
            }
        }
        val log = MongoEventLog(committing, MongoLogFixture.DATABASE, context, 4).also { it.createSchema() }
        val streams = twoInPartitionZero(context)
        log.appendAll(streams.map { it to null }).forEach { it.assert().isInstanceOf(AppendOutcome.Unknown::class.java) }
        val committed = MongoLogFixture.log(4, context)
        committed.head(0).assert().isEqualTo(2L)
        streams.forEach { committed.read(it.ref).toList().single().version.assert().isEqualTo(1) }
    }

    @Test
    fun `a group aborted for sure falls back to one append per stream`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        var aborted = false
        val aborting = clientWith { commit ->
            if (aborted) {
                commit()
            } else {
                aborted = true
                throw MongoException("aborted").apply { addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL) }
            }
        }
        val log = MongoEventLog(aborting, MongoLogFixture.DATABASE, context, 4).also { it.createSchema() }
        log.appendAll(twoInPartitionZero(context).map { it to null })
            .forEach { it.assert().isInstanceOf(AppendOutcome.Committed::class.java) }
        log.head(0).assert().isEqualTo(2L)
    }

    @Test
    fun `reads outside an append read what a majority committed`(): Unit = runBlocking {
        // Finding A2-2: a tail reading `local` could deliver an entry a failover then rolls back.
        val reads = CopyOnWriteArrayList<BsonDocument>()
        val listener = object : CommandListener {
            override fun commandStarted(event: CommandStartedEvent) {
                val command = event.command.clone()
                if (event.commandName in setOf("find", "aggregate") && !command.containsKey("txnNumber")) {
                    reads += command
                }
            }
        }
        val settings = MongoClientSettings.builder()
            .applyConnectionString(ConnectionString(WowContainers.mongo.replicaSetUrl))
            .addCommandListener(listener)
            .build()
        MongoClients.create(settings).use { client ->
            val context = MongoLogFixture.newContext()
            val log = MongoEventLog(client, MongoLogFixture.DATABASE, context, 1).also { it.createSchema() }
            val appended = stream("a", 1, aggregate = AggregateType(context, "order"))
            log.append(appended)
            reads.clear()
            withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).first() }
            log.head(0)
            log.read(appended.ref).toList()
            log.readEntries(appended.ref).toList()
            log.positionOf(appended.ref, 1)
            log.findByRequestId(appended.ref.type, "tenant", appended.requestId)
            log.findByRequestId("tenant", appended.requestId)
            val logReads = reads.filter { (it["find"] ?: it["aggregate"])?.asString()?.value?.startsWith("wow_log__") == true }
            logReads.size.assert().isGreaterThanOrEqualTo(7)
            logReads.forEach { command ->
                command.getDocument("readConcern")?.getString("level")?.value.assert().isEqualTo("majority")
            }
        }
    }

    @Test
    fun `a different partition count is refused`() {
        val context = MongoLogFixture.newContext()
        MongoLogFixture.log(4, context)
        assertThrownBy<IllegalStateException> { MongoLogFixture.log(8, context) }
    }

    private companion object {
        const val TAIL_STALL_MS = 500L
    }
}
