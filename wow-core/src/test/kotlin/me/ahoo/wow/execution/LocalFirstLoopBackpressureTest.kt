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

package me.ahoo.wow.execution

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.DistributedCommandBus
import me.ahoo.wow.command.LocalFirstCommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.event.DistributedDomainEventBus
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.LocalFirstDomainEventBus
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.messaging.MessageReceiver
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.modeling.command.dispatcher.CommandDispatcher
import me.ahoo.wow.modeling.command.dispatcher.CommandHandler
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.runtime.WowRuntime
import me.ahoo.wow.saga.stateless.StatelessSagaDispatcher
import me.ahoo.wow.saga.stateless.StatelessSagaFunctionRegistrar
import me.ahoo.wow.saga.stateless.StatelessSagaHandler
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression for the X7 review: with local-first buses, a command handler waits on its event send and a saga on its
 * command send. If a local-first send completed only when the target dispatcher pulled the message (a demand slot),
 * a burst of command → event → saga → command chains larger than [KeyedExecutor.maxInFlight] would deadlock: every
 * slot held by a handler waiting for a slot. X5 completes a local send on hand-off to the local sink, so the loop
 * drains with `maxInFlight = 4` and 100 concurrent chains.
 */
class LocalFirstLoopBackpressureTest {

    @Test
    fun `command to event to saga to command chains complete with a small in-flight bound`() {
        val chains = 100
        val hops = 3
        val commandBus = LocalFirstCommandBus(distributedBus = distributedCommandBus())
        val domainEventBus = LocalFirstDomainEventBus(distributedBus = distributedDomainEventBus())
        val completed = CountDownLatch(chains)
        val commandsHandled = AtomicInteger()
        val sagaHops = AtomicInteger()

        val commandDispatcher = CommandDispatcher(
            name = "loop.CommandDispatcher",
            namedAggregates = setOf(MOCK_AGGREGATE_METADATA.materialize()),
            commandBus = commandBus,
            commandHandler = object : CommandHandler {
                // Like the real handler: the command completes once its event stream is sent.
                override fun handle(
                    exchange: ServerCommandExchange<*>,
                    aggregateMetadata: AggregateMetadata<*, *>,
                ): Mono<Void> {
                    commandsHandled.incrementAndGet()
                    val command = exchange.message
                    val body = command.body as MockCreateAggregate
                    val stream = MockAggregateCreated(body.data).toDomainEventStream(
                        upstream = command,
                        aggregateVersion = 1,
                    )
                    return domainEventBus.send(stream).then(exchange.acknowledge())
                }
            },
        )
        val sagaRegistrar = StatelessSagaFunctionRegistrar(mockk(), mockk()).apply {
            register(ChainSagaFunction(MOCK_AGGREGATE_METADATA.materialize()))
        }
        val sagaDispatcher = StatelessSagaDispatcher(
            name = "loop.StatelessSagaDispatcher",
            domainEventBus = domainEventBus,
            stateEventBus = InMemoryStateEventBus(),
            functionRegistrar = sagaRegistrar,
            // Like the real saga: the event completes once the next command is sent.
            eventHandler = object : StatelessSagaHandler {
                override fun handle(context: DomainEventExchange<*>): Mono<Void> {
                    val event = context.message.body as MockAggregateCreated
                    val (chain, hop) = event.data.split(":").map(String::toInt)
                    if (hop + 1 == hops) {
                        completed.countDown()
                        return Mono.empty()
                    }
                    sagaHops.incrementAndGet()
                    return commandBus.send(command(chain, hop + 1))
                }
            },
        )
        val runtime = WowRuntime(
            components = listOf(commandDispatcher, sagaDispatcher),
            shutdownTimeout = Duration.ofSeconds(10),
            shutdownQuietPeriod = Duration.ZERO,
            keyedExecutor = KeyedExecutor(workers = 2, maxInFlight = 4, name = "loop-dispatch"),
        )
        runtime.start().block()
        try {
            Flux.range(0, chains)
                .flatMap({ commandBus.send(command(it, 0)) }, chains)
                .then()
                .block(Duration.ofSeconds(30))

            completed.await(30, TimeUnit.SECONDS).assert()
                .describedAs("chains completed: ${chains - completed.count}/$chains").isTrue()
            commandsHandled.get().assert().isEqualTo(chains * hops)
            sagaHops.get().assert().isEqualTo(chains * (hops - 1))
        } finally {
            runtime.stopGracefully().block(Duration.ofSeconds(15))
            commandBus.close()
            domainEventBus.close()
        }
    }

    private fun command(chain: Int, hop: Int): CommandMessage<MockCreateAggregate> =
        MockCreateAggregate(id = "chain-$chain", data = "$chain:$hop")
            .toCommandMessage(aggregateId = "chain-$chain", namedAggregate = MOCK_AGGREGATE_METADATA.namedAggregate)

    private fun distributedCommandBus(): DistributedCommandBus = mockk {
        every { send(any()) } returns Mono.empty()
        every { receiver(any()) } answers { MessageReceiver(Flux.never()) }
        every { close() } returns Unit
    }

    private fun distributedDomainEventBus(): DistributedDomainEventBus = mockk {
        every { send(any()) } returns Mono.empty()
        every { receiver(any()) } answers { MessageReceiver(Flux.never()) }
        every { close() } returns Unit
    }

    private class ChainSagaFunction(
        namedAggregate: NamedAggregate,
    ) : MessageFunction<Any, DomainEventExchange<*>, Mono<*>> {
        override val name: String = "onCreated"
        override val functionKind: FunctionKind = FunctionKind.EVENT
        override val contextName: String = namedAggregate.contextName
        override val supportedType: Class<*> = MockAggregateCreated::class.java
        override val supportedTopics: Set<NamedAggregate> = setOf(namedAggregate)
        override val processor: Any = this

        override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = null

        override fun invoke(exchange: DomainEventExchange<*>): Mono<*> = Mono.empty<Void>()
    }
}
