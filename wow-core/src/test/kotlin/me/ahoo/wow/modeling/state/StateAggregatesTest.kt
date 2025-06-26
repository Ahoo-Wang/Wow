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
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test

class StateAggregatesTest {
    private val aggregateMetadata = MOCK_AGGREGATE_METADATA

    @Test
    fun toStateAggregate() {
        val state = MockStateAggregate(generateGlobalId())
        val stateAggregate = aggregateMetadata.toStateAggregate(
            state = state,
            version = 1,
            eventId = "eventId",
            firstOperator = "firstOperator",
            operator = "operator",
            firstEventTime = 1,
            eventTime = 1,
            deleted = false
        )
        stateAggregate.aggregateId.id.assert().isEqualTo(state.id)
        stateAggregate.state.assert().isEqualTo(state)
        stateAggregate.version.assert().isEqualTo(1)
        stateAggregate.eventId.assert().isEqualTo("eventId")
        stateAggregate.firstOperator.assert().isEqualTo("firstOperator")
        stateAggregate.operator.assert().isEqualTo("operator")
        stateAggregate.firstEventTime.assert().isEqualTo(1)
        stateAggregate.deleted.assert().isEqualTo(false)
    }

    @Test
    fun toStateAggregateGivenAggregateId() {
        val state = MockStateAggregate(generateGlobalId())
        val stateAggregate = aggregateMetadata.state.toStateAggregate(
            aggregateId = aggregateMetadata.aggregateId(state.id),
            state = state,
            version = 1,
            eventId = "eventId",
            firstOperator = "firstOperator",
            operator = "operator",
            firstEventTime = 1,
            eventTime = 1,
            deleted = false
        )
        stateAggregate.aggregateId.id.assert().isEqualTo(state.id)
        stateAggregate.state.assert().isEqualTo(state)
        stateAggregate.version.assert().isEqualTo(1)
        stateAggregate.eventId.assert().isEqualTo("eventId")
        stateAggregate.firstOperator.assert().isEqualTo("firstOperator")
        stateAggregate.operator.assert().isEqualTo("operator")
        stateAggregate.firstEventTime.assert().isEqualTo(1)
        stateAggregate.deleted.assert().isEqualTo(false)
    }

    @Test
    fun toStateAggregateGivenReadOnly() {
        val state = MockStateAggregate(generateGlobalId())
        val stateAggregate = aggregateMetadata.state.toStateAggregate(
            aggregateId = aggregateMetadata.aggregateId(state.id),
            state = state,
            version = 1,
            eventId = "eventId",
            firstOperator = "firstOperator",
            operator = "operator",
            firstEventTime = 1,
            eventTime = 1,
            deleted = false
        )
        val readOnlyStateAggregate = stateAggregate as ReadOnlyStateAggregate<MockStateAggregate>
        val fromReadyOnly = readOnlyStateAggregate.toStateAggregate()
        fromReadyOnly.aggregateId.assert().isEqualTo(stateAggregate.aggregateId)
        fromReadyOnly.state.assert().isEqualTo(stateAggregate.state)
        fromReadyOnly.version.assert().isEqualTo(stateAggregate.version)
        fromReadyOnly.eventId.assert().isEqualTo(stateAggregate.eventId)
        fromReadyOnly.firstOperator.assert().isEqualTo(stateAggregate.firstOperator)
        fromReadyOnly.operator.assert().isEqualTo(stateAggregate.operator)
        fromReadyOnly.firstEventTime.assert().isEqualTo(stateAggregate.firstEventTime)
        fromReadyOnly.deleted.assert().isEqualTo(stateAggregate.deleted)
    }
}
