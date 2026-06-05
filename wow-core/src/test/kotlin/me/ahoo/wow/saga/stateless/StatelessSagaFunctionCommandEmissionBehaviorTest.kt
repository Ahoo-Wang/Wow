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

package me.ahoo.wow.saga.stateless

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.factory.CommandBuilder
import me.ahoo.wow.command.factory.CommandMessageFactory
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.SimpleDomainEventExchange
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.propagation.TraceMessagePropagator.Companion.traceId
import me.ahoo.wow.messaging.propagation.TraceMessagePropagator.Companion.upstreamId
import me.ahoo.wow.messaging.propagation.TraceMessagePropagator.Companion.upstreamName
import me.ahoo.wow.messaging.propagation.TraceMessagePropagator.Companion.withTraceId
import me.ahoo.wow.tck.mock.MockChangeAggregate
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration

class StatelessSagaFunctionCommandEmissionBehaviorTest {

    @Test
    fun `single command result is converted sent collected and stored on exchange`() {
        val event = fixtureEvent()
        val exchange = SimpleDomainEventExchange(event)
        val sentCommands = mutableListOf<CommandMessage<*>>()
        val function = statelessSagaFunction(
            result = Mono.just(MockCreateAggregate("next-id", "create")),
            sentCommands = sentCommands,
        )

        StepVerifier.create(function.invoke(exchange))
            .assertNext { stream ->
                val commands = stream.toList()
                stream.domainEventId.assert().isEqualTo(event.id)
                stream.size.assert().isEqualTo(1)
                commands[0].body.assert().isInstanceOf(MockCreateAggregate::class.java)
                commands[0].requestId.assert().isEqualTo("${event.id}-0")
                commands[0].aggregateId.tenantId.assert().isEqualTo(event.aggregateId.tenantId)
                commands[0].spaceId.assert().isEqualTo(event.spaceId)
                commands[0].header.traceId.assert().isEqualTo(event.header.traceId)
                commands[0].header.upstreamId.assert().isEqualTo(event.id)
                commands[0].header.upstreamName.assert().isEqualTo(event.name)
                exchange.getCommandStream().assert().isSameAs(stream)
            }.verifyComplete()

        sentCommands.map { it.body.javaClass as Class<*> }.assert().containsExactly(MockCreateAggregate::class.java)
    }

    @Test
    fun `iterable command result is converted and sent in order`() {
        val event = fixtureEvent()
        val sentCommands = mutableListOf<CommandMessage<*>>()
        val commandFactory = delayingCommandMessageFactory(firstBodyType = MockCreateAggregate::class.java)
        val function = statelessSagaFunction(
            result = Mono.just(
                listOf(
                    MockCreateAggregate("next-id", "create"),
                    MockChangeAggregate("next-id", "change"),
                )
            ),
            sentCommands = sentCommands,
            commandMessageFactory = commandFactory,
        )

        StepVerifier.create(function.invoke(SimpleDomainEventExchange(event)))
            .assertNext { stream ->
                val commands = stream.toList()
                stream.domainEventId.assert().isEqualTo(event.id)
                stream.size.assert().isEqualTo(2)
                commands.map { it.body.javaClass as Class<*> }.assert().containsExactly(
                    MockCreateAggregate::class.java,
                    MockChangeAggregate::class.java,
                )
                commands.map { it.requestId }.assert().containsExactly(
                    "${event.id}-0",
                    "${event.id}-1",
                )
            }.verifyComplete()

        sentCommands.map { it.body.javaClass as Class<*> }.assert().containsExactly(
            MockCreateAggregate::class.java,
            MockChangeAggregate::class.java,
        )
    }

    @Test
    fun `prebuilt command message is sent unchanged after propagating domain event header`() {
        val event = fixtureEvent()
        val prebuilt = MockCreateAggregate("next-id", "create")
            .toCommandMessage(requestId = "prebuilt-request")
        val sentCommands = mutableListOf<CommandMessage<*>>()
        val function = statelessSagaFunction(
            result = Mono.just(prebuilt),
            sentCommands = sentCommands,
        )

        StepVerifier.create(function.invoke(SimpleDomainEventExchange(event)))
            .assertNext { stream ->
                val commands = stream.toList()
                commands.assert().containsExactly(prebuilt)
                commands[0].requestId.assert().isEqualTo("prebuilt-request")
                commands[0].header.upstreamId.assert().isEqualTo(event.id)
                commands[0].header.upstreamName.assert().isEqualTo(event.name)
            }.verifyComplete()

        sentCommands.single().assert().isSameAs(prebuilt)
    }

    private fun statelessSagaFunction(
        result: Mono<*>,
        sentCommands: MutableList<CommandMessage<*>>,
        commandMessageFactory: CommandMessageFactory = commandMessageFactory()
    ): StatelessSagaFunction {
        val commandGateway = mockk<CommandGateway> {
            every { send(any<CommandMessage<*>>()) } answers {
                sentCommands.add(firstArg())
                Mono.empty()
            }
        }
        return StatelessSagaFunction(
            delegate = StubMessageFunction(result),
            commandGateway = commandGateway,
            commandMessageFactory = commandMessageFactory,
        )
    }
}

internal fun fixtureEvent(): DomainEvent<me.ahoo.wow.tck.mock.MockAggregateCreated> {
    val upstream = MockCreateAggregate("aggregate-id", "create").toCommandMessage(
        id = "upstream-command",
        requestId = "upstream-request",
        aggregateId = "aggregate-id",
        tenantId = "tenant-id",
        spaceId = "space-id",
        header = DefaultHeader.empty().withTraceId("trace-id"),
    )
    @Suppress("UNCHECKED_CAST")
    return me.ahoo.wow.tck.mock.MockAggregateCreated("created").toDomainEventStream(
        upstream = upstream,
        aggregateVersion = 1,
    ).body.first() as DomainEvent<me.ahoo.wow.tck.mock.MockAggregateCreated>
}

internal fun commandMessageFactory(): CommandMessageFactory =
    object : CommandMessageFactory {
        override fun <TARGET : Any> create(commandBuilder: CommandBuilder): Mono<CommandMessage<TARGET>> =
            Mono.just(commandBuilder.toCommandMessage())
    }

private fun delayingCommandMessageFactory(firstBodyType: Class<*>): CommandMessageFactory =
    object : CommandMessageFactory {
        override fun <TARGET : Any> create(commandBuilder: CommandBuilder): Mono<CommandMessage<TARGET>> {
            val command = commandBuilder.toCommandMessage<TARGET>()
            val delay = if (firstBodyType.isInstance(command.body)) {
                Duration.ofMillis(25)
            } else {
                Duration.ZERO
            }
            return Mono.delay(delay).thenReturn(command)
        }
    }

internal class StubMessageFunction(
    private val result: Mono<*>
) : MessageFunction<Any, DomainEventExchange<*>, Mono<*>> {
    override val contextName: String = "fixture"
    override val name: String = "onEvent"
    override val processor: Any = "processor"
    override val supportedType: Class<*> = me.ahoo.wow.tck.mock.MockAggregateCreated::class.java
    override val supportedTopics: Set<NamedAggregate> = emptySet()
    override val functionKind: FunctionKind = FunctionKind.EVENT

    override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = null

    override fun invoke(exchange: DomainEventExchange<*>): Mono<*> = result
}
