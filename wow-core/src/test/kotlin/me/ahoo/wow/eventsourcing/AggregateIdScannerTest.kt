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
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier

class AggregateIdScannerTest {

    @Test
    fun `scanner boundary constants sort before and after normal ids`() {
        AggregateIdScanner.FIRST_ID.assert().isEqualTo("(0)")
        AggregateIdScanner.LAST_ID.assert().isEqualTo("~")
        ("aggregate-1" > AggregateIdScanner.FIRST_ID).assert().isTrue()
        ("aggregate-1" < AggregateIdScanner.LAST_ID).assert().isTrue()
    }

    @Test
    fun `event store scanner exposes default arguments`() {
        val eventStore: EventStore = InMemoryEventStore()
        val namedAggregate = MOCK_AGGREGATE_METADATA.materialize()
        val aggregateId = namedAggregate.aggregateId("default-arguments")

        StepVerifier.create(eventStore.append(generateEventStream(aggregateId)))
            .verifyComplete()

        StepVerifier.create(eventStore.scanAggregateId(namedAggregate))
            .assertNext {
                it.assert().isEqualTo(aggregateId)
            }
            .verifyComplete()
    }

    @Test
    fun `in memory scanner honors aggregate name after id and limit boundaries`() {
        val eventStore = InMemoryEventStore()
        val namedAggregate = MOCK_AGGREGATE_METADATA.materialize()
        val matchingIds = listOf("a", "b", "c").map { namedAggregate.aggregateId(it) }
        val otherAggregateId = "other.fixture".toNamedAggregate().aggregateId("b")

        (matchingIds + otherAggregateId).forEach { aggregateId ->
            StepVerifier.create(eventStore.append(generateEventStream(aggregateId)))
                .verifyComplete()
        }

        StepVerifier.create(eventStore.scanAggregateId(namedAggregate, afterId = "a", limit = 1))
            .assertNext {
                it.id.assert().isEqualTo("b")
                it.assert().isEqualTo(matchingIds[1])
            }
            .verifyComplete()
        StepVerifier.create(
            eventStore.scanAggregateId(
                namedAggregate,
                afterId = AggregateIdScanner.LAST_ID,
                limit = 10,
            )
        )
            .verifyComplete()
    }
}
