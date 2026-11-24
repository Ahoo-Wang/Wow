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

package me.ahoo.wow.mongo

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.mongodb.MongoCommandException
import com.mongodb.MongoSocketReadTimeoutException
import com.mongodb.ServerAddress
import com.mongodb.client.model.Filters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.mongo.log.MongoEventLogOptions
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.mongo.log.faulty
import me.ahoo.wow.mongo.timer.MongoTimerStore
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.timer.Timer
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonString
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.IOException

/**
 * The registries `wow_log_aggregate` and `wow_timer_aggregate` are data, written under every `ddl` (#6768): a tail,
 * `head` and the due read read the collections a registry lists, so a collection that exists without its record (a
 * database restored without the registries' documents, collections made by hand) is recorded at its first use.
 */
class MongoStoreRegistryTest {
    private val client = MongoLogFixture.client
    private val database = MongoLogFixture.DATABASE

    private fun log(context: String, ddl: Ddl, on: com.mongodb.client.MongoClient = client) =
        MongoEventLog(on, database, context, 1, options = MongoEventLogOptions(ddl = ddl)).also { it.createSchema() }

    /**
     * [context]'s log collections of `order` and `cart` made by `ddl: auto`, then `order`'s registry record deleted, as
     * a restore loses it.
     */
    private fun withoutItsRecord(context: String) {
        MongoLogFixture.log(1, context, listOf(ORDER, CART))
        client.getDatabase(database).getCollection(MongoEventLog.REGISTRY)
            .deleteOne(Filters.eq("_id", MongoNames.of("wow_log", context, ORDER)))
    }

    @Test
    fun `validate records a log collection without its record, and another node's tail reads its entries`(): Unit =
        tailedByAnotherNode(Ddl.VALIDATE)

    @Test
    fun `none records a log collection without its record, and another node's tail reads its entries`(): Unit =
        tailedByAnotherNode(Ddl.NONE)

    private fun tailedByAnotherNode(ddl: Ddl): Unit = runBlocking {
        val context = MongoLogFixture.newContext("registry")
        withoutItsRecord(context)
        log(context, ddl).append(stream(AggregateType(context, ORDER).ref("tenant", "a")))
            .assert().isInstanceOf(AppendOutcome.Committed::class.java)

        // Another node, or the writer after a restart.
        val reader = log(context, ddl)

        withTimeout(WAIT) { reader.tail(0).first() }.stream.ref.id.assert().isEqualTo("a")
        reader.head(0).assert().isEqualTo(1L)
    }

    @Test
    fun `validate records a timer collection without its record, and its timers are due`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext("timer-registry")
        val order = AggregateType(context, ORDER)
        val auto = MongoTimerStore(client, database, context, PARTITIONS).also { it.createSchema() }
        auto.put(timer(order.ref("t", "o-1"))).assert().isTrue()
        client.getDatabase(database).getCollection(TIMERS)
            .deleteOne(Filters.eq("_id", auto.collectionName(ORDER)))
        val validating = MongoTimerStore(client, database, context, PARTITIONS, ddl = Ddl.VALIDATE)
            .also { it.createSchema() }
        validating.put(timer(order.ref("t", "o-2"))).assert().isTrue()

        validating.due((0 until PARTITIONS).toSet(), Long.MAX_VALUE, 10).map { it.owner }
            .assert().containsExactlyInAnyOrder(order.ref("t", "o-1"), order.ref("t", "o-2"))
    }

    @Test
    fun `none records a timer collection that does not exist yet, and its timers are due`(): Unit = runBlocking {
        // MongoDB creates the collection at the first write and reads a missing one as empty: it is recorded at once.
        val context = MongoLogFixture.newContext("timer-registry")
        val store = MongoTimerStore(client, database, context, PARTITIONS, ddl = Ddl.NONE).also { it.createSchema() }
        val ref = AggregateType(context, ORDER).ref("t", "o-1")
        store.put(timer(ref)).assert().isTrue()

        store.due((0 until PARTITIONS).toSet(), Long.MAX_VALUE, 10).map { it.owner }.assert().containsExactly(ref)
    }

    @Test
    fun `a record the store cannot write refuses the first use, naming the document to insert`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext("registry")
        withoutItsRecord(context)
        val refusing = faulty(record = { throw unauthorized() })
        val validating = log(context, Ddl.VALIDATE, refusing)
        assertThrownBy<IllegalStateException> { validating.createCollection(ORDER) }
            .hasMessageContaining("The registry wow_log_aggregate has no record of the log collection")
            .hasMessageContaining(
                "{_id: \"${MongoNames.of("wow_log", context, ORDER)}\", context: \"$context\", aggregate: \"$ORDER\"}"
            )

        val ref = AggregateType(context, ORDER).ref("t", "o-1")
        val auto = MongoTimerStore(client, database, context, PARTITIONS).also { it.createSchema() }
        auto.put(timer(ref)).assert().isTrue()
        client.getDatabase(database).getCollection(TIMERS).deleteOne(Filters.eq("_id", auto.collectionName(ORDER)))
        val timers = MongoTimerStore(refusing, database, context, PARTITIONS, ddl = Ddl.VALIDATE)
        assertThrownBy<IllegalStateException> { runBlocking { timers.put(timer(ref)) } }
            .hasMessageContaining("The registry wow_timer_aggregate has no record of the timer collection")
    }

    @Test
    fun `a record the store's connection fails to write is thrown as transient, to be retried`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext("registry")
        withoutItsRecord(context)
        val lost =
            faulty(record = { throw MongoSocketReadTimeoutException("no reply", ServerAddress(), IOException("lost")) })

        assertThrownBy<StorageUnavailableException> {
            runBlocking {
                log(
                    context,
                    Ddl.VALIDATE,
                    lost
                ).append(stream(AggregateType(context, ORDER).ref("tenant", "a")))
            }
        }

        val ref = AggregateType(context, ORDER).ref("t", "o-1")
        val auto = MongoTimerStore(client, database, context, PARTITIONS).also { it.createSchema() }
        auto.put(timer(ref)).assert().isTrue()
        client.getDatabase(database).getCollection(TIMERS).deleteOne(Filters.eq("_id", auto.collectionName(ORDER)))
        val timers = MongoTimerStore(lost, database, context, PARTITIONS, ddl = Ddl.VALIDATE)
        assertThrownBy<StorageUnavailableException> { runBlocking { timers.put(timer(ref)) } }
    }

    @Test
    fun `an open block naming a collection without its record is not sealed, and its tail warns until it is recorded`(): Unit =
        runBlocking {
            // A node of an earlier version appended `a` to `order` without recording it, and stopped; this node appends
            // to `cart`. Before, the seal of the stopped node's block failed the tail on the unknown collection. Sealed
            // anyway, its placeholder in `cart` at `a`'s sequence would let a tail that does not read `order` step over
            // `a`: the tail waits instead.
            val context = MongoLogFixture.newContext("registry")
            withoutItsRecord(context)
            val earlier = log(context, Ddl.VALIDATE)
            earlier.append(stream(AggregateType(context, ORDER).ref("tenant", "a")))
            client.getDatabase(database).getCollection(MongoEventLog.REGISTRY)
                .deleteOne(Filters.eq("_id", MongoNames.of("wow_log", context, ORDER)))
            val node = log(context, Ddl.VALIDATE)
            node.append(stream(AggregateType(context, CART).ref("tenant", "b")))
            val logger = LoggerFactory.getLogger(MongoEventLog::class.java) as Logger
            val warnings = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(warnings)
            try {
                // The tail does not fail: it waits, and says which collection to record.
                withTimeoutOrNull(STALL) { node.tail(0).take(2).toList() }.assert().isNull()

                warnings.list.map { it.formattedMessage }.any {
                    "names the log collection ${MongoNames.of("wow_log", context, ORDER)}" in it
                }.assert().isTrue()
            } finally {
                logger.detachAppender(warnings)
            }
            // Recorded, as a node of this version records it at its first use of `order`: the same tail reads it, then
            // seals the stopped node's block.
            log(context, Ddl.VALIDATE).createCollection(ORDER)
            withTimeout(
                WAIT
            ) { node.tail(0).take(2).toList() }.map { it.stream.ref.id }.assert().containsExactly("a", "b")
        }

    private fun unauthorized() = MongoCommandException(
        BsonDocument("ok", BsonInt32(0)).append("code", BsonInt32(UNAUTHORIZED))
            .append("codeName", BsonString("Unauthorized")).append("errmsg", BsonString("not authorized to insert")),
        ServerAddress(),
    )

    private fun stream(ref: AggregateRef) = EventStreamRecord(
        ref,
        1,
        "c-${ref.id}",
        "r-${ref.id}",
        "${ref.type}.Act",
        1,
        listOf(EventRecord("${ref.type}.Acted", 1, "{}")),
        me.ahoo.wow.api.aggregate.Scope.NONE,
    )

    private fun timer(ref: AggregateRef) = Timer(
        id = "$ref#k@1",
        owner = ref,
        key = "k",
        version = 1,
        partition = Partitioner.partitionOf(ref, PARTITIONS),
        rule = ScheduleRule.At(5),
        dueAt = 5,
        target = ref,
        commandType = "c.order.Cancel",
        body = "{}",
    )

    private companion object {
        const val ORDER = "order"
        const val CART = "cart"
        const val TIMERS = "wow_timer_aggregate"
        const val PARTITIONS = 4
        const val UNAUTHORIZED = 13
        const val WAIT = 15_000L
        const val STALL = 4_000L
    }
}
