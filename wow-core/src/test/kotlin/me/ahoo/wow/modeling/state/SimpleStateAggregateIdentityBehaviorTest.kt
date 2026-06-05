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
import me.ahoo.wow.api.Version
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test

class SimpleStateAggregateIdentityBehaviorTest {

    @Test
    fun `state aggregate exposes identity initialization and string fields`() {
        val state = MockStateAggregate("aggregate-1")
        val aggregate = MOCK_AGGREGATE_METADATA.toStateAggregate(
            state = state,
            version = Version.UNINITIALIZED_VERSION,
            ownerId = "owner-1",
            spaceId = "space-1",
            eventId = "event-1",
            firstOperator = "first-operator",
            operator = "operator",
            firstEventTime = 100,
            eventTime = 200,
            deleted = true,
        )

        aggregate.aggregateId.id.assert().isEqualTo("aggregate-1")
        aggregate.state.assert().isSameAs(state)
        aggregate.initialized.assert().isFalse()
        aggregate.expectedNextVersion.assert().isEqualTo(1)
        aggregate.ownerId.assert().isEqualTo("owner-1")
        aggregate.spaceId.assert().isEqualTo("space-1")
        aggregate.eventId.assert().isEqualTo("event-1")
        aggregate.firstOperator.assert().isEqualTo("first-operator")
        aggregate.operator.assert().isEqualTo("operator")
        aggregate.firstEventTime.assert().isEqualTo(100)
        aggregate.eventTime.assert().isEqualTo(200)
        aggregate.deleted.assert().isTrue()
        aggregate.toString().assert().isEqualTo("SimpleStateAggregate(aggregateId=${aggregate.aggregateId}, version=0)")
    }

    @Test
    fun `state aggregate equality and hash use aggregate id and version`() {
        val state = MockStateAggregate("aggregate-1")
        val first = MOCK_AGGREGATE_METADATA.toStateAggregate(state, version = 1)
        val same = MOCK_AGGREGATE_METADATA.toStateAggregate(MockStateAggregate("aggregate-1"), version = 1)
        val differentVersion = MOCK_AGGREGATE_METADATA.toStateAggregate(MockStateAggregate("aggregate-1"), version = 2)

        first.assert().isEqualTo(same)
        first.hashCode().assert().isEqualTo(same.hashCode())
        first.assert().isNotEqualTo(differentVersion)
        first.assert().isNotEqualTo(Any())
    }
}
