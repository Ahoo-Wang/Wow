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

package me.ahoo.wow.event

import me.ahoo.test.asserts.assert
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.serialization.event.JsonDomainEvent
import me.ahoo.wow.serialization.toJsonNode
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

class DomainEventStreamTest {

    private val aggregateId = FIXTURE_NAMED_AGGREGATE.toNamedAggregate().aggregateId("stream-aggregate")

    @Test
    fun `simple stream derives stream fields from first event`() {
        val event = FixtureNamedEvent("created").toDomainEvent(
            id = "event-1",
            aggregateId = aggregateId,
            commandId = "command-1",
            ownerId = "owner-1",
            spaceId = "space-1",
            version = 3,
            createTime = 1000,
        )
        val stream = SimpleDomainEventStream(
            id = "stream-1",
            requestId = "request-1",
            header = DefaultHeader.empty().with("stream", "source"),
            body = listOf(event),
        )

        stream.aggregateId.assert().isEqualTo(aggregateId)
        stream.contextName.assert().isEqualTo(aggregateId.contextName)
        stream.aggregateName.assert().isEqualTo(aggregateId.aggregateName)
        stream.ownerId.assert().isEqualTo("owner-1")
        stream.spaceId.assert().isEqualTo("space-1")
        stream.commandId.assert().isEqualTo("command-1")
        stream.version.assert().isEqualTo(3)
        stream.createTime.assert().isEqualTo(1000)
        stream.size.assert().isEqualTo(1)
        stream.first().assert().isSameAs(event)
    }

    @Test
    fun `copy materializes independent headers for simple domain events`() {
        val eventHeader = DefaultHeader.empty().with("event", "original")
        val event = FixtureNamedEvent().toDomainEvent(
            aggregateId = aggregateId,
            commandId = "command-1",
            header = eventHeader,
        )
        val stream = SimpleDomainEventStream(
            requestId = "request-1",
            header = DefaultHeader.empty().with("stream", "original"),
            body = listOf(event),
        )

        val copied = stream.copy()
        copied.header["stream"] = "copied"
        copied.first().header["event"] = "copied"

        stream.header["stream"].assert().isEqualTo("original")
        stream.first().header["event"].assert().isEqualTo("original")
    }

    @Test
    fun `copy materializes independent headers for json domain events`() {
        val sourceEvent = FixtureNamedEvent().toDomainEvent(
            aggregateId = aggregateId,
            commandId = "command-1",
        )
        val jsonEvent = JsonDomainEvent(
            id = sourceEvent.id,
            header = DefaultHeader.empty().with("event", "json-original"),
            bodyType = FixtureNamedEvent::class.java.name,
            body = """{"value":"json"}""".toJsonNode<ObjectNode>(),
            aggregateId = sourceEvent.aggregateId,
            ownerId = sourceEvent.ownerId,
            spaceId = sourceEvent.spaceId,
            version = sourceEvent.version,
            sequence = sourceEvent.sequence,
            revision = sourceEvent.revision,
            commandId = sourceEvent.commandId,
            name = sourceEvent.name,
            isLast = sourceEvent.isLast,
            createTime = sourceEvent.createTime,
        )
        val stream = SimpleDomainEventStream(
            requestId = "request-1",
            body = listOf(jsonEvent),
        )

        val copiedEvent = stream.copy().first() as JsonDomainEvent
        copiedEvent.header["event"] = "json-copied"

        jsonEvent.header["event"].assert().isEqualTo("json-original")
    }

    @Test
    fun `ignoreSourcing only ignores initial error events marked as ignore sourcing`() {
        val normalInitial = SimpleDomainEventStream(
            requestId = "request-1",
            body = listOf(
                FixtureNamedEvent().toDomainEvent(
                    aggregateId = aggregateId,
                    commandId = "command-1",
                    version = 1,
                )
            ),
        )
        val ignoredInitial = SimpleDomainEventStream(
            requestId = "request-2",
            body = listOf(
                FixtureIgnoredErrorEvent("failed", "failed event").toDomainEvent(
                    aggregateId = aggregateId,
                    commandId = "command-2",
                    version = 1,
                )
            ),
        )
        val ignoredButNotInitial = SimpleDomainEventStream(
            requestId = "request-3",
            body = listOf(
                FixtureIgnoredErrorEvent("failed", "failed event").toDomainEvent(
                    aggregateId = aggregateId,
                    commandId = "command-3",
                    version = 2,
                )
            ),
        )

        normalInitial.ignoreSourcing().assert().isFalse()
        ignoredInitial.ignoreSourcing().assert().isTrue()
        ignoredButNotInitial.ignoreSourcing().assert().isFalse()
    }
}
