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

package me.ahoo.wow.hotpath

import me.ahoo.wow.BenchmarkAggregateSchedulerSupplier
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.DefaultCommandGateway
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.createBloomFilterIdempotencyChecker
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.command.wait.ProcessedNotifierFilter
import me.ahoo.wow.command.wait.SimpleCommandWaitEndpoint
import me.ahoo.wow.command.wait.SimpleWaitStrategyRegistrar
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.eventsourcing.EventSourcingStateAggregateRepository
import me.ahoo.wow.eventsourcing.NoopEventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.eventsourcing.state.SendStateEventFilter
import me.ahoo.wow.exception.WowException
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.infra.idempotency.DefaultAggregateIdempotencyCheckerProvider
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.modeling.command.RetryableAggregateProcessorFactory
import me.ahoo.wow.modeling.command.SimpleCommandAggregateFactory
import me.ahoo.wow.modeling.command.dispatcher.AggregateProcessorFilter
import me.ahoo.wow.modeling.command.dispatcher.CommandDispatcher
import me.ahoo.wow.modeling.command.dispatcher.DefaultCommandHandler
import me.ahoo.wow.modeling.command.dispatcher.SendDomainEventStreamFilter
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateRepository
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
open class CommandProcessingPipelineBenchmark {
    private lateinit var commandGateway: CommandGateway
    private lateinit var commandDispatcher: CommandDispatcher

    @Setup
    fun setup() {
        val commandWaitNotifier = LocalCommandWaitNotifier(SimpleWaitStrategyRegistrar)
        val commandBus = InMemoryCommandBus()
        val domainEventBus: DomainEventBus = InMemoryDomainEventBus()
        val stateEventBus = InMemoryStateEventBus()
        val eventStore = NoopEventStore
        val snapshotRepository = InMemorySnapshotRepository()

        commandGateway = DefaultCommandGateway(
            commandWaitEndpoint = SimpleCommandWaitEndpoint(""),
            commandBus = commandBus,
            validator = NoOpValidator,
            idempotencyCheckerProvider = DefaultAggregateIdempotencyCheckerProvider {
                createBloomFilterIdempotencyChecker()
            },
            waitStrategyRegistrar = SimpleWaitStrategyRegistrar,
            commandWaitNotifier = commandWaitNotifier,
        )

        val stateAggregateRepository: StateAggregateRepository =
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
        commandDispatcher = CommandDispatcher(
            namedAggregates = setOf(HotPathFixture.namedAggregate),
            commandBus = commandGateway,
            commandHandler = DefaultCommandHandler(chain),
            schedulerSupplier = BenchmarkAggregateSchedulerSupplier(),
        )
        commandDispatcher.start()
    }

    @TearDown
    fun tearDown() {
        commandDispatcher.stop()
        commandGateway.close()
    }

    @Benchmark
    fun sendAndWaitForProcessed(blackhole: Blackhole) {
        try {
            val result = commandGateway.sendAndWaitForProcessed(
                HotPathFixture.createCommandMessage(),
            ).block()
            blackhole.consume(result)
        } catch (e: WowException) {
            blackhole.consume(e)
        }
    }

    @Benchmark
    fun sendFireAndForget(blackhole: Blackhole) {
        try {
            val result = commandGateway.send(
                HotPathFixture.createCommandMessage(),
            ).block()
            blackhole.consume(result)
        } catch (e: WowException) {
            blackhole.consume(e)
        }
    }
}
