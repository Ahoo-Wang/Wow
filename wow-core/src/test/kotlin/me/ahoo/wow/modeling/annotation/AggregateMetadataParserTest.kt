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

/**
 * AnnotationAggregateParserTest .
 *
 * @author ahoo wang
 */
internal class AggregateMetadataParserTest {
    @Test
    fun parse() {
        val aggregateMetadata =
            aggregateMetadata<MockAggregate, MockAggregate>()
        aggregateMetadata.state.aggregateType.assert().isEqualTo(MockAggregate::class.java)
        aggregateMetadata.isAggregationPattern.assert().isFalse()
    }

    @Test
    fun parseCombined() {
        val aggregateMetadata = aggregateMetadata<MockCommandAggregate, me.ahoo.wow.tck.mock.MockStateAggregate>()
        aggregateMetadata.command.aggregateType.assert().isEqualTo(MockCommandAggregate::class.java)
        aggregateMetadata.state.aggregateType.assert().isEqualTo(MockStateAggregate::class.java)
        aggregateMetadata.isAggregationPattern.assert().isTrue()
    }

    @Test
    fun parseWhenWithAggregateId() {
        val aggregateMetadata = aggregateMetadata<MockAggregateWithAggregateId, MockAggregateWithAggregateId>()
        aggregateMetadata.state.aggregateType.assert().isEqualTo(MockAggregateWithAggregateId::class.java)
    }

    @Test
    fun parseMockCommandAggregateWithoutConstructor() {
        assertThrownBy<IllegalStateException> {
            aggregateMetadata<MockCommandAggregateWithoutConstructor, MockCommandAggregateWithoutConstructor>()
        }
    }

    @Test
    fun parseMockStateAggregateWithoutCtorCommand() {
        val aggregateMetadata =
            aggregateMetadata<MockStateAggregateWithoutCtorCommand, MockStateAggregateWithoutCtorState>()
        aggregateMetadata.state.aggregateType.assert().isEqualTo(MockStateAggregateWithoutCtorState::class.java)
    }

    @Test
    fun parseMountAggregate() {
        val aggregateMetadata = aggregateMetadata<MockMountAggregate, MockMountAggregate>()
        aggregateMetadata.command.mountedCommands.assert().hasSize(1)
        val mountCommand = aggregateMetadata.command.mountedCommands.first()
        mountCommand.assert().isEqualTo(MockMountCommand::class.java)
    }

    @Test
    fun parseAfterCommandAggregate() {
        val aggregateMetadata = aggregateMetadata<MockAfterCommandAggregate, MockAfterCommandAggregate>()
        aggregateMetadata.command.afterCommandFunctionRegistry.assert().hasSize(3)
        aggregateMetadata.command.afterCommandFunctionRegistry[0].order.value.assert().isEqualTo(ORDER_FIRST)
        aggregateMetadata.command.afterCommandFunctionRegistry[1].order.value.assert().isEqualTo(0)
        aggregateMetadata.command.afterCommandFunctionRegistry[2].order.value.assert().isEqualTo(ORDER_LAST)
    }

    @Test
    fun parseDefaultAfterCommandAggregate() {
        val aggregateMetadata = aggregateMetadata<MockDefaultAfterCommandAggregate, MockDefaultAfterCommandAggregate>()
        aggregateMetadata.command.afterCommandFunctionRegistry.assert().hasSize(1)
    }

    @Test
    fun parseMockMultipleAfterCommandAggregate() {
        val aggregateMetadata =
            aggregateMetadata<MockMultipleAfterCommandAggregate, MockMultipleAfterCommandAggregate>()
        aggregateMetadata.command.afterCommandFunctionRegistry.assert().hasSize(2)
    }
}
