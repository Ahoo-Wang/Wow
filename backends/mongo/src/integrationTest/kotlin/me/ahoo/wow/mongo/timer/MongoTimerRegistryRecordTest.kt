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

package me.ahoo.wow.mongo.timer

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.model.Filters
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import com.mongodb.event.CommandSucceededEvent
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.tck.containers.WowContainers
import org.bson.Document
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * How a MongoDB timer store records a timer collection in `wow_timer_aggregate` (#6792): it reads the record first and
 * inserts it only when it is missing (#7069). `MongoTimerRegistryGrantTest` holds an account that may only read the
 * registry; these hold the two other answers of that read and insert.
 */
class MongoTimerRegistryRecordTest {
    private val context = MongoLogFixture.newContext("timer-record")
    private val registry = MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE).getCollection(REGISTRY)

    @Test
    fun `a collection whose record names another aggregate is refused`() {
        val store = store(MongoLogFixture.client)
        val name = store.collectionName(ORDER)
        registry.insertOne(Document("_id", name).append("context", context).append("aggregate", "other"))
        val ref = AggregateType(context, ORDER).ref("t", "o-1")
        assertThrownBy<IllegalStateException> { runBlocking { store.put(timer(ref)) } }
            .hasMessageContaining("takes the timer collection $name")
            .hasMessageContaining("the aggregate other of $context")
    }

    @Test
    fun `a collection whose record names another context is refused`() {
        val store = store(MongoLogFixture.client)
        val name = store.collectionName(ORDER)
        registry.insertOne(Document("_id", name).append("context", "elsewhere").append("aggregate", ORDER))
        val ref = AggregateType(context, ORDER).ref("t", "o-1")
        assertThrownBy<IllegalStateException> { runBlocking { store.put(timer(ref)) } }
            .hasMessageContaining("the aggregate $ORDER of elsewhere")
    }

    @Test
    fun `a record another node inserts between this store's read and its insert is the store's record`(): Unit =
        runBlocking {
            val name = store(MongoLogFixture.client).collectionName(ORDER)
            // Another node records the collection as soon as this store's read of the record has found nothing.
            val raced = RecordAfterRead(name)
            MongoClients.create(
                MongoClientSettings.builder()
                    .applyConnectionString(ConnectionString(WowContainers.mongoUrl))
                    .addCommandListener(raced)
                    .build(),
            ).use { client ->
                store(client).put(timer(AggregateType(context, ORDER).ref("t", "o-1"))).assert().isTrue()
            }
            raced.inserted.get().assert().describedAs("the other node's inserts").isEqualTo(1)
            registry.countDocuments(Filters.eq("_id", name)).assert().isEqualTo(1L)
        }

    /** Inserts [name]'s record through another client once the first read of it on the registry has answered. */
    private inner class RecordAfterRead(private val name: String) : CommandListener {
        val inserted = AtomicInteger()

        @Volatile
        private var read: Int? = null

        override fun commandStarted(event: CommandStartedEvent) {
            if (read == null && readsRecord(event)) {
                read = event.requestId
            }
        }

        private fun readsRecord(event: CommandStartedEvent): Boolean {
            val command = event.command
            val filter = command.getDocument("filter", null) ?: return false
            return command.getString("find", null)?.value == REGISTRY && filter["_id"]?.asString()?.value == name
        }

        override fun commandSucceeded(event: CommandSucceededEvent) {
            if (event.requestId == read && inserted.compareAndSet(0, 1)) {
                registry.insertOne(Document("_id", name).append("context", context).append("aggregate", ORDER))
            }
        }
    }

    /** A store with its schema made sure of: the registry's format claimed, as a node does before its first timer. */
    private fun store(client: MongoClient) =
        MongoTimerStore(client, MongoLogFixture.DATABASE, context, PARTITIONS).also { it.createSchema() }

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
        const val REGISTRY = "wow_timer_aggregate"
        const val ORDER = "order"
        const val PARTITIONS = 4
    }
}
