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
import me.ahoo.wow.api.event.DEFAULT_EVENT_SEQUENCE
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Test

class DomainEventStreamFactoryBehaviorTest {

    @Test
    fun `flatEvent normalizes iterables arrays and single values`() {
        listOf(1, 2).flatEvent().toList().assert().isEqualTo(listOf(1, 2))
        arrayOf("a", "b").flatEvent().toList().assert().isEqualTo(listOf("a", "b"))
        FixtureNamedEvent("single").flatEvent().toList().assert().isEqualTo(listOf(FixtureNamedEvent("single")))
    }

    @Test
    fun `toDomainEventStream creates sequenced events from command context`() {
        val aggregateId = FIXTURE_NAMED_AGGREGATE.toNamedAggregate().aggregateId("factory-aggregate")
        val upstream = GivenInitializationCommand(
            aggregateId = aggregateId,
            id = "command-1",
            ownerId = "",
            spaceId = "",
            requestId = "request-1",
        )
        val header = DefaultHeader.empty().with("trace", "trace-1")

        val stream = listOf(
            FixtureNamedEvent("first"),
            FixtureRevisedEvent("second"),
        ).toDomainEventStream(
            upstream = upstream,
            aggregateVersion = 4,
            stateOwnerId = "state-owner",
            stateSpaceId = "state-space",
            header = header,
            createTime = 2000,
        )
        val events = stream.body

        stream.requestId.assert().isEqualTo("request-1")
        stream.aggregateId.assert().isEqualTo(aggregateId)
        stream.ownerId.assert().isEqualTo("state-owner")
        stream.spaceId.assert().isEqualTo("state-space")
        stream.version.assert().isEqualTo(5)
        stream.createTime.assert().isEqualTo(2000)
        stream.size.assert().isEqualTo(2)
        events[0].sequence.assert().isEqualTo(DEFAULT_EVENT_SEQUENCE)
        events[0].isLast.assert().isFalse()
        events[1].sequence.assert().isEqualTo(DEFAULT_EVENT_SEQUENCE + 1)
        events[1].isLast.assert().isTrue()
        events[1].revision.assert().isEqualTo(FIXTURE_EVENT_REVISION)
        events.forEach {
            it.aggregateId.assert().isEqualTo(aggregateId)
            it.commandId.assert().isEqualTo(upstream.commandId)
            it.header["trace"].assert().isEqualTo("trace-1")
        }

        events[0].header["trace"] = "event-local"
        events[1].header["trace"].assert().isEqualTo("trace-1")
    }
}
