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
import com.mongodb.ServerAddress
import com.mongodb.MongoSocketReadTimeoutException
import com.mongodb.client.ClientSession
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.TransactionBody
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import io.mockk.every
import io.mockk.spyk
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
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.PartitionLineage
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.tck.containers.WowContainers
import me.ahoo.wow.tck.log.EventLogSpec
import org.bson.BsonDocument
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MongoEventLogTest : EventLogSpec() {
    override fun createLog(context: String, partitions: Int): EventLog = MongoLogFixture.log(partitions, context)

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
    fun `contended appends keep retrying write conflicts past the transaction timeout, which bounds each attempt`(): Unit =
        runBlocking {
            // Regression (B.G-4): the timeout bounded the driver's whole retry loop, so on a slow machine an append that
            // kept losing write conflicts on the partition's counter ended unknown (the lease spike's direct run, 255 of
            // 256). With a 600 ms timeout the old loop lost dozens here; each attempt is bounded now, the retries not.
            val context = MongoLogFixture.newContext("contended")
            val client = MongoLogFixture.client
            val log = MongoEventLog(client, MongoLogFixture.DATABASE, context, 1, transactionTimeout = 500.milliseconds)
            log.createSchema()
            val type = AggregateType(context, "account")
            val outcomes = (0 until CONTENDERS).map { index ->
                async(Dispatchers.Default) {
                    (1..CONTENDED_VERSIONS).map { version -> log.append(stream("a-$index", version, aggregate = type)) }
                }
            }.awaitAll().flatten()
            outcomes.filterNot { it is AppendOutcome.Committed }.assert().isEmpty()
            outcomes.size.assert().isEqualTo(CONTENDERS * CONTENDED_VERSIONS)
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

    /** A client whose transactions run [transaction], given the commit they would make. */
    /** The fixture's client, each transaction's commit run through [commit], which calls the real one or throws. */
    private fun clientWith(commit: (() -> Unit) -> Unit): MongoClient {
        val real = MongoLogFixture.client
        return object : MongoClient by real {
            override fun startSession(): ClientSession = spyk(real.startSession()).also { session ->
                every { session.commitTransaction() } answers { commit { callOriginal() } }
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
            // The commit's reply lost: the driver gives up on an unknown commit result.
            throw MongoSocketReadTimeoutException("commit result unknown", ServerAddress(), IOException("timed out"))
                .apply { addLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL) }
        }
        val log = MongoEventLog(committing, MongoLogFixture.DATABASE, context, 4).also { it.createSchema() }
        val streams = twoInPartitionZero(context)
        log.appendAll(streams.map { it to null }).forEach { it.assert().isInstanceOf(AppendOutcome.Unknown::class.java) }
        val committed = MongoLogFixture.log(4, context)
        committed.head(0).assert().isEqualTo(2L)
        streams.forEach { committed.read(it.ref).toList().single().version.assert().isEqualTo(1) }
    }

    @Test
    fun `an unknown commit result from a server that answered is retried, and the append committed once`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            var unknown = true
            val committing = clientWith { commit ->
                commit()
                if (unknown) {
                    unknown = false
                    throw MongoException("commit result unknown").apply {
                        addLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)
                    }
                }
            }
            val log = MongoEventLog(committing, MongoLogFixture.DATABASE, context, 1).also { it.createSchema() }
            val appended = stream("a", 1, aggregate = AggregateType(context, "order"))
            log.append(appended).assert().isInstanceOf(AppendOutcome.Committed::class.java)
            MongoLogFixture.log(1, context).read(appended.ref).toList().size.assert().isEqualTo(1)
        }

    @Test
    fun `a commit aborted after it applied is tried again, and its retry finds the request committed`(): Unit =
        runBlocking {
            // A transient error after the commit applied: the transaction is no longer open, nothing to abort.
            val context = MongoLogFixture.newContext()
            var transient = true
            val committing = clientWith { commit ->
                commit()
                if (transient) {
                    transient = false
                    throw MongoException("aborted").apply { addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL) }
                }
            }
            val log = MongoEventLog(committing, MongoLogFixture.DATABASE, context, 1).also { it.createSchema() }
            val appended = stream("a", 1, aggregate = AggregateType(context, "order"))
            log.append(appended).assert().isInstanceOf(AppendOutcome.AlreadyCommitted::class.java)
            MongoLogFixture.log(1, context).read(appended.ref).toList().size.assert().isEqualTo(1)
        }

    @Test
    fun `an abort that fails joins the failure that caused it, and the transaction is tried again`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val real = MongoLogFixture.client
            var aborted = false
            val client = object : MongoClient by real {
                override fun startSession(): ClientSession = spyk(real.startSession()).also { session ->
                    every { session.commitTransaction() } answers {
                        if (aborted) {
                            callOriginal()
                        } else {
                            throw MongoException("aborted").apply {
                                addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)
                            }
                        }
                    }
                    every { session.abortTransaction() } answers {
                        aborted = true
                        callOriginal()
                        throw MongoException("abort failed")
                    }
                }
            }
            val log = MongoEventLog(client, MongoLogFixture.DATABASE, context, 1).also { it.createSchema() }
            log.append(stream("a", 1, aggregate = AggregateType(context, "order")))
                .assert().isInstanceOf(AppendOutcome.Committed::class.java)
            aborted.assert().isTrue()
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
    fun `an append to a warm aggregate is one transaction of three statements`(): Unit = runBlocking {
        // B.D diagnostics, decision D11: read the version, bump the partition counter, insert; then the commit.
        val context = MongoLogFixture.newContext()
        val log = MongoEventLog(CountedMongo.client, MongoLogFixture.DATABASE, context, 1)
            .also { it.createSchema() }
        val type = AggregateType(context, "order")
        log.append(stream("a", 1, aggregate = type)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        val (outcome, sent) = CountedMongo.commandsOf { runBlocking { log.append(stream("a", 2, aggregate = type)) } }
        outcome.assert().isInstanceOf(AppendOutcome.Committed::class.java)
        sent.assert().containsExactly(
            CountedMongo.Sent("find", collection(context, "order")),
            CountedMongo.Sent("findAndModify", collection(context, "partition")),
            CountedMongo.Sent("insert", collection(context, "order")),
            CountedMongo.Sent("commitTransaction", null),
        )
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
        // Finding R2.2-2: the driver's size check escaped as an exception rather than a refusal.
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

    private companion object {
        const val CONTENDERS = 32
        const val CONTENDED_VERSIONS = 8
        const val TAIL_STALL_MS = 500L
        const val RACES = 5
        const val IDLE_POLLS = 13
        const val COMMITS = 5
        const val SETTLE_MS = 100L
        const val OVERSIZED_BYTES = 17 * 1024 * 1024
    }
}
