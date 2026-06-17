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

package me.ahoo.wow.serialization

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.SimpleDomainEvent
import me.ahoo.wow.event.SimpleDomainEventStream
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.serialization.event.DomainEventRecords
import me.ahoo.wow.serialization.event.EventTypeDescriptor
import me.ahoo.wow.serialization.event.EventTypeId
import me.ahoo.wow.serialization.event.EventTypeRegistry
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

internal class JsonSerializerEventTest {

    @Test
    fun `domain event serializer should write aggregate and stream metadata`() {
        val event = domainEvent(sequence = 1, isLast = true)

        val node = event.toJsonNode<ObjectNode>()

        node[MessageRecords.ID].asString().assert().isEqualTo("event-1")
        node[MessageRecords.CONTEXT_NAME].asString().assert().isEqualTo("sales")
        node[MessageRecords.AGGREGATE_NAME].asString().assert().isEqualTo("Order")
        node[MessageRecords.AGGREGATE_ID].asString().assert().isEqualTo("order-1")
        node[MessageRecords.COMMAND_ID].asString().assert().isEqualTo("command-1")
        node[MessageRecords.VERSION].asInt().assert().isEqualTo(5)
        node[DomainEventRecords.SEQUENCE].asInt().assert().isEqualTo(1)
        node[DomainEventRecords.IS_LAST].asBoolean().assert().isTrue()
        node[MessageRecords.BODY_TYPE].asString().assert().isEqualTo(OrderCreated::class.java.name)
    }

    @Test
    fun `domain event deserializer should rebuild known body types`() {
        val event = domainEvent(sequence = 1, isLast = true)

        val decoded = event.toJsonString().toObject<DomainEvent<*>>()

        decoded.assert().isEqualTo(event)
    }

    @Test
    fun `domain event deserializer should prefer derived event type over body type`() {
        val event = domainEvent(sequence = 1, isLast = true)
        val node = event.toJsonNode<ObjectNode>()
        val typeId = EventTypeId("sales", "Order", "OrderCreated")
        node.put(MessageRecords.BODY_TYPE, LegacyOrderCreated::class.java.name)
        EventTypeRegistry.register(
            EventTypeDescriptor(
                typeId = typeId,
                eventType = OrderCreated::class.java,
                revision = "1",
            )
        )

        try {
            val decoded = node.toJsonString().toObject<DomainEvent<*>>()

            decoded.body.assert().isInstanceOf(OrderCreated::class.java)
            (decoded.body as OrderCreated).orderId.assert().isEqualTo("order-1")
        } finally {
            EventTypeRegistry.unregister(typeId)
        }
    }

    @Test
    fun `domain event deserializer should fall back to body type when revision differs`() {
        val event = domainEvent(sequence = 1, isLast = true)
        val node = event.toJsonNode<ObjectNode>()
        val typeId = EventTypeId("sales", "Order", "OrderCreated")
        node.put(MessageRecords.BODY_TYPE, LegacyOrderCreated::class.java.name)
        EventTypeRegistry.register(
            EventTypeDescriptor(
                typeId = typeId,
                eventType = OrderCreated::class.java,
                revision = "2",
            )
        )

        try {
            val decoded = node.toJsonString().toObject<DomainEvent<*>>()

            decoded.body.assert().isInstanceOf(LegacyOrderCreated::class.java)
            (decoded.body as LegacyOrderCreated).orderId.assert().isEqualTo("order-1")
        } finally {
            EventTypeRegistry.unregister(typeId)
        }
    }

    @Test
    fun `event stream deserializer should derive event sequence and last flag from body order`() {
        val first = domainEvent(id = "event-1", sequence = 9, isLast = true)
        val second = domainEvent(id = "event-2", sequence = 9, isLast = true)
        val stream = SimpleDomainEventStream(
            id = "stream-1",
            requestId = "request-1",
            header = DefaultHeader.empty(),
            body = listOf(first, second),
        )

        val decoded = stream.toJsonString().toObject<DomainEventStream>()

        decoded.id.assert().isEqualTo("stream-1")
        decoded.requestId.assert().isEqualTo("request-1")
        decoded.body[0].sequence.assert().isEqualTo(1)
        decoded.body[0].isLast.assert().isFalse()
        decoded.body[1].sequence.assert().isEqualTo(2)
        decoded.body[1].isLast.assert().isTrue()
        decoded.body[1].aggregateId.assert().isEqualTo(stream.aggregateId)
        decoded.body[1].version.assert().isEqualTo(stream.version)
    }

    @Test
    fun `event stream serializer should not write type id field`() {
        val stream = SimpleDomainEventStream(
            id = "stream-1",
            requestId = "request-1",
            header = DefaultHeader.empty(),
            body = listOf(domainEvent(sequence = 1, isLast = true)),
        )

        val node = stream.toJsonNode<ObjectNode>()
        val eventNode = node[MessageRecords.BODY][0] as ObjectNode

        (eventNode["typeId"] == null).assert().isTrue()
        eventNode[MessageRecords.BODY_TYPE].asString().assert().isEqualTo(OrderCreated::class.java.name)
    }

    @Test
    fun `event stream deserializer should prefer derived event type over body type`() {
        val stream = SimpleDomainEventStream(
            id = "stream-1",
            requestId = "request-1",
            header = DefaultHeader.empty(),
            body = listOf(domainEvent(sequence = 1, isLast = true)),
        )
        val node = stream.toJsonNode<ObjectNode>()
        val eventNode = node[MessageRecords.BODY][0] as ObjectNode
        val typeId = EventTypeId("sales", "Order", "OrderCreated")
        eventNode.put(MessageRecords.BODY_TYPE, LegacyOrderCreated::class.java.name)
        EventTypeRegistry.register(
            EventTypeDescriptor(
                typeId = typeId,
                eventType = OrderCreated::class.java,
                revision = "1",
            )
        )

        try {
            val decoded = node.toJsonString().toObject<DomainEventStream>()

            decoded.first().body.assert().isInstanceOf(OrderCreated::class.java)
            (decoded.first().body as OrderCreated).orderId.assert().isEqualTo("order-1")
        } finally {
            EventTypeRegistry.unregister(typeId)
        }
    }

    private fun domainEvent(
        id: String = "event-1",
        sequence: Int,
        isLast: Boolean,
    ): DomainEvent<OrderCreated> {
        val aggregateId = MaterializedNamedAggregate("sales", "Order").aggregateId("order-1", "tenant-1")
        return SimpleDomainEvent(
            id = id,
            header = DefaultHeader.empty(),
            body = OrderCreated("order-1"),
            aggregateId = aggregateId,
            ownerId = "owner-1",
            spaceId = "space-1",
            version = 5,
            sequence = sequence,
            revision = "1",
            commandId = "command-1",
            name = "OrderCreated",
            isLast = isLast,
            createTime = 2000,
        )
    }

    private data class OrderCreated(val orderId: String)

    private data class LegacyOrderCreated(val orderId: String)
}
