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
package me.ahoo.wow.tck.event

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.api.modeling.SpaceId
import me.ahoo.wow.api.modeling.SpaceIdCapable
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.tck.mock.MockAggregateChanged
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import java.util.*

const val DEFAULT_AGGREGATE_VERSION = 0
const val DEFAULT_EVENT_COUNT = 10

object MockDomainEventStreams {
    @JvmOverloads
    fun generateEventStream(
        aggregateId: AggregateId,
        aggregateVersion: Int = DEFAULT_AGGREGATE_VERSION,
        eventCount: Int = DEFAULT_EVENT_COUNT,
        ownerId: String = OwnerId.DEFAULT_OWNER_ID,
        spaceId: SpaceId = SpaceIdCapable.DEFAULT_SPACE_ID,
        createdEventSupplier: (AggregateId) -> Any = { _ -> MockAggregateCreated(generateGlobalId()) },
        changedEventSupplier: (AggregateId) -> Any = { _ -> MockAggregateChanged(generateGlobalId()) }
    ): DomainEventStream {
        val events: MutableList<Any> = LinkedList()
        var eventCounter = 0
        val created = createdEventSupplier(aggregateId)
        events.add(created)
        ++eventCounter
        while (eventCounter < eventCount) {
            val changed = changedEventSupplier(aggregateId)
            events.add(changed)
            eventCounter++
        }
        return events.toDomainEventStream(
            upstream = GivenInitializationCommand(
                aggregateId = aggregateId,
                ownerId = ownerId,
                spaceId = spaceId
            ),
            aggregateVersion = aggregateVersion,
        )
    }
}
