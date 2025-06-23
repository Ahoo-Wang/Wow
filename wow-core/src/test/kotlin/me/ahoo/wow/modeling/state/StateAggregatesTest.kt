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

import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
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
        assertThat(stateAggregate.aggregateId.id, equalTo(state.id))
        assertThat(stateAggregate.state, equalTo(state))
        assertThat(stateAggregate.version, equalTo(1))
        assertThat(stateAggregate.eventId, equalTo("eventId"))
        assertThat(stateAggregate.firstOperator, equalTo("firstOperator"))
        assertThat(stateAggregate.operator, equalTo("operator"))
        assertThat(stateAggregate.firstEventTime, equalTo(1))
        assertThat(stateAggregate.deleted, equalTo(false))
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
        assertThat(stateAggregate.aggregateId.id, equalTo(state.id))
        assertThat(stateAggregate.state, equalTo(state))
        assertThat(stateAggregate.version, equalTo(1))
        assertThat(stateAggregate.eventId, equalTo("eventId"))
        assertThat(stateAggregate.firstOperator, equalTo("firstOperator"))
        assertThat(stateAggregate.operator, equalTo("operator"))
        assertThat(stateAggregate.firstEventTime, equalTo(1))
        assertThat(stateAggregate.deleted, equalTo(false))
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
        assertThat(fromReadyOnly.aggregateId, equalTo(stateAggregate.aggregateId))
        assertThat(fromReadyOnly.state, equalTo(stateAggregate.state))
        assertThat(fromReadyOnly.version, equalTo(stateAggregate.version))
        assertThat(fromReadyOnly.eventId, equalTo(stateAggregate.eventId))
        assertThat(fromReadyOnly.firstOperator, equalTo(stateAggregate.firstOperator))
        assertThat(fromReadyOnly.operator, equalTo(stateAggregate.operator))
        assertThat(fromReadyOnly.firstEventTime, equalTo(stateAggregate.firstEventTime))
        assertThat(fromReadyOnly.deleted, equalTo(stateAggregate.deleted))
    }
}
