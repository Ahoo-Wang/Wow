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

package me.ahoo.wow.migrate

import me.ahoo.test.asserts.assert
import me.ahoo.wow.migrate.example.NineXExample
import org.junit.jupiter.api.Test

class MongoNineXEventSourceTest {
    private val order = NineXAggregate("example-service", "order")
    private val cart = NineXAggregate("example-service", "cart")
    private val database = NineXMongoStore.example()
    private val source = MongoNineXEventSource(database)

    @Test
    fun `the inventory holds what the 9x example wrote`() {
        val inventories = source.inventory().associateBy { it.aggregate }
        inventories.keys.assert().containsExactlyInAnyOrder(order, cart)
        inventories.getValue(order).also {
            it.streams.assert().isEqualTo(4)
            it.events.assert().containsEntry(NineXEventType("order_created", "0.0.1"), 1L)
            it.commands.assert().isEqualTo(
                mapOf("create_order" to 1L, "change_address" to 1L, "pay_order" to 1L, "ship_order" to 1L)
            )
            it.streamsWithoutCommand.assert().isEqualTo(0)
        }
        inventories.getValue(cart).commands.assert().containsEntry("add_cart_item", 2L)
        val inMemory = InMemoryNineXEventSource(NineXExample.streams()).inventory().associateBy { it.aggregate }
        inventories.getValue(order).streamEventTypes.assert().isEqualTo(inMemory.getValue(order).streamEventTypes)
        inventories.getValue(cart).streamEventTypes.assert().isEqualTo(inMemory.getValue(cart).streamEventTypes)
        MappingCheck(NineXExample.plan).check(inventories.values.toList()).passed.assert().isTrue()
    }

    @Test
    fun `the inventory counts streams by the set of event types they hold`() {
        val shipped = NineXExample.resource("order-stream-v4.json")
        val event = """{"id":"0VY3GwcK4OmV00R","name":"order_shipped","revision":"0.0.1","""
        val noted = """{"id":"noted","name":"order_noted","body":{}}"""
        val twice = shipped.replace("\"0VY3GwcK4OmV00Q\"", "\"twice-1\"").replace("0VY3GwaK4OmV00E", "twice-1")
            .replace(event, "$noted,$event")
        val reversed = shipped.replace("\"0VY3GwcK4OmV00Q\"", "\"twice-2\"").replace("0VY3GwaK4OmV00E", "twice-2")
            .replace("\"body\":{}}]", "\"body\":{}},$noted]")
        NineXMongoStore.insert(database, twice)
        NineXMongoStore.insert(database, reversed)
        val shape = setOf(
            NineXEventType("order_noted", NineXStream.DEFAULT_REVISION),
            NineXEventType("order_shipped", "0.0.1")
        )
        source.inventory().first { it.aggregate == order }.streamEventTypes.assert()
            .containsEntry(shape, 2L)
            .containsEntry(setOf(NineXEventType("order_shipped", "0.0.1")), 1L)
    }

    @Test
    fun `the inventory counts streams that record no command`() {
        val text = NineXExample.resource("order-stream-v1.json")
            .replace(",\"upstream_name\":\"create_order\"", "")
        val other = text.replace("0VY3GwaK4OmV00E", "other-order").replace("0VY3Gwb94OmV00H", "other-stream")
        NineXMongoStore.insert(database, other)
        source.inventory().first { it.aggregate == order }.streamsWithoutCommand.assert().isEqualTo(1)
    }

    @Test
    fun `instances, streams and commits read in their orders, exactly`() {
        val first = NineXExample.stream("order-stream-v1.json")
        NineXMongoStore.insert(
            database,
            NineXExample.resource("order-stream-v1.json")
                .replace(first.aggregateId.id, "z-order")
                .replace(first.id, "z-stream"),
        )
        val ids = source.aggregateIds(order, null, 1)
        ids.assert().containsExactly(first.aggregateId)
        source.aggregateIds(order, ids.last(), 10).assert().containsExactly(NineXAggregateId("z-order", "(0)"))
        source.aggregateIds(order, NineXAggregateId("z-order", "(0)"), 10).assert().isEmpty()

        val streams = source.streams(order, first.aggregateId, 2, 2)
        streams.map { it.version }.assert().containsExactly(2, 3)
        source.streams(order, first.aggregateId, 1, 1).single().assert().isEqualTo(first)

        val committed = source.committedSince(order, first.createTime, null, 2)
        committed.map { it.version }.assert().containsExactly(1, 1)
        val next = source.committedSince(order, first.createTime, committed.last().let {
            NineXPosition(it.createTime, it.id)
        }, 10)
        next.map { it.version }.assert().containsExactly(2, 3, 4)
        source.committedSince(order, Long.MAX_VALUE, null, 10).assert().isEmpty()
    }

    @Test
    fun `the tail's index is missing until created, once`() {
        source.missingCommitTimeIndexes(listOf(order, cart)).assert().isEqualTo(
            mapOf(
                "order_event_stream" to "db.getCollection(\"order_event_stream\").createIndex({ createTime: 1, _id: 1 })",
                "cart_event_stream" to "db.getCollection(\"cart_event_stream\").createIndex({ createTime: 1, _id: 1 })",
            ),
        )
        source.ensureCommitTimeIndex(order)
        source.ensureCommitTimeIndex(order)
        source.missingCommitTimeIndexes(listOf(order, cart)).keys.assert().containsExactly("cart_event_stream")
        database.getCollection("order_event_stream").listIndexes()
            .count { MongoNineXEventSource.isCommitTimeKey(it.get("key", org.bson.Document::class.java)) }
            .assert().isEqualTo(1)
    }
}
