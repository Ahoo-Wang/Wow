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

package me.ahoo.wow.commandpath

import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkCommands
import me.ahoo.wow.benchmark.fixture.BenchmarkIds
import me.ahoo.wow.benchmark.fixture.BenchmarkIdempotency
import me.ahoo.wow.benchmark.scenario.CommandDispatcherScenario
import me.ahoo.wow.benchmark.scenario.CommandPipelineScenario
import me.ahoo.wow.benchmark.scenario.consumeWowResult
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.command.wait.SimpleWaitStrategyRegistrar
import me.ahoo.wow.command.wait.stage.WaitingForStage
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.eventsourcing.NoopEventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.infra.idempotency.DefaultAggregateIdempotencyCheckerProvider
import me.ahoo.wow.modeling.materialize
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
open class CommandPipelineBenchmark {
    private lateinit var commandDispatcherScenario: CommandDispatcherScenario
    private lateinit var commandPipelineScenario: CommandPipelineScenario

    @Setup
    fun setup() {
        BenchmarkIds.installDeterministicGlobalIdGenerator()
        val aggregateMetadata = BenchmarkAggregates.cartMetadata
        val eventStore = NoopEventStore
        val snapshotRepository = InMemorySnapshotRepository()
        val domainEventBus = InMemoryDomainEventBus()
        val stateEventBus = InMemoryStateEventBus()
        val commandWaitNotifier = LocalCommandWaitNotifier(SimpleWaitStrategyRegistrar)
        commandDispatcherScenario = CommandDispatcherScenario.create(
            eventStore = eventStore,
            snapshotRepository = snapshotRepository,
            domainEventBus = domainEventBus,
            stateEventBus = stateEventBus,
            validator = NoOpValidator,
            commandWaitNotifier = commandWaitNotifier,
            idempotencyCheckerProvider = DefaultAggregateIdempotencyCheckerProvider {
                BenchmarkIdempotency.bloomFilterChecker()
            },
            namedAggregate = aggregateMetadata.namedAggregate.materialize(),
        )
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
        commandDispatcherScenario.close()
    }

    @Benchmark
    fun sendCommandAndWaitForProcessed(blackhole: Blackhole) {
        blackhole.consumeWowResult {
            commandDispatcherScenario.commandGateway
                .sendAndWaitForProcessed(BenchmarkCommands.commandPathAddCartItem())
                .block()
        }
    }

    @Benchmark
    fun handleAggregateOnly(blackhole: Blackhole) {
        blackhole.consumeWowResult {
            commandPipelineScenario.aggregateOnlyHandler
                .handle(commandPipelineScenario.createServerExchange())
                .block()
        }
    }

    @Benchmark
    fun handleAggregateWithoutRetry(blackhole: Blackhole) {
        blackhole.consumeWowResult {
            commandPipelineScenario.aggregateOnlyWithoutRetryHandler
                .handle(commandPipelineScenario.createServerExchange())
                .block()
        }
    }

    @Benchmark
    fun handleAggregateAndSendDomainEvent(blackhole: Blackhole) {
        blackhole.consumeWowResult {
            commandPipelineScenario.aggregateAndDomainEventHandler
                .handle(commandPipelineScenario.createServerExchange())
                .block()
        }
    }

    @Benchmark
    fun handleAggregateAndSendDomainStateEvents(blackhole: Blackhole) {
        blackhole.consumeWowResult {
            commandPipelineScenario.aggregateDomainAndStateEventHandler
                .handle(commandPipelineScenario.createServerExchange())
                .block()
        }
    }

    @Benchmark
    fun handleAggregateAndNotifyProcessedWithoutWait(blackhole: Blackhole) {
        blackhole.consumeWowResult {
            commandPipelineScenario.aggregateDomainStateAndProcessedNotifierHandler
                .handle(commandPipelineScenario.createServerExchange())
                .block()
        }
    }

    @Benchmark
    fun handleAggregateAndNotifyProcessedWithLocalWait(blackhole: Blackhole) {
        blackhole.consumeWowResult {
            val commandMessage = BenchmarkCommands.commandPathAddCartItem()
            val waitStrategy = WaitingForStage.processed(commandMessage.commandId)
            waitStrategy.propagate("", commandMessage.header)
            SimpleWaitStrategyRegistrar.register(waitStrategy)
            waitStrategy.onFinally {
                SimpleWaitStrategyRegistrar.unregister(waitStrategy.waitCommandId)
            }
            val exchange = commandPipelineScenario.createServerExchange(commandMessage)
            commandPipelineScenario.aggregateDomainStateAndProcessedNotifierHandler
                .handle(exchange)
                .then(waitStrategy.waitingLast())
                .block()
        }
    }

    @Benchmark
    fun sendCommandFireAndForget(blackhole: Blackhole) {
        blackhole.consumeWowResult {
            commandDispatcherScenario.commandGateway
                .send(BenchmarkCommands.commandPathAddCartItem())
                .block()
        }
    }
}
