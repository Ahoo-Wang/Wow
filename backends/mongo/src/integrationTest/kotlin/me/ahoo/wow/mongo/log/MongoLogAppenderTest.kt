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

import com.mongodb.MongoBulkWriteException
import com.mongodb.MongoException
import com.mongodb.MongoSocketReadTimeoutException
import com.mongodb.ServerAddress
import com.mongodb.bulk.BulkWriteError
import com.mongodb.bulk.BulkWriteResult
import com.mongodb.bulk.WriteConcernError
import com.mongodb.client.model.Filters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.mongo.CountedMongo
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import org.bson.BsonDocument
import org.bson.Document
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A group commit that fails once part of it may have committed: the call returns an outcome for every stream, and what
 * it committed is answered committed (EventLog.appendAll).
 */
class MongoLogAppenderTest : MongoLogFixtures() {
    @Test
    fun `a group whose second collection's insert the server refuses keeps the first's commits, the rest unknown`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val refused = MongoException(QUOTA_EXCEEDED, "refused")
            var inserts = 0
            val client = faulty(many = { documents, insert ->
                if (documents.none { it.containsKey(MongoEventLog.GAP) } && ++inserts == 2) throw refused
                insert(documents)
            })
            val log = MongoEventLog(client, MongoLogFixture.DATABASE, context, 1).also { it.createSchema() }
            val order = stream(AggregateType(context, "order").ref("tenant", "a"))
            val item = stream(AggregateType(context, "item").ref("tenant", "b"))
            val outcomes = log.appendAll(listOf(order to null, item to null))
            outcomes[0].assert().isInstanceOf(AppendOutcome.Committed::class.java)
            (outcomes[1] as AppendOutcome.Unknown).cause.assert().isSameAs(refused)
            MongoLogFixture.log(1, context).read(order.ref).toList().size.assert().isEqualTo(1)
        }

    @Test
    fun `a stream appended alone after part of its group committed is unknown when the server refuses it`(): Unit =
        runBlocking {
            // The driver refuses the oversized document as it reaches it, so the streams around it are appended
            // again, each alone: the last one's insert is refused once the first one committed.
            val context = MongoLogFixture.newContext()
            val refused = MongoException(QUOTA_EXCEEDED, "refused")
            val client = faulty { document: Document, insert: () -> Unit ->
                if (!document.containsKey(MongoEventLog.GAP) && document.toJson().contains("after")) throw refused
                insert()
            }
            val log = MongoEventLog(client, MongoLogFixture.DATABASE, context, 1).also { it.createSchema() }
            val type = AggregateType(context, "order")
            val big = oversized(stream(type.ref("tenant", "big")))
            val outcomes = log.appendAll(
                listOf(
                    stream(type.ref("tenant", "before")) to null,
                    big to null,
                    stream(type.ref("tenant", "after")) to null,
                    stream(type.ref("tenant", "last")) to null,
                ),
            )
            outcomes[0].assert().isInstanceOf(AppendOutcome.Committed::class.java)
            outcomes[1].assert().isInstanceOf(AppendOutcome.Rejected::class.java)
            // The refused stream and the one after it, which is not tried, are unknown.
            (outcomes[2] as AppendOutcome.Unknown).cause.assert().isSameAs(refused)
            (outcomes[3] as AppendOutcome.Unknown).cause.assert().isSameAs(refused)
            MongoLogFixture.log(1, context).read(type.ref("tenant", "last")).toList().assert().isEmpty()
        }

    @Test
    fun `a stream appended alone that the server refuses while nothing of the group committed is thrown`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val refused = MongoException(QUOTA_EXCEEDED, "refused")
            val client = faulty { document: Document, insert: () -> Unit ->
                if (!document.containsKey(MongoEventLog.GAP) && document.toJson().contains("after")) throw refused
                insert()
            }
            val log = MongoEventLog(client, MongoLogFixture.DATABASE, context, 1).also { it.createSchema() }
            val type = AggregateType(context, "order")
            // The oversized stream is refused before anything is written, and the stream after it fails alone.
            val big = oversized(stream(type.ref("tenant", "big")))
            val streams = listOf(big to null, stream(type.ref("tenant", "after")) to null)
            assertThrownBy<MongoException> { runBlocking { log.appendAll(streams) } }.isSameAs(refused)
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
    fun `an append that reads its aggregate's latest stream reads the version key`(): Unit = runBlocking {
        // D271: the version key is a partial index; a lookup by the aggregate's key alone scanned the collection.
        // The profiler this reads runs on a shard's members, not on a mongos.
        assumeFalse(MongoLogFixture.sharded, "The profiler is not on a mongos.")
        val context = MongoLogFixture.newContext()
        val type = AggregateType(context, "order")
        MongoLogFixture.log(1, context).append(stream("a", 1, aggregate = type))
        // A log that has not seen the aggregate reads its latest stream before appending.
        val cold = MongoLogFixture.log(1, context)
        val database = MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE)
        val namespace = "${MongoLogFixture.DATABASE}.${collection(context, "order")}"
        database.runCommand(Document("profile", 2))
        try {
            cold.append(stream("a", 2, aggregate = type)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        } finally {
            database.runCommand(Document("profile", 0))
        }
        val reads = database.getCollection("system.profile")
            .find(Filters.and(Filters.eq("ns", namespace), Filters.eq("op", "query")))
            .map { it.getString("planSummary") }
            .toList()
        reads.assert().isNotEmpty()
        reads.forEach { it.assert().startsWith("IXSCAN") }
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
    fun `a group whose reply is lost is unknown for each stream, and the streams stay where they landed`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            var lose = true
            val client = faulty(many = { documents, insert ->
                insert(documents)
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
        val client = faulty(many = { documents, insert ->
            if (refuse) {
                refuse = false
                throw MongoException(QUOTA_EXCEEDED, "refused")
            }
            insert(documents)
        })
        val log = MongoEventLog(client, MongoLogFixture.DATABASE, context, 1).also { it.createSchema() }
        assertThrownBy<MongoException> { runBlocking { log.appendAll(group(context, "a", "b")) } }
        log.append(group(context, "c").single().first).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        tailed(log, 1).single().stream.ref.id.assert().isEqualTo("c")
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

    @Test
    fun `a group member the server refuses alone is unknown or rejected, the others commit`(): Unit = runBlocking {
        listOf(QUOTA_EXCEEDED to AppendOutcome.Unknown::class.java, TOO_LARGE to AppendOutcome.Rejected::class.java)
            .forEach { (code, expected) ->
                val context = MongoLogFixture.newContext()
                var faulted = false
                val client = faulty(many = { documents, insert ->
                    if (faulted) {
                        insert(documents)
                    } else {
                        // The group's ordered insert stops at its failure: the members after it are not written.
                        faulted = true
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
    fun `a group member whose version another node took fails alone, with one placeholder and no second append`(): Unit = runBlocking {
        // X-60: another node ran a command without the partition's lease, so the owner's next append of that
        // aggregate conflicts at the store, which costs that stream alone, never the group's others.
        val context = MongoLogFixture.newContext()
        val type = AggregateType(context, "order")
        val owner = MongoEventLog(CountedMongo.client, MongoLogFixture.DATABASE, context, 1)
            .also { it.createSchema() }
        val ids = listOf("a", "b", "c", "d")
        owner.appendAll(ids.map { stream(it, 1, aggregate = type) to null })
            .forEach { it.assert().isInstanceOf(AppendOutcome.Committed::class.java) }
        MongoLogFixture.log(1, context).append(stream("b", 2, aggregate = type))
            .assert().isInstanceOf(AppendOutcome.Committed::class.java)

        val (outcomes, sent) = CountedMongo.commandsOf {
            runBlocking { owner.appendAll(ids.map { stream(it, 2, aggregate = type) to null }) }
        }

        outcomes[1].assert().isEqualTo(AppendOutcome.Conflict(2))
        listOf(0, 2, 3).forEach { outcomes[it].assert().isInstanceOf(AppendOutcome.Committed::class.java) }
        // The group's ordered insert, which stops at the conflict, one insert of the two streams it did not reach,
        // and the conflicting stream's placeholder: no member was inserted again alone.
        sent.filter { it.name == "insert" }.map { it.collection }.assert()
            .containsExactly(collection(context, "order"), collection(context, "order"), collection(context, "order"))
        tailed(owner, 8).map { it.stream.ref.id to it.stream.version }.assert()
            .containsExactlyInAnyOrderElementsOf(ids.map { it to 1 } + ids.map { it to 2 })
    }

    @Test
    fun `an aggregate's next stream in a group is not tried once the one before it conflicted`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val type = AggregateType(context, "order")
        val owner = MongoEventLog(CountedMongo.client, MongoLogFixture.DATABASE, context, 1)
            .also { it.createSchema() }
        owner.appendAll(listOf(stream("hot", 1, aggregate = type) to null, stream("cold", 1, aggregate = type) to null))
        MongoLogFixture.log(1, context).append(stream("hot", 2, requestId = "elsewhere", aggregate = type))
            .assert().isInstanceOf(AppendOutcome.Committed::class.java)

        val (outcomes, sent) = CountedMongo.commandsOf {
            runBlocking {
                owner.appendAll(
                    listOf(
                        stream("hot", 2, aggregate = type) to null,
                        stream("hot", 3, aggregate = type) to null,
                        stream("cold", 2, aggregate = type) to null,
                    ),
                )
            }
        }

        outcomes[0].assert().isEqualTo(AppendOutcome.Conflict(2))
        outcomes[1].assert().isEqualTo(AppendOutcome.Conflict(2))
        outcomes[2].assert().isInstanceOf(AppendOutcome.Committed::class.java)
        // The group's ordered insert, which stops at the conflict, the other aggregate's stream it did not reach, then
        // one write of both placeholders; the conflicting aggregate's next stream was never inserted.
        sent.filter { it.name == "insert" }.size.assert().isEqualTo(3)
        owner.read(type.ref("tenant", "hot")).toList().map { it.requestId }.last().assert().isEqualTo("elsewhere")
        tailed(owner, 4).map { it.stream.ref.id to it.stream.version }.assert()
            .containsExactlyInAnyOrder("hot" to 1, "cold" to 1, "hot" to 2, "cold" to 2)
    }

    @Test
    fun `a group the store refuses nothing of is one insert, whatever its aggregates' chains`(): Unit = runBlocking {
        // X-68: a contended aggregate hands the queue a chain of consecutive versions, which goes with the others.
        val context = MongoLogFixture.newContext()
        val type = AggregateType(context, "order")
        val log = MongoEventLog(CountedMongo.client, MongoLogFixture.DATABASE, context, 1).also { it.createSchema() }
        log.appendAll(listOf("a", "b", "c").map { stream(it, 1, aggregate = type) to null })
            .forEach { it.assert().isInstanceOf(AppendOutcome.Committed::class.java) }
        val chains = listOf("a" to 2..5, "b" to 2..2, "c" to 2..4)
            .flatMap { (id, versions) -> versions.map { stream(id, it, aggregate = type) to null } }

        val (outcomes, sent) = CountedMongo.commandsOf { runBlocking { log.appendAll(chains) } }

        outcomes.forEach { it.assert().isInstanceOf(AppendOutcome.Committed::class.java) }
        sent.filter { it.name == "insert" }.assert()
            .containsExactly(CountedMongo.Sent("insert", collection(context, "order")))
        tailed(log, 11).map { it.stream.ref.id to it.stream.version }.assert()
            .containsExactlyElementsOf(
                listOf("a" to 1, "b" to 1, "c" to 1) + chains.map { (stream, _) -> stream.ref.id to stream.version },
            )
    }

    @Test
    fun `a conflict in a group of chains stops its own aggregate, and the others land with two more inserts`(): Unit =
        runBlocking {
            // X-68 with X-60: the group's ordered insert stops at the conflict; the streams it did not reach go on
            // with one unordered insert of each aggregate's next stream and one ordered insert of the later ones.
            val context = MongoLogFixture.newContext()
            val type = AggregateType(context, "order")
            val inserts = CopyOnWriteArrayList<List<String>>()
            val client = faulty(many = { documents, insert ->
                inserts += documents.map { if (it.containsKey(MongoEventLog.GAP)) "gap" else it.label() }
                insert(documents)
            })
            val owner = MongoEventLog(client, MongoLogFixture.DATABASE, context, 1).also { it.createSchema() }
            val ids = listOf("a", "b", "c", "d")
            owner.appendAll(ids.map { stream(it, 1, aggregate = type) to null })
                .forEach { it.assert().isInstanceOf(AppendOutcome.Committed::class.java) }
            MongoLogFixture.log(1, context).append(stream("b", 2, requestId = "elsewhere", aggregate = type))
                .assert().isInstanceOf(AppendOutcome.Committed::class.java)
            inserts.clear()
            val chains = ids.flatMap { id -> (2..4).map { stream(id, it, aggregate = type) to null } }

            val outcomes = owner.appendAll(chains)

            chains.map { it.first }.zip(outcomes).forEach { (stream, outcome) ->
                if (stream.ref.id == "b") {
                    outcome.assert().isEqualTo(AppendOutcome.Conflict(2))
                } else {
                    outcome.assert().isInstanceOf(AppendOutcome.Committed::class.java)
                }
            }
            // No stream was inserted alone, nor any twice: three inserts of streams, one of the placeholders.
            inserts.assert().containsExactly(
                chains.map { (stream, _) -> "${stream.ref.id}@${stream.version}" },
                listOf("c@2", "d@2"),
                listOf("c@3", "c@4", "d@3", "d@4"),
                listOf("gap", "gap", "gap"),
            )
            // Each of the group's sequences holds a stream or, for b's three, a placeholder: the log has no gap.
            val written = MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE)
                .getCollection(collection(context, "order"))
                .find(Filters.lte(MongoEventLog.SEQUENCE, MongoEventLog.BLOCK)).toList()
            written.map { it.getLong(MongoEventLog.SEQUENCE) }.assert()
                .containsExactlyInAnyOrderElementsOf((1L..ids.size + chains.size.toLong()).toList())
            written.filter { it.containsKey(MongoEventLog.GAP) }.map { it.getString(MongoEventLog.GAP) }.assert()
                .containsExactly(MongoEventLog.GAP_FAILED, MongoEventLog.GAP_FAILED, MongoEventLog.GAP_FAILED)
            tailed(owner, ids.size * 4 - 2).map { it.stream.ref.id to it.stream.version }.assert()
                .containsExactlyInAnyOrderElementsOf(
                    ids.map { it to 1 } + ("b" to 2) +
                        chains.filter { (stream, _) -> stream.ref.id != "b" }
                            .map { (stream, _) -> stream.ref.id to stream.version },
                )
        }

    @Test
    fun `placeholders of a group that cannot be written are left to the tail, which fills them`(): Unit = runBlocking {
        val failures: List<(List<Document>, (List<Document>) -> Unit) -> Unit> = listOf(
            // The server refuses the placeholders' write.
            { _, _ -> throw MongoException(QUOTA_EXCEEDED, "refused") },
            // One placeholder fails, the other lands.
            { documents, insert ->
                insert(documents.drop(1))
                throw bulk(documents, BulkWriteError(QUOTA_EXCEEDED, "failed", BsonDocument(), 0), null)
            },
            // Both land, but their write concern failed: each is filled again, a duplicate then.
            { documents, insert ->
                insert(documents)
                throw bulk(documents, null, WriteConcernError(WRITE_CONCERN_FAILED, "", "no majority", BsonDocument()))
            },
        )
        failures.forEach { failure ->
            val context = MongoLogFixture.newContext()
            val type = AggregateType(context, "order")
            var armed = false
            val client = faulty(many = { documents, insert ->
                if (armed && documents.all { it.containsKey(MongoEventLog.GAP) }) {
                    armed = false
                    failure(documents, insert)
                } else {
                    insert(documents)
                }
            })
            val owner = MongoEventLog(client, MongoLogFixture.DATABASE, context, 1).also { it.createSchema() }
            owner.appendAll(listOf(stream("hot", 1, aggregate = type) to null, stream("cold", 1, aggregate = type) to null))
            MongoLogFixture.log(1, context).append(stream("hot", 2, aggregate = type))
                .assert().isInstanceOf(AppendOutcome.Committed::class.java)
            armed = true

            // The conflict leaves its own sequence and its successor's without a stream: two placeholders, one write.
            val outcomes = owner.appendAll(
                listOf(
                    stream("hot", 2, aggregate = type) to null,
                    stream("hot", 3, aggregate = type) to null,
                    stream("cold", 2, aggregate = type) to null,
                ),
            )

            armed.assert().isFalse()
            outcomes[0].assert().isEqualTo(AppendOutcome.Conflict(2))
            outcomes[2].assert().isInstanceOf(AppendOutcome.Committed::class.java)
            tailed(owner, 4).map { it.stream.ref.id to it.stream.version }.assert()
                .containsExactlyInAnyOrder("hot" to 1, "cold" to 1, "hot" to 2, "cold" to 2)
        }
    }

    @Test
    fun `a group whose write concern failed is unknown for each stream`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        var faulted = false
        val client = faulty(many = { documents, insert ->
            insert(documents)
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
    fun `a group may hold several partitions, and an aggregate's streams only in consecutive versions`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val log = MongoLogFixture.log(4, context)
            val type = AggregateType(context, "order")
            val ids = generateSequence(1) { it + 1 }.map { "p-$it" }
            val (zero, one) = Partitioner.partitionOf(type.ref("tenant", "p-1"), 4).let { first ->
                "p-1" to ids.first { Partitioner.partitionOf(type.ref("tenant", it), 4) != first }
            }
            // Decision D275: the queue hands several partitions' groups to one call.
            log.appendsAcrossPartitions.assert().isTrue()
            log.appendAll(group(context, zero, one))
                .forEach { it.assert().isInstanceOf(AppendOutcome.Committed::class.java) }
            assertThrownBy<IllegalArgumentException> { runBlocking { log.appendAll(group(context, zero, zero)) } }
            // A group holds this log's context alone.
            val elsewhere = stream("x", 1, aggregate = AggregateType(MongoLogFixture.newContext(), "order"))
            assertThrownBy<IllegalArgumentException> {
                runBlocking { log.appendAll(group(context, zero) + (elsewhere to null)) }
            }
        }

    /** [stream] with an event too large for one MongoDB document: 17 MiB, beyond the 16 MiB limit. */
    private fun oversized(stream: EventStreamRecord): EventStreamRecord = stream.copyWith(
        events = listOf(EventRecord("x.aggregate.Imported", 1, """{"blob":"${"x".repeat(OVERSIZED_BYTES)}"}""")),
    )

    private fun bulk(documents: List<Document>, error: BulkWriteError?, concern: WriteConcernError?) =
        MongoBulkWriteException(
            BulkWriteResult.acknowledged(documents.size - listOfNotNull(error).size, 0, 0, 0, emptyList(), emptyList()),
            listOfNotNull(error),
            concern,
            ServerAddress(),
            emptySet(),
        )

    private fun Document.label() = "${getString(MongoEventLog.AGGREGATE_ID)}@${getInteger(MongoEventLog.VERSION)}"

    private companion object {
        /** A server error the driver does not take as a write of unknown outcome. */
        const val QUOTA_EXCEEDED = 12_501

        /** An event beyond the 16 MiB document limit. */
        const val OVERSIZED_BYTES = 17 * 1024 * 1024
        const val TOO_LARGE = 10_334
        const val WRITE_CONCERN_FAILED = 64
    }
}
