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

package me.ahoo.wow.event.upgrader

import me.ahoo.test.asserts.assert
import me.ahoo.wow.event.FIXTURE_NAMED_AGGREGATE
import me.ahoo.wow.event.FixtureNamedEvent
import me.ahoo.wow.event.toDomainEvent
import me.ahoo.wow.event.upgrader.MutableDomainEventRecord.Companion.toMutableDomainEventRecord
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.serialization.event.StreamDomainEventRecord
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObjectNode
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

/**
 * An event read from a stored stream carries the stream's identity, not its own JSON: an upgrader that reads the
 * aggregate or the header of such an event, before or after making it mutable, must see the stream's.
 */
class StreamedEventRecordIdentityTest {
    private val aggregateId = FIXTURE_NAMED_AGGREGATE.toNamedAggregate().aggregateId("streamed", tenantId = "tenant-1")
    private val header = DefaultHeader.empty().with("source", "stream")

    private fun streamedRecord(): StreamDomainEventRecord {
        val eventNode = FixtureNamedEvent("v1")
            .toDomainEvent(aggregateId = aggregateId, commandId = "command-1")
            .toJsonString()
            .toObjectNode()
        return StreamDomainEventRecord(
            actual = eventNode,
            streamedAggregateId = aggregateId,
            version = 2,
            ownerId = "owner-1",
            spaceId = "space-1",
            streamedHeader = header,
            commandId = "command-1",
            sequence = 1,
            isLast = true,
            createTime = 100,
        )
    }

    @Test
    fun `a streamed event record reads the stream identity`() {
        val record = streamedRecord()

        record.contextName.assert().isEqualTo(aggregateId.contextName)
        record.aggregateName.assert().isEqualTo(aggregateId.aggregateName)
        record.aggregateId.assert().isEqualTo("streamed")
        record.tenantId.assert().isEqualTo("tenant-1")
        record.toAggregateId().assert().isEqualTo(aggregateId)
        record.toMessageHeader().assert().isSameAs(header)
    }

    @Test
    fun `a mutable copy keeps the stream identity and edits the event JSON in place`() {
        val mutable = streamedRecord().toMutableDomainEventRecord()

        mutable.contextName.assert().isEqualTo(aggregateId.contextName)
        mutable.aggregateName.assert().isEqualTo(aggregateId.aggregateName)
        mutable.aggregateId.assert().isEqualTo("streamed")
        mutable.tenantId.assert().isEqualTo("tenant-1")
        mutable.toAggregateId().assert().isEqualTo(aggregateId)
        mutable.toMessageHeader().assert().isSameAs(header)
        mutable.toMutableDomainEventRecord().assert().isSameAs(mutable)

        val upgradedBody = mutable.body.deepCopy() as ObjectNode
        upgradedBody.put("value", "v2")
        mutable.body = upgradedBody
        mutable.body["value"].asString().assert().isEqualTo("v2")
        mutable.actual["body"]["value"].asString().assert().isEqualTo("v2")
    }
}
