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

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class CommandFunctionBehaviorTest {

    @Test
    fun `command function exposes delegate metadata and annotations`() {
        val delegate = delegateFunction(Mono.just(StateChanged("aggregate-1", "changed")))
        val commandAggregate = commandAggregate()
        val function = CommandFunction(delegate, commandAggregate, emptyList())

        function.contextName.assert().isEqualTo("wow-core-test")
        function.name.assert().isEqualTo("fixture-command")
        function.supportedType.assert().isEqualTo(Create::class.java)
        function.processor.assert().isSameAs(delegate.processor)
        function.functionKind.assert().isEqualTo(FunctionKind.COMMAND)
        function.supportedTopics.assert().contains(commandAggregate.materialize())
        function.getAnnotation(Retry::class.java).assert().isNull()
        function.toString().assert().isEqualTo("CommandFunction(delegate=$delegate)")
    }

    @Test
    fun `command function invokes delegate and stores command result and event stream`() {
        val command = Create("aggregate-1", "created").toCommandMessage()
        val exchange = SimpleServerCommandExchange(command)
        val function = CommandFunction(
            delegate = delegateFunction(Mono.just(StateChanged("aggregate-1", "changed"))),
            commandAggregate = commandAggregate(),
            afterCommandFunctions = emptyList(),
        )

        StepVerifier.create(function.invoke(exchange))
            .assertNext { eventStream ->
                eventStream.version.assert().isEqualTo(1)
                eventStream.commandId.assert().isEqualTo(command.id)
                eventStream.first().body.assert().isEqualTo(StateChanged("aggregate-1", "changed"))
                exchange.getCommandInvokeResult<StateChanged>().assert()
                    .isEqualTo(StateChanged("aggregate-1", "changed"))
                exchange.getEventStream().assert().isSameAs(eventStream)
            }
            .verifyComplete()
    }

    private fun delegateFunction(result: Mono<*>): MessageFunction<MockCommandAggregate, ServerCommandExchange<*>, Mono<*>> {
        return TestCommandMessageFunction(result)
    }

    private fun commandAggregate(): CommandAggregate<MockCommandAggregate, MockCommandAggregate> {
        val metadata = aggregateMetadata<MockCommandAggregate, MockCommandAggregate>()
        val stateRoot = MockCommandAggregate("aggregate-1")
        val stateAggregate = metadata.toStateAggregate(stateRoot, version = 0)
        return SimpleCommandAggregate(
            state = stateAggregate,
            commandRoot = stateRoot,
            eventStore = mockk<EventStore>(relaxed = true),
            metadata = metadata.command,
        )
    }
}

private class TestCommandMessageFunction(
    private val result: Mono<*>
) : MessageFunction<MockCommandAggregate, ServerCommandExchange<*>, Mono<*>> {
    override val contextName: String = "wow-core-test"
    override val name: String = "fixture-command"
    override val supportedType: Class<*> = Create::class.java
    override val supportedTopics = emptySet<me.ahoo.wow.api.modeling.NamedAggregate>()
    override val processor: MockCommandAggregate = MockCommandAggregate("aggregate-1")
    override val functionKind: FunctionKind = FunctionKind.COMMAND

    override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = null

    override fun invoke(exchange: ServerCommandExchange<*>): Mono<*> = result
}
