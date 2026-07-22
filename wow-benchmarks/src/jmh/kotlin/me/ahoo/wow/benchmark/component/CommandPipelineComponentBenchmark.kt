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

package me.ahoo.wow.benchmark.component

import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkCommands
import me.ahoo.wow.benchmark.fixture.BenchmarkIds
import me.ahoo.wow.benchmark.scenario.CommandPipelineScenario
import me.ahoo.wow.command.wait.CommandWait
import me.ahoo.wow.command.wait.DefaultWaitCoordinator
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.command.wait.SimpleCommandWaitEndpoint
import me.ahoo.wow.command.wait.WaitCoordinator
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.eventsourcing.NoopEventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.messaging.MessageSubscription
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import reactor.core.Disposable

@State(Scope.Thread)
open class CommandPipelineComponentBenchmark {
    private lateinit var commandPipelineScenario: CommandPipelineScenario
    private lateinit var waitCoordinator: WaitCoordinator
    private lateinit var domainEventBus: InMemoryDomainEventBus
    private lateinit var stateEventBus: InMemoryStateEventBus
    private lateinit var domainEventSubscription: Disposable
    private lateinit var stateEventSubscription: Disposable

    @Setup
    fun setup() {
        BenchmarkIds.installDeterministicGlobalIdGenerator()
        val aggregateMetadata = BenchmarkAggregates.cartMetadata
        val eventStore = NoopEventStore
        val snapshotRepository = InMemorySnapshotRepository()
        domainEventBus = InMemoryDomainEventBus()
        stateEventBus = InMemoryStateEventBus()
        val subscription = MessageSubscription(BenchmarkAggregates.namedAggregate)
        domainEventSubscription = domainEventBus.receive(subscription).subscribe()
        stateEventSubscription = stateEventBus.receive(subscription).subscribe()
        waitCoordinator = DefaultWaitCoordinator()
        val commandWaitNotifier = LocalCommandWaitNotifier(waitCoordinator)
        commandPipelineScenario = CommandPipelineScenario.create(
            eventStore = eventStore,
            snapshotRepository = snapshotRepository,
            domainEventBus = domainEventBus,
            stateEventBus = stateEventBus,
            commandWaitNotifier = commandWaitNotifier,
            aggregateMetadata = aggregateMetadata,
        )
    }

    @TearDown
    fun tearDown() {
        stateEventSubscription.dispose()
        domainEventSubscription.dispose()
        stateEventBus.close()
        domainEventBus.close()
    }

    @Benchmark
    fun handleAggregateOnly(blackhole: Blackhole) {
        val result = commandPipelineScenario.aggregateOnlyHandler
            .handle(commandPipelineScenario.createServerExchange())
            .block()
        blackhole.consume(result)
    }

    @Benchmark
    fun handleAggregateWithoutRetry(blackhole: Blackhole) {
        val result = commandPipelineScenario.aggregateOnlyWithoutRetryHandler
            .handle(commandPipelineScenario.createServerExchange())
            .block()
        blackhole.consume(result)
    }

    @Benchmark
    fun handleAggregateAndSendDomainEvent(blackhole: Blackhole) {
        val result = commandPipelineScenario.aggregateAndDomainEventHandler
            .handle(commandPipelineScenario.createServerExchange())
            .block()
        blackhole.consume(result)
    }

    @Benchmark
    fun handleAggregateAndSendDomainStateEvents(blackhole: Blackhole) {
        val result = commandPipelineScenario.aggregateDomainAndStateEventHandler
            .handle(commandPipelineScenario.createServerExchange())
            .block()
        blackhole.consume(result)
    }

    @Benchmark
    fun handleAggregateAndNotifyProcessedWithoutWait(blackhole: Blackhole) {
        val result = commandPipelineScenario.aggregateDomainStateAndProcessedNotifierHandler
            .handle(commandPipelineScenario.createServerExchange())
            .block()
        blackhole.consume(result)
    }

    @Benchmark
    fun handleAggregateAndNotifyProcessedWithLocalWait(blackhole: Blackhole) {
        val commandMessage = BenchmarkCommands.commandPathAddCartItem()
        val waitPlan = CommandWait.processed(commandMessage.commandId)
        val handle = waitCoordinator.createLast(waitPlan)
        waitPlan.propagate(SimpleCommandWaitEndpoint(""), commandMessage.header)
        val exchange = commandPipelineScenario.createServerExchange(commandMessage)
        val result = commandPipelineScenario.aggregateDomainStateAndProcessedNotifierHandler
            .handle(exchange)
            .then(handle.await())
            .block()
        blackhole.consume(result)
    }
}
