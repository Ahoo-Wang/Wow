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
import me.ahoo.wow.modeling.annotation.CreateCmd
import me.ahoo.wow.modeling.annotation.MockAfterCommandAggregate
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import org.junit.jupiter.api.Test

class CommandFunctionResolverTest {

    @Test
    fun `command function is cached after first successful resolution`() {
        val resolver = afterCommandResolver()

        val first = resolver.commandFunction(CreateCmd::class.java)
        val second = resolver.commandFunction(CreateCmd::class.java)

        first.assert().isNotNull()
        second.assert().isSameAs(first)
    }

    @Test
    fun `error function is cached after first successful resolution`() {
        val resolver = errorHandlingResolver()

        val first = resolver.errorFunction(FailingCommand::class.java)
        val second = resolver.errorFunction(FailingCommand::class.java)

        first.assert().isNotNull()
        second.assert().isSameAs(first)
    }

    @Test
    fun `missing error function is not cached`() {
        val resolver = errorHandlingResolver()

        resolver.errorFunction(String::class.java).assert().isNull()
        resolver.errorFunction(String::class.java).assert().isNull()
    }

    private fun afterCommandResolver(): CommandFunctionResolver<MockAfterCommandAggregate> {
        val metadata = aggregateMetadata<MockAfterCommandAggregate, MockAfterCommandAggregate>()
        val commandRoot = MockAfterCommandAggregate("aggregate-1")
        val commandAggregate = SimpleCommandAggregate(
            state = metadata.toStateAggregate(commandRoot, version = 0),
            commandRoot = commandRoot,
            eventStore = InMemoryEventStore(),
            metadata = metadata.command,
        )
        return CommandFunctionResolver(metadata.command, commandAggregate)
    }

    private fun errorHandlingResolver(): CommandFunctionResolver<ErrorHandlingCommandAggregate> {
        val metadata = aggregateMetadata<ErrorHandlingCommandAggregate, ErrorHandlingCommandAggregate>()
        val commandRoot = ErrorHandlingCommandAggregate("aggregate-1")
        val commandAggregate = SimpleCommandAggregate(
            state = metadata.toStateAggregate(commandRoot, version = 0),
            commandRoot = commandRoot,
            eventStore = InMemoryEventStore(),
            metadata = metadata.command,
        )
        return CommandFunctionResolver(metadata.command, commandAggregate)
    }
}
