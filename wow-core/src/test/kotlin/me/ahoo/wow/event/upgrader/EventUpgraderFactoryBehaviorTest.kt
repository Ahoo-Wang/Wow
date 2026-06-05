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
import me.ahoo.wow.api.annotation.ORDER_FIRST
import me.ahoo.wow.api.annotation.ORDER_LAST
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.event.FixtureNamedEvent
import me.ahoo.wow.event.annotation.eventMetadata
import me.ahoo.wow.event.toDomainEvent
import me.ahoo.wow.event.upgrader.DroppedEvent.toDroppedEventRecord
import me.ahoo.wow.event.upgrader.EventNamedAggregate.Companion.toEventNamedAggregate
import me.ahoo.wow.event.upgrader.MutableDomainEventRecord.Companion.toMutableDomainEventRecord
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.serialization.event.DomainEventRecord
import me.ahoo.wow.serialization.event.toDomainEventRecord
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObjectNode
import org.junit.jupiter.api.Test

class EventUpgraderFactoryBehaviorTest {

    @Test
    fun `get returns empty list when no upgrader is registered for event`() {
        val eventNamedAggregate = "event.unregistered"
            .toNamedAggregate()
            .toEventNamedAggregate("unregistered")

        EventUpgraderFactory.get(eventNamedAggregate).assert().isEmpty()
    }

    @Test
    fun `service loaded upgraders are sorted by order`() {
        val upgraders = EventUpgraderFactory.get(MockEventToDroppedUpgrader.EVENT_NAMED_AGGREGATE)

        upgraders.map { it::class.java }.assert().isEqualTo(
            listOf(
                MockEventChangeRevisionUpgrader::class.java,
                MockEventToDroppedUpgrader::class.java,
            )
        )
    }

    @Test
    fun `upgrade applies registered upgraders in order`() {
        val aggregateId = MockEventToDroppedUpgrader.EVENT_NAMED_AGGREGATE.aggregateId("upgrader-aggregate")
        val record = FixtureNamedEvent()
            .toDomainEvent(
                aggregateId = aggregateId,
                commandId = "command-1",
            )
            .toJsonString()
            .toObjectNode()
            .toDomainEventRecord()

        val upgraded = EventUpgraderFactory.upgrade(record)

        upgraded.revision.assert().isEqualTo(MockEventChangeRevisionUpgrader.REVISION)
        upgraded.name.assert().isEqualTo("dropped_event")
        upgraded.bodyType.assert().isEqualTo(DroppedEvent::class.java.name)
    }
}

@Order(ORDER_FIRST)
class MockEventChangeRevisionUpgrader : EventUpgrader {
    companion object {
        const val REVISION = "1.0.0"
    }

    override val eventNamedAggregate: EventNamedAggregate
        get() = MockEventToDroppedUpgrader.EVENT_NAMED_AGGREGATE

    override fun upgrade(domainEventRecord: DomainEventRecord): DomainEventRecord {
        val mutableDomainEventRecord = domainEventRecord.toMutableDomainEventRecord()
        mutableDomainEventRecord.revision = REVISION
        return mutableDomainEventRecord
    }
}

@Order(ORDER_LAST)
class MockEventToDroppedUpgrader : EventUpgrader {
    companion object {
        val EVENT_NAMED_AGGREGATE: EventNamedAggregate by lazy {
            val eventName = eventMetadata<FixtureNamedEvent>().name
            "event.fixture".toNamedAggregate().toEventNamedAggregate(eventName)
        }
    }

    override val eventNamedAggregate: EventNamedAggregate
        get() = EVENT_NAMED_AGGREGATE

    override fun upgrade(domainEventRecord: DomainEventRecord): DomainEventRecord {
        return domainEventRecord.toDroppedEventRecord()
    }
}
