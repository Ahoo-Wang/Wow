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
import me.ahoo.wow.serialization.event.JsonDomainEvent
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

internal class JsonSerializerPolymorphicTest {

    @Test
    fun `domain event deserializer should preserve unknown body type as JsonDomainEvent`() {
        val node = event().toJsonNode<ObjectNode>()
        node.put(MessageRecords.BODY_TYPE, "missing.event.Body")

        val decoded = node.toJsonString().toObject<DomainEvent<*>>()

        decoded.assert().isInstanceOf(JsonDomainEvent::class.java)
        val jsonDomainEvent = decoded as JsonDomainEvent
        jsonDomainEvent.bodyType.assert().isEqualTo("missing.event.Body")
        jsonDomainEvent.body["orderId"].asString().assert().isEqualTo("order-1")
    }

    @Test
    fun `event stream serializer should keep JsonDomainEvent body type when reserialized`() {
        val streamNode = SimpleDomainEventStream(
            id = "stream-1",
            requestId = "request-1",
            body = listOf(event()),
        ).toJsonNode<ObjectNode>()
        val eventNode = streamNode[MessageRecords.BODY][0] as ObjectNode
        eventNode.put(MessageRecords.BODY_TYPE, "missing.stream.Body")

        val decoded = streamNode.toJsonString().toObject<DomainEventStream>()
        val reserialized = decoded.toJsonNode<ObjectNode>()
        val reserializedEvent = reserialized[MessageRecords.BODY][0] as ObjectNode

        decoded.first().assert().isInstanceOf(JsonDomainEvent::class.java)
        reserializedEvent[MessageRecords.BODY_TYPE].asString().assert().isEqualTo("missing.stream.Body")
    }

    private fun event(): DomainEvent<OrderCreated> =
        SimpleDomainEvent(
            id = "event-1",
            header = DefaultHeader.empty(),
            body = OrderCreated("order-1"),
            aggregateId = MaterializedNamedAggregate("sales", "Order").aggregateId("order-1", "tenant-1"),
            ownerId = "owner-1",
            spaceId = "space-1",
            version = 1,
            revision = "1",
            commandId = "command-1",
            name = "OrderCreated",
            createTime = 2000,
        )

    private data class OrderCreated(val orderId: String)
}
