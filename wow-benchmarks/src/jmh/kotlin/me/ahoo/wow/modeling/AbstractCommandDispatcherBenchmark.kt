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

package me.ahoo.wow.modeling

import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnels
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.DefaultCommandGateway
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.createCommandMessage
import me.ahoo.wow.command.wait.CommandWaitNotifier
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.command.wait.ProcessedNotifierFilter
import me.ahoo.wow.command.wait.SimpleCommandWaitEndpoint
import me.ahoo.wow.command.wait.SimpleWaitStrategyRegistrar
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.eventsourcing.EventSourcingStateAggregateRepository
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.eventsourcing.state.SendStateEventFilter
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.infra.idempotency.AggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.BloomFilterIdempotencyChecker
import me.ahoo.wow.infra.idempotency.DefaultAggregateIdempotencyCheckerProvider
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
import org.openjdk.jmh.infra.Blackhole
import java.time.Duration

abstract class AbstractCommandDispatcherBenchmark {
    private lateinit var commandWaitNotifier: CommandWaitNotifier
    private lateinit var commandGateway: CommandGateway
    private lateinit var eventStore: EventStore
    private lateinit var snapshotRepository: SnapshotRepository
    private lateinit var stateAggregateRepository: StateAggregateRepository
    private lateinit var aggregateProcessorFactory: AggregateProcessorFactory
    private lateinit var commandDispatcher: CommandDispatcher

    open fun setup() {
        commandWaitNotifier = LocalCommandWaitNotifier(SimpleWaitStrategyRegistrar)
        commandGateway = DefaultCommandGateway(
            commandWaitEndpoint = SimpleCommandWaitEndpoint(""),
            commandBus = createCommandBus(),
            validator = TestValidator,
            idempotencyCheckerProvider = createIdempotencyCheckerProvider(),
            waitStrategyRegistrar = SimpleWaitStrategyRegistrar,
            commandWaitNotifier = commandWaitNotifier
        )

        eventStore = createEventStore()
        snapshotRepository = createSnapshotRepository()
        stateAggregateRepository =
            EventSourcingStateAggregateRepository(
                ConstructorStateAggregateFactory,
                snapshotRepository,
                eventStore
            )
        aggregateProcessorFactory = RetryableAggregateProcessorFactory(
            ConstructorStateAggregateFactory,
            stateAggregateRepository,
            SimpleCommandAggregateFactory(eventStore)
        )

        val chain = FilterChainBuilder<ServerCommandExchange<*>>()
            .addFilter(AggregateProcessorFilter(SimpleServiceProvider(), aggregateProcessorFactory))
            .addFilter(SendDomainEventStreamFilter(createDomainEventBus()))
            .addFilter(SendStateEventFilter(createStateEventBus()))
            .addFilter(ProcessedNotifierFilter(commandWaitNotifier))
            .build()
        commandDispatcher = CommandDispatcher(
            commandBus = commandGateway,
            commandHandler = DefaultCommandHandler(chain)
        )
        commandDispatcher.start()
    }

    open fun createCommandBus(): CommandBus {
        return InMemoryCommandBus()
    }

    open fun createDomainEventBus(): DomainEventBus {
        return InMemoryDomainEventBus()
    }

    open fun createStateEventBus(): StateEventBus {
        return InMemoryStateEventBus()
    }

    abstract fun createEventStore(): EventStore

    open fun createIdempotencyCheckerProvider(): AggregateIdempotencyCheckerProvider {
        return DefaultAggregateIdempotencyCheckerProvider {
            BloomFilterIdempotencyChecker(Duration.ofMinutes(1)) {
                BloomFilter.create(
                    Funnels.stringFunnel(Charsets.UTF_8),
                    1_000_000,
                    0.00001,
                )
            }
        }
    }

    open fun createSnapshotRepository(): SnapshotRepository {
        return InMemorySnapshotRepository()
    }

    open fun destroy() {
        commandDispatcher.stop()
    }


    open fun send(blackHole: Blackhole) {
        val result = commandGateway.send(createCommandMessage()).block()
        blackHole.consume(result)
    }

    open fun sendAndWaitForSent(blackHole: Blackhole) {
        val result = commandGateway.sendAndWaitForSent(createCommandMessage()).block()
        blackHole.consume(result)
    }

    open fun sendAndWaitForProcessed(blackHole: Blackhole) {
        val result = commandGateway.sendAndWaitForProcessed(createCommandMessage()).block()
        blackHole.consume(result)
    }
}