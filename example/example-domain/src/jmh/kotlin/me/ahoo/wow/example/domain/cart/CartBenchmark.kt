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

package me.ahoo.wow.example.domain.cart

import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.DefaultCommandGateway
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.factory.CommandBuilder.Companion.commandBuilder
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.command.wait.CommandWaitNotifier
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.command.wait.ProcessedNotifierFilter
import me.ahoo.wow.command.wait.SimpleCommandWaitEndpoint
import me.ahoo.wow.command.wait.SimpleWaitStrategyRegistrar
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.eventsourcing.EventSourcingStateAggregateRepository
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.infra.idempotency.DefaultAggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.NoOpIdempotencyChecker
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.modeling.command.AggregateProcessorFactory
import me.ahoo.wow.modeling.command.RetryableAggregateProcessorFactory
import me.ahoo.wow.modeling.command.SimpleCommandAggregateFactory
import me.ahoo.wow.modeling.command.dispatcher.AggregateProcessorFilter
import me.ahoo.wow.modeling.command.dispatcher.CommandDispatcher
import me.ahoo.wow.modeling.command.dispatcher.DefaultCommandHandler
import me.ahoo.wow.modeling.command.dispatcher.SendDomainEventStreamFilter
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.test.validation.TestValidator
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Setup

class CartBenchmark {
    private lateinit var commandWaitNotifier: CommandWaitNotifier
    private lateinit var commandGateway: CommandGateway
    private lateinit var eventStore: EventStore
    private lateinit var snapshotRepository: SnapshotRepository
    private lateinit var stateAggregateRepository: StateAggregateRepository
    private lateinit var aggregateProcessorFactory: AggregateProcessorFactory
    private lateinit var commandDispatcher: CommandDispatcher

    @Setup
    fun setup() {
        commandWaitNotifier = LocalCommandWaitNotifier(SimpleWaitStrategyRegistrar)
        commandGateway = DefaultCommandGateway(
            commandWaitEndpoint = SimpleCommandWaitEndpoint(""),
            commandBus = InMemoryCommandBus(),
            validator = TestValidator,
            idempotencyCheckerProvider = DefaultAggregateIdempotencyCheckerProvider({
                NoOpIdempotencyChecker
            }),
            waitStrategyRegistrar = SimpleWaitStrategyRegistrar,
            commandWaitNotifier = commandWaitNotifier
        )
        eventStore = InMemoryEventStore()
        snapshotRepository = InMemorySnapshotRepository()
        stateAggregateRepository =
            EventSourcingStateAggregateRepository(ConstructorStateAggregateFactory, snapshotRepository, eventStore)
        aggregateProcessorFactory = RetryableAggregateProcessorFactory(
            ConstructorStateAggregateFactory,
            stateAggregateRepository,
            SimpleCommandAggregateFactory(eventStore)
        )

        val chain = FilterChainBuilder<ServerCommandExchange<*>>()
            .addFilter(AggregateProcessorFilter(SimpleServiceProvider(), aggregateProcessorFactory))
            .addFilter(SendDomainEventStreamFilter(InMemoryDomainEventBus()))
            .addFilter(ProcessedNotifierFilter(commandWaitNotifier))
            .build()
        commandDispatcher = CommandDispatcher(
            commandBus = commandGateway,
            commandHandler = DefaultCommandHandler(chain)
        )
        commandDispatcher.start()
    }

    @Benchmark
    fun sendAndWaitForSent() {
        val command = AddCartItem(productId = generateGlobalId(), quantity = 1)
            .commandBuilder()
            .aggregateId(generateGlobalId())
            .toCommandMessage<AddCartItem>()
        commandGateway.sendAndWaitForSent(command).block()
    }

    @Benchmark
    fun sendAndWaitForProcessed() {
        val command = AddCartItem(productId = generateGlobalId(), quantity = 1)
            .commandBuilder()
            .aggregateId(generateGlobalId())
            .toCommandMessage<AddCartItem>()
        commandGateway.sendAndWaitForProcessed(command).block()
    }
}