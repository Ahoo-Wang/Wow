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

package me.ahoo.wow.modeling.command

import me.ahoo.test.asserts.assert
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test

class SimpleCommandAggregateCreationBehaviorTest {

    @Test
    fun `factory uses state root directly when command and state types match`() {
        val metadata =
            aggregateMetadata<me.ahoo.wow.modeling.command.MockCommandAggregate, me.ahoo.wow.modeling.command.MockCommandAggregate>()
        val stateRoot = me.ahoo.wow.modeling.command.MockCommandAggregate("aggregate-1")
        val stateAggregate = metadata.toStateAggregate(stateRoot, version = 0)

        val commandAggregate = SimpleCommandAggregateFactory(InMemoryEventStore())
            .create(metadata, stateAggregate)

        commandAggregate.commandRoot.assert().isSameAs(stateRoot)
        commandAggregate.state.assert().isSameAs(stateAggregate)
        commandAggregate.commandState.assert().isEqualTo(CommandState.STORED)
        commandAggregate.processorName.assert().isEqualTo("SimpleCommandAggregate")
    }

    @Test
    fun `factory constructs command root from state when aggregate uses aggregation pattern`() {
        val stateRoot = MockStateAggregate("aggregate-1")
        val stateAggregate = MOCK_AGGREGATE_METADATA.toStateAggregate(stateRoot, version = 0)

        val commandAggregate = SimpleCommandAggregateFactory(InMemoryEventStore())
            .create(MOCK_AGGREGATE_METADATA, stateAggregate)

        commandAggregate.commandRoot.assert().isInstanceOf(MockCommandAggregate::class.java)
        commandAggregate.commandRoot.state.assert().isSameAs(stateRoot)
    }
}
