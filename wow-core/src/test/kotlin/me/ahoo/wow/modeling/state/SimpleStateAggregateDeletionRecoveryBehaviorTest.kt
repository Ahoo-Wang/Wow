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

package me.ahoo.wow.modeling.state

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.abac.DefaultResourceTagsApplied
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.annotation.stateAggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Test

class SimpleStateAggregateDeletionRecoveryBehaviorTest {

    @Test
    fun `sourcing deletion and recovery events toggles deleted flag`() {
        val aggregate = MOCK_AGGREGATE_METADATA.toStateAggregate(MockStateAggregate("aggregate-1"), version = 0)

        aggregate.onSourcing(
            TestAggregateDeleted.toDomainEventStream(
                upstream = GivenInitializationCommand(aggregate.aggregateId),
                aggregateVersion = aggregate.version,
            )
        )
        aggregate.deleted.assert().isTrue()

        aggregate.onSourcing(
            TestAggregateRecovered.toDomainEventStream(
                upstream = GivenInitializationCommand(aggregate.aggregateId),
                aggregateVersion = aggregate.version,
            )
        )
        aggregate.deleted.assert().isFalse()
    }

    @Test
    fun `sourcing transfer and resource tag events update aggregate ownership metadata`() {
        val aggregate = MOCK_AGGREGATE_METADATA.toStateAggregate(MockStateAggregate("aggregate-1"), version = 0)
        val tags = mapOf("department" to listOf("engineering"))

        aggregate.onSourcing(
            listOf(
                TestOwnerTransferred("owner-2"),
                TestSpaceTransferred("space-2"),
                DefaultResourceTagsApplied(tags),
            ).toDomainEventStream(
                upstream = GivenInitializationCommand(aggregate.aggregateId),
                aggregateVersion = aggregate.version,
            )
        )

        aggregate.ownerId.assert().isEqualTo("owner-2")
        aggregate.spaceId.assert().isEqualTo("space-2")
        aggregate.tags.assert().isEqualTo(tags)
    }

    @Test
    fun `sourcing lets state tag extractor override event tags after event application`() {
        val metadata = stateAggregateMetadata<TagExtractingState>()
        val aggregateId = MOCK_AGGREGATE_METADATA.namedAggregate.aggregateId("aggregate-1")
        val aggregate = metadata.toStateAggregate(
            aggregateId = aggregateId,
            state = TagExtractingState("aggregate-1"),
            version = 0,
        )

        aggregate.onSourcing(
            DefaultResourceTagsApplied(mapOf("event" to listOf("tag"))).toDomainEventStream(
                upstream = GivenInitializationCommand(aggregate.aggregateId),
                aggregateVersion = aggregate.version,
            )
        )

        aggregate.tags.assert().isEqualTo(mapOf("state" to listOf("aggregate-1")))
    }
}
