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

package me.ahoo.wow.benchmark.scenario

import jakarta.validation.Validator
import me.ahoo.wow.BenchmarkAggregateSchedulerSupplier
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkIdempotency
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.wait.CommandWaitEndpoint
import me.ahoo.wow.command.wait.CommandWaitNotifier
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.command.wait.ProcessedNotifierFilter
import me.ahoo.wow.command.wait.SimpleCommandWaitEndpoint
import me.ahoo.wow.command.wait.SimpleWaitStrategyRegistrar
import me.ahoo.wow.command.wait.WaitStrategyRegistrar
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.eventsourcing.EventSourcingStateAggregateRepository
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.eventsourcing.state.SendStateEventFilter
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.infra.idempotency.AggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.DefaultAggregateIdempotencyCheckerProvider
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.modeling.command.RetryableAggregateProcessorFactory
import me.ahoo.wow.modeling.command.SimpleCommandAggregateFactory
import me.ahoo.wow.modeling.command.dispatcher.AggregateProcessorFilter
import me.ahoo.wow.modeling.command.dispatcher.CommandDispatcher
import me.ahoo.wow.modeling.command.dispatcher.DefaultCommandHandler
import me.ahoo.wow.modeling.command.dispatcher.SendDomainEventStreamFilter
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.scheduler.AggregateSchedulerSupplier
import me.ahoo.wow.test.validation.TestValidator

class CommandDispatcherScenario private constructor(
    val gatewayScenario: CommandGatewayScenario,
    val commandDispatcher: CommandDispatcher,
) : AutoCloseable {
    val commandGateway: CommandGateway
        get() = gatewayScenario.commandGateway

    override fun close() {
        commandDispatcher.stop()
        gatewayScenario.close()
    }

    companion object {
        fun create(
            commandBus: CommandBus = InMemoryCommandBus(),
            eventStore: EventStore = InMemoryEventStore(),
            snapshotRepository: SnapshotRepository = InMemorySnapshotRepository(),
            domainEventBus: DomainEventBus = InMemoryDomainEventBus(),
            stateEventBus: StateEventBus = InMemoryStateEventBus(),
            schedulerSupplier: AggregateSchedulerSupplier = BenchmarkAggregateSchedulerSupplier(),
            idempotencyCheckerProvider: AggregateIdempotencyCheckerProvider =
                DefaultAggregateIdempotencyCheckerProvider {
                    BenchmarkIdempotency.bloomFilterChecker()
                },
            validator: Validator = TestValidator,
            waitStrategyRegistrar: WaitStrategyRegistrar = SimpleWaitStrategyRegistrar,
            commandWaitNotifier: CommandWaitNotifier = LocalCommandWaitNotifier(waitStrategyRegistrar),
            commandWaitEndpoint: CommandWaitEndpoint = SimpleCommandWaitEndpoint(""),
            namedAggregate: NamedAggregate = BenchmarkAggregates.cartMetadata.namedAggregate.materialize(),
        ): CommandDispatcherScenario {
            val gatewayScenario = CommandGatewayScenario.create(
                commandBus = commandBus,
                validator = validator,
                idempotencyCheckerProvider = idempotencyCheckerProvider,
                waitStrategyRegistrar = waitStrategyRegistrar,
                commandWaitNotifier = commandWaitNotifier,
                commandWaitEndpoint = commandWaitEndpoint,
                subscribeToCart = false,
            )
            val stateAggregateRepository =
                EventSourcingStateAggregateRepository(
                    ConstructorStateAggregateFactory,
                    snapshotRepository,
                    eventStore,
                )
            val aggregateProcessorFactory = RetryableAggregateProcessorFactory(
                ConstructorStateAggregateFactory,
                stateAggregateRepository,
                SimpleCommandAggregateFactory(eventStore),
            )
            val chain = FilterChainBuilder<ServerCommandExchange<*>>()
                .addFilter(AggregateProcessorFilter(SimpleServiceProvider(), aggregateProcessorFactory))
                .addFilter(SendDomainEventStreamFilter(domainEventBus))
                .addFilter(SendStateEventFilter(stateEventBus))
                .addFilter(ProcessedNotifierFilter(commandWaitNotifier))
                .build()
            val commandDispatcher = CommandDispatcher(
                namedAggregates = setOf(namedAggregate),
                commandBus = gatewayScenario.commandGateway,
                commandHandler = DefaultCommandHandler(chain),
                schedulerSupplier = schedulerSupplier,
            )
            commandDispatcher.start()
            return CommandDispatcherScenario(
                gatewayScenario = gatewayScenario,
                commandDispatcher = commandDispatcher,
            )
        }
    }
}
