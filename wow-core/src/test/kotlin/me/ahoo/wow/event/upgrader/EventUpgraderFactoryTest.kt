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

import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.configuration.requiredNamedAggregate
import me.ahoo.wow.event.MockNamedEvent
import me.ahoo.wow.event.toDomainEvent
import me.ahoo.wow.event.upgrader.EventNamedAggregate.Companion.toEventNamedAggregate
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class EventUpgraderFactoryTest {
    @Test
    fun getWhenEmpty() {
        val eventNamedAggregate = requiredNamedAggregate<MockNamedEvent>()
            .toEventNamedAggregate("MockNamedEvent")
        assertThat(EventUpgraderFactory.get(eventNamedAggregate), empty())
    }

    @Test
    fun get() {
        assertThat(EventUpgraderFactory.get(MockEventToDroppedUpgrader.EVENT_NAMED_AGGREGATE), hasSize(2))
        assertThat(
            EventUpgraderFactory.get(MockEventToDroppedUpgrader.EVENT_NAMED_AGGREGATE).first(),
            instanceOf(MockEventChangeRevisionUpgrader::class.java),
        )
        assertThat(
            EventUpgraderFactory.get(MockEventToDroppedUpgrader.EVENT_NAMED_AGGREGATE).last(),
            instanceOf(MockEventToDroppedUpgrader::class.java),
        )
    }

    @Test
    fun asDomainEvent() {
        val aggregateId =
            MockEventToDroppedUpgrader.EVENT_NAMED_AGGREGATE.aggregateId(GlobalIdGenerator.generateAsString())
        val mockEventJson = MockNamedEvent()
            .toDomainEvent(
                aggregateId = aggregateId,
                commandId = GlobalIdGenerator.generateAsString(),
            )
            .toJsonString()
        val droppedEvent = mockEventJson.toObject<DomainEvent<Any>>()
        assertThat(droppedEvent.id, equalTo(droppedEvent.id))
        assertThat(droppedEvent.aggregateId, equalTo(aggregateId))
        assertThat(droppedEvent.revision, equalTo(MockEventChangeRevisionUpgrader.REVISION))
        assertThat(droppedEvent.body, instanceOf(DroppedEvent::class.java))

        val droppedEventJson = droppedEvent.toJsonString()
        val droppedEvent2 = droppedEventJson.toObject<DomainEvent<Any>>()
        assertThat(droppedEvent2.id, equalTo(droppedEvent.id))
        assertThat(droppedEvent2.aggregateId, equalTo(droppedEvent.aggregateId))
        assertThat(droppedEvent2.revision, equalTo(droppedEvent.revision))
        assertThat(droppedEvent2.body, instanceOf(DroppedEvent::class.java))
    }
}
