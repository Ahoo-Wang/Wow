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
import com.mongodb.MongoBulkWriteException
import com.mongodb.MongoClientSettings
import com.mongodb.MongoException
import com.mongodb.MongoSocketReadTimeoutException
import com.mongodb.ReadConcern
import com.mongodb.ServerAddress
import com.mongodb.WriteConcern
import com.mongodb.bulk.BulkWriteError
import com.mongodb.bulk.BulkWriteResult
import com.mongodb.bulk.WriteConcernError
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.InsertManyOptions
import com.mongodb.client.model.Updates
import com.mongodb.client.result.InsertManyResult
import com.mongodb.client.result.InsertOneResult
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.mongo.CountedMongo
import me.ahoo.wow.mongo.MongoNames
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.PartitionLineage
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.tck.containers.WowContainers
import me.ahoo.wow.tck.log.EventLogSpec
import org.bson.BsonDocument
import org.bson.Document
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

class MongoEventLogTest : EventLogSpec() {
    override fun createLog(context: String, partitions: Int): EventLog = MongoLogFixture.log(partitions, context)

    override fun createSnapshots(context: String): SnapshotStore =
        MongoSnapshotStore(MongoLogFixture.client, MongoLogFixture.DATABASE, context).also { it.createSchema() }

    /** A decimal of 40 significant digits: MongoDB keeps at most 34 exactly. */
    override fun refused(stream: EventStreamRecord): EventStreamRecord = stream.copyWith(
        events = listOf(EventRecord("x.aggregate.Priced", 1, """{"amount":1.234567890123456789012345678901234567890}""")),
    )

    private fun collection(context: String, aggregate: String) = "wow_log__${context.replace('-', '_')}__$aggregate"

    @Test
    fun `a partition's stored lineage reads back, and a counter written without it reads as created`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val log = MongoLogFixture.log(2, context)
            val counters = MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE)
                .getCollection(MongoNames.of("wow_log", context, "partition"))
            counters.find(Filters.eq("_id", 0)).first()!!.let { counter ->
                counter.containsKey("parent").assert().isTrue()
                counter["parent"].assert().isNull()
                counter.getInteger("epoch").assert().isZero()
            }
            // What an online split of 10.x writes: partition 1 split from partition 0 (decision D240).
            counters.updateOne(Filters.eq("_id", 1), Updates.combine(Updates.set("parent", 0), Updates.set("epoch", 1)))
            log.lineage(0).assert().isEqualTo(PartitionLineage.ROOT)
            log.lineage(1).assert().isEqualTo(PartitionLineage.of(0, 1))
            // A counter of format 1 written before the lineage fields reads as a partition the log was created with.
            counters.updateOne(Filters.eq("_id", 1), Updates.combine(Updates.unset("parent"), Updates.unset("epoch")))
            log.lineage(1).assert().isEqualTo(PartitionLineage.ROOT)
            MongoLogFixture.log(2, context).lineage(1).assert().isEqualTo(PartitionLineage.ROOT)
            counters.deleteOne(Filters.eq("_id", 1))
            runCatching { log.lineage(1) }.exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
        }

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
        val tooPrecise = valid.copyWith(
            events = listOf(EventRecord("x.aggregate.Priced", 1, """{"amount":1.2345678901234567890123456789012345}""")),
        )
        (log.append(tooPrecise) as AppendOutcome.Rejected).cause.assert().isInstanceOf(IllegalArgumentException::class.java)
        log.head(0).assert().isNull()
        log.read(tooPrecise.ref).toList().assert().isEmpty()
        // An integer of 35 digits has no exact Decimal128 of scale 0 either.
        val tooLarge = tooPrecise.copyWith(
            events = listOf(EventRecord("x.aggregate.Priced", 1, """{"amount":12345678901234567890123456789012345}""")),
        )
        log.append(tooLarge).assert().isInstanceOf(AppendOutcome.Rejected::class.java)
        log.append(valid).assert().isInstanceOf(AppendOutcome.Committed::class.java)
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
    fun `nodes creating a new log at once all succeed`(): Unit = runBlocking {
        // Finding A2-8: the counters were inserted when none were found, and the later node hit a duplicate key.
        // A node that came second also used to find the first one's counters half written and refuse the log.
        repeat(RACES) {
            val context = MongoLogFixture.newContext()
            coroutineScope {
                (1..8).map { async(Dispatchers.IO) { MongoLogFixture.log(64, context).partitions } }.awaitAll()
            }.forEach { it.assert().isEqualTo(64) }
            MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE).getCollection(collection(context, "partition"))
                .countDocuments().assert().isEqualTo(64L)
            assertThrownBy<IllegalStateException> { MongoLogFixture.log(32, context) }
            MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE).getCollection(collection(context, "partition"))
                .countDocuments().assert().isEqualTo(64L)
        }
    }

    @Test
    fun `a different partition count is refused`() {
        val context = MongoLogFixture.newContext()
        MongoLogFixture.log(4, context)
        assertThrownBy<IllegalStateException> { MongoLogFixture.log(8, context) }
    }

    @Test
    fun `an append to a warm aggregate is one insert`(): Unit = runBlocking {
        // Decision D271: the sequence comes from the node's block, the previous one from the log's memory.
        val context = MongoLogFixture.newContext()
        val log = MongoEventLog(CountedMongo.client, MongoLogFixture.DATABASE, context, 1)
            .also { it.createSchema() }
        val type = AggregateType(context, "order")
        log.append(stream("a", 1, aggregate = type)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        val (outcome, sent) = CountedMongo.commandsOf { runBlocking { log.append(stream("a", 2, aggregate = type)) } }
        outcome.assert().isInstanceOf(AppendOutcome.Committed::class.java)
        sent.assert().containsExactly(CountedMongo.Sent("insert", collection(context, "order")))
    }

    @Test
    fun `a commit of this log costs its idle tail one read`(): Unit = runBlocking {
        // B.D diagnostics: the poll a commit wakes finds the entry and keeps the backoff, without a run of short polls.
        val context = MongoLogFixture.newContext()
        val log = MongoEventLog(
            CountedMongo.client,
            MongoLogFixture.DATABASE,
            context,
            1,
            pollInterval = 1.milliseconds,
            maxPollInterval = 1.hours,
        ).also { it.createSchema() }
        val type = AggregateType(context, "order")
        log.append(stream("a", 1, aggregate = type))
        val counters = collection(context, "partition")
        fun reads(mark: Int) = CountedMongo.since(mark).count { it.name == "aggregate" && it.collection == counters }
        val start = CountedMongo.begin()
        val received = Channel<LogEntry>(Channel.UNLIMITED)
        val tail = launch(Dispatchers.Default) { log.tail(0).collect { received.send(it) } }
        try {
            withTimeout(TAIL_TIMEOUT_MS) { received.receive() }
            // Twelve empty polls back the tail off to seconds between polls.
            withTimeout(TAIL_TIMEOUT_MS) {
                while (reads(start) < IDLE_POLLS) {
                    delay(10)
                }
            }
            val mark = CountedMongo.begin()
            (2..COMMITS + 1).forEach { version ->
                log.append(stream("a", version, aggregate = type))
                withTimeout(TAIL_TIMEOUT_MS) { received.receive() }.stream.version.assert().isEqualTo(version)
            }
            delay(SETTLE_MS)
            reads(mark).assert().isEqualTo(COMMITS)
        } finally {
            tail.cancel()
        }
    }

    /** [stream] with an event too large for one MongoDB document: 17 MiB, beyond the 16 MiB limit. */
    private fun oversized(stream: EventStreamRecord): EventStreamRecord = stream.copyWith(
        events = listOf(EventRecord("x.aggregate.Imported", 1, """{"blob":"${"x".repeat(OVERSIZED_BYTES)}"}""")),
    )

    @Test
    fun `a stream beyond the document size limit is rejected, and in appendAll fails alone`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val log = MongoLogFixture.log(1, context)
        val type = AggregateType(context, "order")
        val big = oversized(stream("big", 1, aggregate = type))
        // Finding R2.2-2: the driver's size check escaped as an exception rather than a refusal. Its sequence holds a
        // placeholder, which is no stream (decision D271).
        log.append(big).assert().isInstanceOf(AppendOutcome.Rejected::class.java)
        log.head(0).assert().isNull()
        val outcomes = log.appendAll(
            listOf(stream("before", 1, aggregate = type) to null, big to null, stream("after", 1, aggregate = type) to null),
        )
        outcomes[0].assert().isInstanceOf(AppendOutcome.Committed::class.java)
        outcomes[1].assert().isInstanceOf(AppendOutcome.Rejected::class.java)
        outcomes[2].assert().isInstanceOf(AppendOutcome.Committed::class.java)
        log.read(big.ref).toList().assert().isEmpty()
        withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).take(2).toList() }.map { it.stream.ref.id }
            .assert().containsExactlyInAnyOrder("before", "after")
    }

    /** The streams [log] tails from the start of partition 0 within the stall grace and a margin, by sequence. */
    private suspend fun tailed(log: EventLog, count: Int): List<LogEntry> =
        withTimeout(TAIL_TIMEOUT_MS + MongoEventLog.STALL_GRACE.inWholeMilliseconds * 2) {
            log.tail(0).take(count).toList()
        }

    private fun counter(context: String) =
        MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE).getCollection(collection(context, "partition"))
            .find(Filters.eq("_id", 0)).first()!!

    @Test
    fun `a conflicting append leaves a placeholder the tail steps over and no read by aggregate sees`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val log = MongoLogFixture.log(1, context)
            val type = AggregateType(context, "order")
            log.append(stream("a", 1, aggregate = type)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
            log.append(stream("a", 1, aggregate = type)).assert().isInstanceOf(AppendOutcome.Conflict::class.java)
            val b = log.append(stream("b", 1, aggregate = type)) as AppendOutcome.Committed
            b.position.sequence.assert().isEqualTo(3L)
            tailed(log, 2).map { it.position.sequence }.assert().containsExactly(1L, 3L)
            log.head(0).assert().isEqualTo(3L)
            log.read(type.ref("tenant", "a")).toList().size.assert().isEqualTo(1)
            log.untagged(type, null, 10).assert().containsExactly(type.ref("tenant", "a"), type.ref("tenant", "b"))
        }

    @Test
    fun `a node's unused remainder is closed when another node's block lies past it`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val first = MongoLogFixture.log(1, context)
        val second = MongoLogFixture.log(1, context)
        val type = AggregateType(context, "order")
        first.append(stream("a", 1, aggregate = type))
        (second.append(stream("b", 1, aggregate = type)) as AppendOutcome.Committed).position.sequence
            .assert().isEqualTo(MongoEventLog.BLOCK + 1)
        // The first node's tail waits at 2, its own sequence not allocated, then closes its block there.
        tailed(first, 2).map { it.stream.ref.id }.assert().containsExactly("a", "b")
        (first.append(stream("c", 1, aggregate = type)) as AppendOutcome.Committed).position.sequence
            .assert().isGreaterThan(MongoEventLog.BLOCK * 2)
        counter(context).getList("blocks", org.bson.Document::class.java).map { it.getLong("from") }
            .assert().doesNotContain(1L)
    }

    @Test
    fun `a stopped node's block is sealed, and its late write takes a new block`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val stopped = MongoLogFixture.log(1, context)
        val owner = MongoLogFixture.log(1, context)
        val type = AggregateType(context, "order")
        stopped.append(stream("a", 1, aggregate = type))
        owner.append(stream("b", 1, aggregate = type))
        // The owner's tail waits at 2, in the stopped node's block, then seals the rest of it with placeholders.
        tailed(owner, 2).map { it.stream.ref.id }.assert().containsExactly("a", "b")
        // The node was not stopped after all: its next sequence is taken, so it appends from a new block.
        val late = stopped.append(stream("c", 1, aggregate = type)) as AppendOutcome.Committed
        late.position.sequence.assert().isGreaterThan(MongoEventLog.BLOCK * 2)
        tailed(owner, 3).map { it.stream.ref.id }.assert().containsExactly("a", "b", "c")
    }

    @Test
    fun `an aggregate's next stream lands after its previous one, whichever node wrote that`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val first = MongoLogFixture.log(1, context)
        val second = MongoLogFixture.log(1, context)
        val type = AggregateType(context, "order")
        first.append(stream("x", 1, aggregate = type))
        val v2 = second.append(stream("a", 1, aggregate = type)).let { second.append(stream("a", 2, aggregate = type)) }
        val v3 = first.append(stream("a", 3, aggregate = type))
        ((v3 as AppendOutcome.Committed).position.sequence > (v2 as AppendOutcome.Committed).position.sequence)
            .assert().isTrue()
        val versions = withTimeout(TAIL_TIMEOUT_MS + MongoEventLog.STALL_GRACE.inWholeMilliseconds * 4) {
            first.tail(0).take(4).toList()
        }.filter { it.stream.ref.id == "a" }.map { it.stream.version }
        versions.assert().containsExactly(1, 2, 3)
    }

    @Test
    fun `an append to a collection its block does not list takes a new block`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val log = MongoLogFixture.log(1, context)
        val order = AggregateType(context, "order")
        val cart = AggregateType(context, "cart")
        log.append(stream("a", 1, aggregate = order))
        (log.append(stream("b", 1, aggregate = cart)) as AppendOutcome.Committed).position.sequence
            .assert().isEqualTo(MongoEventLog.BLOCK + 1)
        tailed(log, 2).map { it.stream.ref.type }.assert().containsExactly(order, cart)
    }

    /**
     * The fixture's client whose log collections run each insert through [insert], given the document and the real
     * insert to call or not: a fault injected where a stream or a placeholder is written.
     */
    private fun faulty(
        many: (List<Document>, () -> Unit) -> Unit = { _, insert -> insert() },
        insert: (Document, () -> Unit) -> Unit = { _, write -> write() },
    ): MongoClient {
        val real = MongoLogFixture.client
        return object : MongoClient by real {
            override fun getDatabase(databaseName: String): MongoDatabase =
                object : MongoDatabase by real.getDatabase(databaseName) {
                    override fun getCollection(collectionName: String): MongoCollection<Document> {
                        val collection = real.getDatabase(databaseName).getCollection(collectionName)
                        val aggregate =
                            collectionName.startsWith("wow_log__") && !collectionName.endsWith("__partition")
                        return if (aggregate) Faulty(collection, insert, many) else collection
                    }
                }
        }
    }

    private class Faulty(
        private val real: MongoCollection<Document>,
        private val insert: (Document, () -> Unit) -> Unit,
        private val many: (List<Document>, () -> Unit) -> Unit,
    ) : MongoCollection<Document> by real {
        override fun withWriteConcern(writeConcern: WriteConcern) =
            Faulty(real.withWriteConcern(writeConcern), insert, many)

        override fun withReadConcern(readConcern: ReadConcern) = Faulty(real.withReadConcern(readConcern), insert, many)

        override fun withTimeout(timeout: Long, timeUnit: TimeUnit) =
            Faulty(real.withTimeout(timeout, timeUnit), insert, many)

        override fun insertMany(documents: List<Document>, options: InsertManyOptions): InsertManyResult {
            var result: InsertManyResult? = null
            many(documents) { result = real.insertMany(documents, options) }
            return result ?: throw MongoSocketReadTimeoutException("no reply", ServerAddress(), IOException("timed out"))
        }

        override fun insertOne(document: Document): InsertOneResult {
            var result: InsertOneResult? = null
            insert(document) { result = real.insertOne(document) }
            return result ?: throw MongoSocketReadTimeoutException("no reply", ServerAddress(), IOException("timed out"))
        }
    }

    @Test
    fun `an insert whose reply is lost is unknown, and its stream stays where it landed`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        var lose = true
        val client = faulty { document: Document, insert: () -> Unit ->
            insert()
            if (lose && !document.containsKey(MongoEventLog.GAP)) {
                lose = false
                throw MongoSocketReadTimeoutException("reply lost", ServerAddress(), IOException("timed out"))
            }
        }
        val log = MongoEventLog(client, MongoLogFixture.DATABASE, context, 1).also { it.createSchema() }
        val type = AggregateType(context, "order")
        log.append(stream("a", 1, aggregate = type)).assert().isInstanceOf(AppendOutcome.Unknown::class.java)
        log.append(stream("b", 1, aggregate = type)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        tailed(log, 2).map { it.stream.ref.id }.assert().containsExactly("a", "b")
    }

    @Test
    fun `an insert that never left is unknown, and a placeholder takes its sequence`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        var drop = true
        val client = faulty { document: Document, insert: () -> Unit ->
            if (drop && !document.containsKey(MongoEventLog.GAP)) {
                drop = false
            } else {
                insert()
            }
        }
        val log = MongoEventLog(client, MongoLogFixture.DATABASE, context, 1).also { it.createSchema() }
        val type = AggregateType(context, "order")
        log.append(stream("a", 1, aggregate = type)).assert().isInstanceOf(AppendOutcome.Unknown::class.java)
        log.append(stream("b", 1, aggregate = type)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        tailed(log, 1).single().stream.ref.id.assert().isEqualTo("b")
        log.read(type.ref("tenant", "a")).toList().assert().isEmpty()
    }

    @Test
    fun `a placeholder that cannot be written is filled by the tail`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        var failures = 2
        val client = faulty { _: Document, insert: () -> Unit ->
            // The stream's insert and then its placeholder's are lost; the tail's fill goes through.
            if (failures > 0) failures-- else insert()
        }
        val log = MongoEventLog(client, MongoLogFixture.DATABASE, context, 1).also { it.createSchema() }
        val type = AggregateType(context, "order")
        log.append(stream("a", 1, aggregate = type)).assert().isInstanceOf(AppendOutcome.Unknown::class.java)
        log.append(stream("b", 1, aggregate = type)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        tailed(log, 1).single().stream.ref.id.assert().isEqualTo("b")
    }

    private fun group(context: String, vararg ids: String): List<Pair<EventStreamRecord, Nothing?>> =
        ids.map { stream(it, 1, aggregate = AggregateType(context, "order")) to null }

    @Test
    fun `a group whose reply is lost is unknown for each stream, and the streams stay where they landed`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            var lose = true
            val client = faulty(many = { _, insert ->
                insert()
                if (lose) {
                    lose = false
                    throw MongoSocketReadTimeoutException("reply lost", ServerAddress(), IOException("timed out"))
                }
            })
            val log = MongoEventLog(client, MongoLogFixture.DATABASE, context, 1).also { it.createSchema() }
            log.appendAll(group(context, "a", "b")).forEach { it.assert().isInstanceOf(AppendOutcome.Unknown::class.java) }
            log.append(group(context, "c").single().first).assert().isInstanceOf(AppendOutcome.Committed::class.java)
            tailed(log, 3).map { it.stream.ref.id }.assert().containsExactly("a", "b", "c")
        }

    @Test
    fun `a group the server refuses is thrown, and placeholders take its sequences`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        var refuse = true
        val client = faulty(many = { _, insert ->
            if (refuse) {
                refuse = false
                throw MongoException(QUOTA_EXCEEDED, "refused")
            }
            insert()
        })
        val log = MongoEventLog(client, MongoLogFixture.DATABASE, context, 1).also { it.createSchema() }
        assertThrownBy<MongoException> { runBlocking { log.appendAll(group(context, "a", "b")) } }
        log.append(group(context, "c").single().first).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        tailed(log, 1).single().stream.ref.id.assert().isEqualTo("c")
    }

    @Test
    fun `a group whose block another node sealed is appended again, each stream alone`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val stopped = MongoLogFixture.log(1, context)
        val owner = MongoLogFixture.log(1, context)
        stopped.append(group(context, "a").single().first)
        owner.append(group(context, "b").single().first)
        tailed(owner, 2).map { it.stream.ref.id }.assert().containsExactly("a", "b")
        stopped.appendAll(group(context, "c", "d")).forEach { outcome ->
            (outcome as AppendOutcome.Committed).position.sequence.assert().isGreaterThan(MongoEventLog.BLOCK * 2)
        }
        tailed(owner, 4).map { it.stream.ref.id }.assert().containsExactly("a", "b", "c", "d")
    }

    @Test
    fun `an insert the server refuses, or any failure, is thrown with its sequence filled`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val failures = ArrayDeque<RuntimeException>(
            listOf(MongoException(QUOTA_EXCEEDED, "refused"), IllegalStateException("broken")),
        )
        val client = faulty { document: Document, insert: () -> Unit ->
            if (!document.containsKey(MongoEventLog.GAP)) failures.removeFirstOrNull()?.let { throw it }
            insert()
        }
        val log = MongoEventLog(client, MongoLogFixture.DATABASE, context, 1).also { it.createSchema() }
        assertThrownBy<MongoException> { runBlocking { log.append(group(context, "a").single().first) } }
        assertThrownBy<IllegalStateException> { runBlocking { log.append(group(context, "b").single().first) } }
        log.append(group(context, "c").single().first).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        tailed(log, 1).single().stream.ref.id.assert().isEqualTo("c")
    }

    private fun bulk(documents: List<Document>, error: BulkWriteError?, concern: WriteConcernError?) =
        MongoBulkWriteException(
            BulkWriteResult.acknowledged(documents.size - listOfNotNull(error).size, 0, 0, 0, emptyList(), emptyList()),
            listOfNotNull(error),
            concern,
            ServerAddress(),
            emptySet(),
        )

    @Test
    fun `a group member the server refuses alone is unknown or rejected, the others commit`(): Unit = runBlocking {
        listOf(QUOTA_EXCEEDED to AppendOutcome.Unknown::class.java, TOO_LARGE to AppendOutcome.Rejected::class.java)
            .forEach { (code, expected) ->
                val context = MongoLogFixture.newContext()
                var faulted = false
                lateinit var real: MongoCollection<Document>
                val client = faulty(many = { documents, insert ->
                    if (faulted) {
                        insert()
                    } else {
                        faulted = true
                        real = MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE)
                            .getCollection(collection(context, "order"))
                        real.insertMany(documents.drop(1))
                        throw bulk(documents, BulkWriteError(code, "failed", BsonDocument(), 0), null)
                    }
                })
                val log = MongoEventLog(client, MongoLogFixture.DATABASE, context, 1).also { it.createSchema() }
                val outcomes = log.appendAll(group(context, "a", "b"))
                outcomes[0].assert().isInstanceOf(expected)
                outcomes[1].assert().isInstanceOf(AppendOutcome.Committed::class.java)
                tailed(log, 1).single().stream.ref.id.assert().isEqualTo("b")
            }
    }

    @Test
    fun `a group whose write concern failed is unknown for each stream`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        var faulted = false
        val client = faulty(many = { documents, insert ->
            insert()
            if (!faulted) {
                faulted = true
                throw bulk(documents, null, WriteConcernError(WRITE_CONCERN_FAILED, "", "no majority", BsonDocument()))
            }
        })
        val log = MongoEventLog(client, MongoLogFixture.DATABASE, context, 1).also { it.createSchema() }
        log.appendAll(group(context, "a", "b")).forEach { it.assert().isInstanceOf(AppendOutcome.Unknown::class.java) }
        tailed(log, 2).map { it.stream.ref.id }.assert().containsExactly("a", "b")
    }

    @Test
    fun `a group holds streams of one partition and distinct aggregates`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val log = MongoLogFixture.log(4, context)
        val type = AggregateType(context, "order")
        val ids = generateSequence(1) { it + 1 }.map { "p-$it" }
        val (zero, one) = Partitioner.partitionOf(type.ref("tenant", "p-1"), 4).let { first ->
            "p-1" to ids.first { Partitioner.partitionOf(type.ref("tenant", it), 4) != first }
        }
        assertThrownBy<IllegalArgumentException> { runBlocking { log.appendAll(group(context, zero, one)) } }
        assertThrownBy<IllegalArgumentException> { runBlocking { log.appendAll(group(context, zero, zero)) } }
    }

    private companion object {
        const val QUOTA_EXCEEDED = 12_501
        const val TOO_LARGE = 10_334
        const val WRITE_CONCERN_FAILED = 64
        const val TAIL_STALL_MS = 500L
        const val RACES = 5
        const val IDLE_POLLS = 13
        const val COMMITS = 5
        const val SETTLE_MS = 100L
        const val OVERSIZED_BYTES = 17 * 1024 * 1024
    }
}
