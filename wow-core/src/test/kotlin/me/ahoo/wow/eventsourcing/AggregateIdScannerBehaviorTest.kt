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
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier

class AggregateIdScannerBehaviorTest {

    @Test
    fun `scanner boundary constants sort before and after normal ids`() {
        AggregateIdScanner.FIRST_ID.assert().isEqualTo("(0)")
        AggregateIdScanner.LAST_ID.assert().isEqualTo("~")
        ("aggregate-1" > AggregateIdScanner.FIRST_ID).assert().isTrue()
        ("aggregate-1" < AggregateIdScanner.LAST_ID).assert().isTrue()
    }

    @Test
    fun `in memory scanner honors aggregate name after id and limit boundaries`() {
        val repository = InMemorySnapshotRepository()
        val namedAggregate = MOCK_AGGREGATE_METADATA.materialize()
        val matchingIds = listOf("a", "b", "c").map { namedAggregate.aggregateId(it) }
        val otherAggregateId = "other.fixture".toNamedAggregate().aggregateId("b")

        (matchingIds + otherAggregateId).forEach { aggregateId ->
            val stateAggregate = MOCK_AGGREGATE_METADATA.state.toStateAggregate(
                aggregateId = aggregateId,
                state = MockStateAggregate(aggregateId.id),
                version = 1,
            )
            StepVerifier.create(repository.save(SimpleSnapshot(stateAggregate)))
                .verifyComplete()
        }

        StepVerifier.create(repository.scanAggregateId(namedAggregate, afterId = "a", limit = 1))
            .assertNext {
                it.id.assert().isEqualTo("b")
                it.assert().isEqualTo(matchingIds[1])
            }
            .verifyComplete()
        StepVerifier.create(
            repository.scanAggregateId(
                namedAggregate,
                afterId = AggregateIdScanner.LAST_ID,
                limit = 10,
            )
        )
            .verifyComplete()
    }
}
