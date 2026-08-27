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
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test

class StateAggregatesHelperTest {

    @Test
    fun `aggregate metadata helper builds state aggregate from state accessor`() {
        val state = MockStateAggregate("aggregate-1")

        val aggregate = MOCK_AGGREGATE_METADATA.toStateAggregate(
            state = state,
            version = 5,
            eventId = "event-1",
            firstOperator = "first-operator",
            operator = "operator",
            firstEventTime = 100,
            eventTime = 200,
            deleted = true,
        )

        aggregate.aggregateId.id.assert().isEqualTo("aggregate-1")
        aggregate.state.assert().isSameAs(state)
        aggregate.version.assert().isEqualTo(5)
        aggregate.eventId.assert().isEqualTo("event-1")
        aggregate.firstOperator.assert().isEqualTo("first-operator")
        aggregate.operator.assert().isEqualTo("operator")
        aggregate.firstEventTime.assert().isEqualTo(100)
        aggregate.eventTime.assert().isEqualTo(200)
        aggregate.deleted.assert().isTrue()
    }

    @Test
    fun `state metadata helper builds state aggregate from explicit aggregate id`() {
        val state = MockStateAggregate("aggregate-1")
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("aggregate-1")

        val aggregate = MOCK_AGGREGATE_METADATA.state.toStateAggregate(
            aggregateId = aggregateId,
            state = state,
            version = 3,
        )

        aggregate.aggregateId.assert().isSameAs(aggregateId)
        aggregate.state.assert().isSameAs(state)
        aggregate.version.assert().isEqualTo(3)
    }

    @Test
    fun `read only helper converts back to writable state aggregate preserving fields`() {
        val source = MOCK_AGGREGATE_METADATA.state.toStateAggregate(
            aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("aggregate-1"),
            state = MockStateAggregate("aggregate-1"),
            version = 4,
            eventId = "event-1",
            firstOperator = "first-operator",
            operator = "operator",
            firstEventTime = 100,
            eventTime = 200,
            deleted = true,
        )

        val converted = (source as ReadOnlyStateAggregate<MockStateAggregate>).toStateAggregate()

        converted.aggregateId.assert().isEqualTo(source.aggregateId)
        converted.state.assert().isSameAs(source.state)
        converted.version.assert().isEqualTo(source.version)
        converted.eventId.assert().isEqualTo(source.eventId)
        converted.firstOperator.assert().isEqualTo(source.firstOperator)
        converted.operator.assert().isEqualTo(source.operator)
        converted.firstEventTime.assert().isEqualTo(source.firstEventTime)
        converted.eventTime.assert().isEqualTo(source.eventTime)
        converted.deleted.assert().isEqualTo(source.deleted)
    }
}
