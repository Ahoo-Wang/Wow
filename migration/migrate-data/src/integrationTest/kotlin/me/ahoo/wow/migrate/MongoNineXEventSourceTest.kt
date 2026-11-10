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
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.json.WowJson
import org.bson.Document
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
            it.commands.assert().isEqualTo(mapOf("create_order" to 1L, "change_address" to 1L, "pay_order" to 1L, "ship_order" to 1L))
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
        val shape = setOf(NineXEventType("order_noted", NineXStream.DEFAULT_REVISION), NineXEventType("order_shipped", "0.0.1"))
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
    fun `the inventory counts a stream whose command header is blank as recording no command`() {
        val text = NineXExample.resource("order-stream-v1.json")
        fun blank(command: String, id: String) = text.replace("\"upstream_name\":\"create_order\"", "\"upstream_name\":\"$command\"")
            .replace("0VY3GwaK4OmV00E", "$id-order").replace("0VY3Gwb94OmV00H", "$id-stream")
        NineXMongoStore.insert(database, blank("", "empty"))
        NineXMongoStore.insert(database, blank(" ", "blank"))
        source.inventory().first { it.aggregate == order }.run {
            commands.keys.assert().containsExactlyInAnyOrder("create_order", "change_address", "pay_order", "ship_order")
            commands.assert().containsEntry("create_order", 1L)
            streamsWithoutCommand.assert().isEqualTo(2)
        }
    }

    @Test
    fun `the inventory counts a stream that holds no body as one of no event types`() {
        // 9.x always writes a body; a store can still hold a document without one.
        @Suppress("UNCHECKED_CAST")
        val fields = WowJson.mapper.readValue(NineXExample.resource("order-stream-v1.json"), LinkedHashMap::class.java)
            as Map<String, Any?>
        val stream = Document(fields - "id" - "body")
        stream["_id"] = "bodiless"
        stream["aggregateId"] = "bodiless-order"
        database.getCollection("order${MongoNineXEventSource.SUFFIX}").insertOne(stream)
        source.inventory().first { it.aggregate == order }.run {
            streams.assert().isEqualTo(5)
            streamEventTypes.assert().containsEntry(emptySet(), 1L)
        }
    }

    @Test
    fun `the inventory should read the command of an older 9x's dotted header, or of command name alone`() {
        val text = NineXExample.resource("order-stream-v1.json")
        fun renamed(header: String, id: String) = text.replace("\"upstream_name\":", "\"$header\":")
            .replace("0VY3GwaK4OmV00E", "$id-order").replace("0VY3Gwb94OmV00H", "$id-stream")
        NineXMongoStore.insert(database, renamed(NineXHeaders.DOTTED_UPSTREAM_NAME, "dotted"))
        NineXMongoStore.insert(database, renamed(NineXHeaders.COMMAND_NAME, "named"))
        source.inventory().first { it.aggregate == order }.run {
            commands.assert().containsEntry("create_order", 3L)
            streamsWithoutCommand.assert().isEqualTo(0)
        }
        listOf("dotted", "named").forEach { id ->
            source.streams(order, NineXAggregateId("$id-order", "(0)"), 1, 1).single().commandName.assert()
                .isEqualTo("create_order")
        }
    }

    @Test
    fun `the inventory should list the instances whose id V10 refuses, an absent or null id as the empty one`() {
        NineXMongoStore.collidable(database, order.name)
        val text = NineXExample.resource("order-stream-v1.json")
        fun insert(stream: String, aggregateId: String?, tenantId: String = "(0)") = NineXMongoStore.insert(
            database,
            text.replace("\"0VY3Gwb94OmV00H\"", "\"$stream\"").replace("\"tenantId\":\"(0)\"", "\"tenantId\":\"$tenantId\"")
                .replace(
                    "\"aggregateId\":\"0VY3GwaK4OmV00E\",",
                    aggregateId?.let { "\"aggregateId\":${RefusedIds.quoted(it)}," }.orEmpty(),
                ),
        )
        insert("absent", null)
        NineXMongoStore.insert(database, text.replace("\"0VY3Gwb94OmV00H\"", "\"null\"").replace("\"0VY3GwaK4OmV00E\"", "null"))
        insert("empty", "")
        insert("empty-t2", "", tenantId = "t2")
        insert("blank", " ")
        insert("unassigned", AggregateRef.UNASSIGNED)
        source.inventory().first { it.aggregate == order }.refusedIds.assert().isEqualTo(
            mapOf(
                NineXAggregateId("", "(0)") to 3L,
                NineXAggregateId("", "t2") to 1L,
                NineXAggregateId(" ", "(0)") to 1L,
                NineXAggregateId(AggregateRef.UNASSIGNED, "(0)") to 1L,
            ),
        )
        // Each reads as the in-memory store reads it, the absent and null ids as the empty one.
        source.aggregateIds(order, null, 10).assert().contains(NineXAggregateId("", "(0)"), NineXAggregateId("", "t2"))
        source.streams(order, NineXAggregateId("", "t2"), 1, 10).single().id.assert().isEqualTo("empty-t2")
        source.committedSince(order, 0, null, 100).count { it.aggregateId.id.isEmpty() }.assert().isEqualTo(4)
    }

    @Test
    fun `the inventory should list a refused id that records no tenant under the empty tenant`() {
        val text = NineXExample.resource("order-stream-v1.json")
        NineXMongoStore.insert(
            database,
            text.replace("\"0VY3Gwb94OmV00H\"", "\"untenanted\"").replace("\"aggregateId\":\"0VY3GwaK4OmV00E\",", "")
                .replace("\"tenantId\":\"(0)\",", ""),
        )
        source.inventory().first { it.aggregate == order }.refusedIds.assert()
            .isEqualTo(mapOf(NineXAggregateId("", "") to 1L))
    }

    @Test
    fun `the inventory should count the streams that record no tenant, by their id`() {
        NineXMongoStore.collidable(database, order.name)
        val text = NineXExample.resource("order-stream-v1.json")
        fun insert(stream: String, id: String, tenant: String?) = NineXMongoStore.insert(
            database,
            text.replace("\"0VY3Gwb94OmV00H\"", "\"$stream\"").replace("\"0VY3GwaK4OmV00E\"", "\"$id\"")
                .replace("\"tenantId\":\"(0)\",", tenant?.let { "\"tenantId\":${RefusedIds.quoted(it)}," }.orEmpty()),
        )
        insert("absent", "o-1", null)
        insert("blank", "o-1", " ")
        insert("other", "o-2", "")
        source.inventory().first { it.aggregate == order }.tenantless.assert().isEqualTo(mapOf("o-1" to 2L, "o-2" to 1L))
        // Each reads as of the blank tenant, as the in-memory store reads it.
        source.aggregateIds(order, null, 10).filter { it.id == "o-2" }.assert().containsExactly(NineXAggregateId("o-2", ""))
        source.tenantsOf(order, "o-1").assert().containsExactly("", " ")
        // The walk reads them as streams does: no stream of the absent tenant, the blank ones by their own tenant.
        source.instances(order, null, 10, 100).filter { it.id.id.startsWith("o-") }
            .map { "${it.id}:${it.streams.map { stream -> stream.id }}" }.assert()
            .containsExactly("/o-1:[]", " /o-1:[blank]", "/o-2:[other]")
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
        val next = source.committedSince(order, first.createTime, committed.last().let { NineXPosition(it.createTime, it.id) }, 10)
        next.map { it.version }.assert().containsExactly(2, 3, 4)
        source.committedSince(order, Long.MAX_VALUE, null, 10).assert().isEmpty()

        // A tail pass ends at the latest stream committed when it starts, that stream included.
        val latest = source.lastCommitted(order)!!
        latest.assert().isEqualTo(next.last().let { NineXPosition(it.createTime, it.id) })
        source.committedSince(order, first.createTime, null, 10, until = latest).size.assert().isEqualTo(5)
        val third = next[1].let { NineXPosition(it.createTime, it.id) }
        source.committedSince(order, first.createTime, null, 10, until = third).map { it.version }.assert()
            .containsExactly(1, 1, 2, 3)
        source.lastCommitted(NineXAggregate("example-service", "nothing")).assert().isNull()
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
