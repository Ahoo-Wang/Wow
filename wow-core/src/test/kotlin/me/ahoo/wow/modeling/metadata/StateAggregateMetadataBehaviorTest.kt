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

package me.ahoo.wow.modeling.metadata

import me.ahoo.test.asserts.assert
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateChanged
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Test

class StateAggregateMetadataBehaviorTest {

    @Test
    fun `state aggregate metadata equality hash and string are based on aggregate type`() {
        val metadata = MOCK_AGGREGATE_METADATA.state

        metadata.assert().isEqualTo(MOCK_AGGREGATE_METADATA.state)
        metadata.hashCode().assert().isEqualTo(MockStateAggregate::class.java.hashCode())
        metadata.toString().assert().isEqualTo("StateAggregateMetadata(aggregateType=${metadata.aggregateType})")
        metadata.equals(Any()).assert().isFalse()
    }

    @Test
    fun `state aggregate metadata converts sourcing registry to message functions`() {
        val state = MockStateAggregate("aggregate-1")
        val registry = MOCK_AGGREGATE_METADATA.state.toMessageFunctionRegistry(state)

        registry.keys.assert().isNotEmpty()
        registry.values.forEach {
            it.processor.assert().isSameAs(state)
        }
    }

    @Test
    fun `state aggregate metadata sources matching domain events directly`() {
        val state = MockStateAggregate("aggregate-1")
        val command = GivenInitializationCommand(MOCK_AGGREGATE_METADATA.aggregateId("aggregate-1"))
        val matchedEvent = MockAggregateChanged("changed")
            .toDomainEventStream(upstream = command)
            .first()
        val ignoredEvent = UnknownStateMetadataEvent
            .toDomainEventStream(upstream = command)
            .first()

        MOCK_AGGREGATE_METADATA.state.sourcing(state, matchedEvent).assert().isTrue()
        MOCK_AGGREGATE_METADATA.state.sourcing(state, ignoredEvent).assert().isFalse()

        state.data.assert().isEqualTo("changed")
    }

    private object UnknownStateMetadataEvent
}
