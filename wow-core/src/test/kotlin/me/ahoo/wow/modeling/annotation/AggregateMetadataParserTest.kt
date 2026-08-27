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

package me.ahoo.wow.modeling.annotation

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.annotation.ORDER_FIRST
import me.ahoo.wow.api.annotation.ORDER_LAST
import org.junit.jupiter.api.Test

class AggregateMetadataParserTest {

    @Test
    fun `parser treats String constructor aggregate as both command and state aggregate`() {
        val metadata = aggregateMetadata<MockAggregate, MockAggregate>()

        metadata.command.aggregateType.assert().isEqualTo(MockAggregate::class.java)
        metadata.state.aggregateType.assert().isEqualTo(MockAggregate::class.java)
        metadata.isAggregationPattern.assert().isFalse()
        metadata.contextName.assert().isEqualTo("wow-core-test")
        metadata.aggregateName.assert().isEqualTo("modeling_annotation_aggregate")
    }

    @Test
    fun `parser treats non String constructor parameter as state aggregate type`() {
        val metadata = aggregateMetadata<MockCommandAggregate, MockStateAggregate>()

        metadata.command.aggregateType.assert().isEqualTo(MockCommandAggregate::class.java)
        metadata.state.aggregateType.assert().isEqualTo(MockStateAggregate::class.java)
        metadata.isAggregationPattern.assert().isTrue()
    }

    @Test
    fun `parser discovers aggregate id accessor on state aggregate`() {
        val metadata = aggregateMetadata<MockAggregateWithAggregateId, MockAggregateWithAggregateId>()

        metadata.state.aggregateIdAccessor.assert().isNotNull()
        metadata.state.aggregateIdAccessor!![MockAggregateWithAggregateId("aggregate-1")]
            .assert().isEqualTo("aggregate-1")
    }

    @Test
    fun `parser rejects command aggregates without one parameter constructor`() {
        assertThrownBy<IllegalStateException> {
            aggregateMetadata<MockCommandAggregateWithoutConstructor, MockCommandAggregateWithoutConstructor>()
        }
    }

    @Test
    fun `parser accepts state aggregates with empty constructor`() {
        val metadata = aggregateMetadata<MockStateAggregateWithoutCtorCommand, MockStateAggregateWithoutCtorState>()

        metadata.state.aggregateType.assert().isEqualTo(MockStateAggregateWithoutCtorState::class.java)
    }

    @Test
    fun `parser rejects state aggregate constructors outside supported string arity`() {
        assertThrownBy<IllegalStateException> {
            aggregateMetadata<MockStateAggregateWithoutRedundantCtorCommand, MockStateAggregateWithoutRedundantCtorState>()
        }
    }

    @Test
    fun `parser reads mounted commands from aggregate root annotation`() {
        val metadata = aggregateMetadata<MockMountAggregate, MockMountAggregate>()

        metadata.command.mountedCommands.assert().containsExactly(MockMountCommand::class.java)
    }

    @Test
    fun `parser sorts after command functions by order`() {
        val metadata = aggregateMetadata<MockAfterCommandAggregate, MockAfterCommandAggregate>()

        metadata.command.afterCommandFunctionRegistry.assert().hasSize(3)
        metadata.command.afterCommandFunctionRegistry.map { it.order.value }
            .assert().containsSequence(ORDER_FIRST, 0, ORDER_LAST)
    }

    @Test
    fun `parser discovers default named and annotated after command functions`() {
        aggregateMetadata<MockDefaultAfterCommandAggregate, MockDefaultAfterCommandAggregate>()
            .command.afterCommandFunctionRegistry.assert().hasSize(1)

        aggregateMetadata<MockMultipleAfterCommandAggregate, MockMultipleAfterCommandAggregate>()
            .command.afterCommandFunctionRegistry.assert().hasSize(2)
    }
}
