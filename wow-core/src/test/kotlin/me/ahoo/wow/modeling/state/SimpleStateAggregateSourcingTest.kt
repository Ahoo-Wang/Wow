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
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.command.CommandOperator.withOperator
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.annotation.stateAggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateChanged
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Test

class SimpleStateAggregateSourcingTest {

    @Test
    fun `sourcing applies matching event function and updates aggregate metadata`() {
        val state = MockStateAggregate("aggregate-1")
        val aggregate = MOCK_AGGREGATE_METADATA.toStateAggregate(state, version = 0)
        val stream = MockAggregateChanged("changed").toDomainEventStream(
            upstream = GivenInitializationCommand(
                aggregate.aggregateId,
                header = DefaultHeader.empty().withOperator("operator-1"),
            ),
            aggregateVersion = aggregate.version,
            createTime = 1000,
        )

        aggregate.onSourcing(stream).assert().isSameAs(aggregate)

        state.data.assert().isEqualTo("changed")
        aggregate.version.assert().isEqualTo(1)
        aggregate.eventId.assert().isEqualTo(stream.id)
        aggregate.operator.assert().isEqualTo("operator-1")
        aggregate.firstOperator.assert().isEqualTo("operator-1")
        aggregate.eventTime.assert().isEqualTo(stream.createTime)
        aggregate.firstEventTime.assert().isEqualTo(stream.createTime)
    }

    @Test
    fun `sourcing ignores event bodies without sourcing function but still advances version`() {
        val aggregate = MOCK_AGGREGATE_METADATA.toStateAggregate(MockStateAggregate("aggregate-1"), version = 0)
        val stream = UnknownStateEvent("ignored").toDomainEventStream(
            upstream = GivenInitializationCommand(aggregate.aggregateId),
            aggregateVersion = aggregate.version,
        )

        aggregate.onSourcing(stream)

        aggregate.version.assert().isEqualTo(1)
    }

    @Test
    fun `sourcing ignores initial error streams marked ignore sourcing`() {
        val aggregate = MOCK_AGGREGATE_METADATA.toStateAggregate(MockStateAggregate("aggregate-1"), version = 0)
        val stream = IgnoredErrorEvent("failed", "failed event").toDomainEventStream(
            upstream = GivenInitializationCommand(aggregate.aggregateId),
            aggregateVersion = aggregate.version,
        )

        aggregate.onSourcing(stream)

        aggregate.version.assert().isEqualTo(0)
        aggregate.eventId.assert().isEmpty()
    }

    @Test
    fun `sourcing rejects mismatched aggregate id and version conflicts`() {
        val aggregate = MOCK_AGGREGATE_METADATA.toStateAggregate(MockStateAggregate("aggregate-1"), version = 0)
        val wrongAggregateStream = MockAggregateChanged("changed").toDomainEventStream(
            upstream = GivenInitializationCommand(MOCK_AGGREGATE_METADATA.aggregateId("other")),
            aggregateVersion = aggregate.version,
        )
        val versionConflictStream = MockAggregateChanged("changed").toDomainEventStream(
            upstream = GivenInitializationCommand(aggregate.aggregateId),
            aggregateVersion = aggregate.version + 1,
        )

        assertThrownBy<IllegalArgumentException> {
            aggregate.onSourcing(wrongAggregateStream)
        }
        assertThrownBy<SourcingVersionConflictException> {
            aggregate.onSourcing(versionConflictStream)
        }
    }

    @Test
    fun `a failing sourcing function leaves the aggregate metadata at the previous version`() {
        val metadata = stateAggregateMetadata<FailingSourcingState>()
        val state = FailingSourcingState("aggregate-1")
        val aggregate = metadata.toStateAggregate(
            aggregateId = MOCK_AGGREGATE_METADATA.namedAggregate.aggregateId("aggregate-1"),
            state = state,
            version = 0,
        )
        val stream = listOf(
            SourcedValue("applied"),
            TestOwnerTransferred("owner-2"),
            SourcingRejected("rejected"),
        ).toDomainEventStream(
            upstream = GivenInitializationCommand(
                aggregate.aggregateId,
                header = DefaultHeader.empty().withOperator("operator-1"),
            ),
            aggregateVersion = aggregate.version,
            createTime = 1000,
        )

        assertThrownBy<IllegalStateException> {
            aggregate.onSourcing(stream)
        }

        aggregate.version.assert().isEqualTo(0)
        aggregate.initialized.assert().isFalse()
        aggregate.eventId.assert().isEmpty()
        aggregate.operator.assert().isEmpty()
        aggregate.firstOperator.assert().isEmpty()
        aggregate.eventTime.assert().isEqualTo(0)
        aggregate.ownerId.assert().isEqualTo(OwnerId.DEFAULT_OWNER_ID)
        state.version.assert().isEqualTo(0)
    }

    @Test
    fun `the state version advances after its sourcing functions ran`() {
        val metadata = stateAggregateMetadata<FailingSourcingState>()
        val state = FailingSourcingState("aggregate-1")
        val aggregate = metadata.toStateAggregate(
            aggregateId = MOCK_AGGREGATE_METADATA.namedAggregate.aggregateId("aggregate-1"),
            state = state,
            version = 0,
        )

        aggregate.onSourcing(
            SourcedValue("applied").toDomainEventStream(
                upstream = GivenInitializationCommand(aggregate.aggregateId),
                aggregateVersion = aggregate.version,
            ),
        )

        state.versionsSeen.assert().isEqualTo(listOf(0))
        state.version.assert().isEqualTo(1)
        aggregate.version.assert().isEqualTo(1)
    }
}
