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
import me.ahoo.wow.api.abac.DefaultApplyResourceTags
import me.ahoo.wow.api.abac.DefaultResourceTagsApplied
import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.api.command.DefaultRecoverAggregate
import me.ahoo.wow.api.event.DefaultAggregateDeleted
import me.ahoo.wow.api.event.DefaultAggregateRecovered
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier

class DefaultAggregateFunctionBehaviorTest {

    @Test
    fun `default delete aggregate function emits aggregate deleted event`() {
        val aggregate = commandAggregate()
        val function = DefaultDeleteAggregateFunction(aggregate, emptyList())
        val command = DefaultDeleteAggregate.toCommandMessage(
            aggregateId = "aggregate-1",
            namedAggregate = aggregate,
        )

        StepVerifier.create(function.invoke(SimpleServerCommandExchange(command)))
            .assertNext {
                it.first().body.assert().isSameAs(DefaultAggregateDeleted)
            }
            .verifyComplete()
    }

    @Test
    fun `default recover aggregate function emits aggregate recovered event`() {
        val aggregate = commandAggregate()
        val function = DefaultRecoverAggregateFunction(aggregate, emptyList())
        val command = DefaultRecoverAggregate.toCommandMessage(
            aggregateId = "aggregate-1",
            namedAggregate = aggregate,
        )

        StepVerifier.create(function.invoke(SimpleServerCommandExchange(command)))
            .assertNext {
                it.first().body.assert().isSameAs(DefaultAggregateRecovered)
            }
            .verifyComplete()
    }

    @Test
    fun `default apply resource tags function emits applied tags event`() {
        val aggregate = commandAggregate()
        val function = DefaultApplyResourceTagsFunction(aggregate, emptyList())
        val tags = mapOf("department" to listOf("engineering"))
        val command = DefaultApplyResourceTags(tags).toCommandMessage(
            aggregateId = "aggregate-1",
            namedAggregate = aggregate,
        )

        StepVerifier.create(function.invoke(SimpleServerCommandExchange(command)))
            .assertNext {
                it.first().body.assert().isEqualTo(DefaultResourceTagsApplied(tags))
            }
            .verifyComplete()
    }

    private fun commandAggregate(): CommandAggregate<MockCommandAggregate, MockCommandAggregate> =
        CommandFunctionBehaviorTestFixture.commandAggregate()
}

private object CommandFunctionBehaviorTestFixture {
    fun commandAggregate(): CommandAggregate<MockCommandAggregate, MockCommandAggregate> {
        val metadata = me.ahoo.wow.modeling.annotation.aggregateMetadata<MockCommandAggregate, MockCommandAggregate>()
        val stateRoot = MockCommandAggregate("aggregate-1")
        val stateAggregate = me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.run {
            metadata.toStateAggregate(stateRoot, version = 0)
        }
        return SimpleCommandAggregate(
            state = stateAggregate,
            commandRoot = stateRoot,
            eventStore = io.mockk.mockk(relaxed = true),
            metadata = metadata.command,
        )
    }
}
