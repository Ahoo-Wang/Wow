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

package me.ahoo.wow.eventsourcing

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.DuplicateRequestIdException
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateChanged
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier

class InMemoryEventStoreTest {

    @Test
    fun `load returns copied streams in requested version and time ranges`() {
        val eventStore = InMemoryEventStore()
        val first = eventStream(data = "first", requestId = "request-1", aggregateVersion = 0, createTime = 1000)
        val second = eventStream(data = "second", requestId = "request-2", aggregateVersion = 1, createTime = 2000)
        eventStore.append(first).block()
        eventStore.append(second).block()

        StepVerifier.create(eventStore.load(first.aggregateId, headVersion = 2, tailVersion = 2))
            .assertNext { loaded ->
                loaded.assert().isNotSameAs(second)
                loaded.id.assert().isEqualTo(second.id)
                loaded.version.assert().isEqualTo(2)
            }
            .verifyComplete()
        StepVerifier.create(eventStore.load(first.aggregateId, headEventTime = 1500, tailEventTime = 2500))
            .assertNext { loaded ->
                loaded.id.assert().isEqualTo(second.id)
            }
            .verifyComplete()
        StepVerifier.create(eventStore.last(first.aggregateId))
            .assertNext { loaded ->
                loaded.assert().isNotSameAs(second)
                loaded.id.assert().isEqualTo(second.id)
            }
            .verifyComplete()
    }

    @Test
    fun `append rejects duplicate initial version and duplicate request id`() {
        val eventStore = InMemoryEventStore()
        val first = eventStream(data = "first", requestId = "request-1", aggregateVersion = 0)
        eventStore.append(first).block()

        StepVerifier.create(
            eventStore.append(
                eventStream(data = "conflict", requestId = "request-2", aggregateVersion = 0)
            )
        )
            .expectError(DuplicateAggregateIdException::class.java)
            .verify()
        StepVerifier.create(
            eventStore.append(
                eventStream(data = "duplicate", requestId = "request-1", aggregateVersion = 1)
            )
        )
            .expectError(DuplicateRequestIdException::class.java)
            .verify()
    }

    private fun eventStream(
        data: String,
        requestId: String,
        aggregateVersion: Int,
        createTime: Long = 1000,
    ): DomainEventStream =
        MockAggregateChanged(data).toDomainEventStream(
            upstream = GivenInitializationCommand(
                aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("aggregate-1"),
                requestId = requestId,
            ),
            aggregateVersion = aggregateVersion,
            createTime = createTime,
        )
}
