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

package me.ahoo.wow.redis

import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.command.cartAggregateMetadata
import me.ahoo.wow.command.createCommandMessageForNewAggregate
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.command.wait.ProcessedNotifierFilter
import me.ahoo.wow.command.wait.SimpleWaitStrategyRegistrar
import me.ahoo.wow.command.wait.stage.WaitingForStage
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.eventsourcing.EventSourcingStateAggregateRepository
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.eventsourcing.state.SendStateEventFilter
import me.ahoo.wow.exception.WowException
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.modeling.command.RetryableAggregateProcessorFactory
import me.ahoo.wow.modeling.command.SimpleCommandAggregateFactory
import me.ahoo.wow.modeling.command.dispatcher.AggregateProcessorFilter
import me.ahoo.wow.modeling.command.dispatcher.CommandHandler
import me.ahoo.wow.modeling.command.dispatcher.DefaultCommandHandler
import me.ahoo.wow.modeling.command.dispatcher.SendDomainEventStreamFilter
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.redis.eventsourcing.RedisEventStore
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Threads
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole

@Warmup(iterations = 1)
@Measurement(iterations = 2)
@Fork(value = 2)
@Threads(5)
@State(Scope.Benchmark)
open class RedisCommandProcessingPipelineBenchmark {
    private lateinit var redis: RedisBenchmarkFixture
    private lateinit var aggregateOnlyHandler: CommandHandler
    private lateinit var aggregateDomainAndStateEventHandler: CommandHandler
    private lateinit var aggregateDomainStateAndProcessedNotifierHandler: CommandHandler

    @Setup
    fun setup() {
        redis = RedisBenchmarkFixture()
        val eventStore = RedisEventStore(redis.redisTemplate)
        val commandWaitNotifier = LocalCommandWaitNotifier(SimpleWaitStrategyRegistrar)
        val aggregateProcessorFilter = AggregateProcessorFilter(
            serviceProvider = SimpleServiceProvider(),
            aggregateProcessorFactory = createAggregateProcessorFactory(eventStore),
        )
        aggregateOnlyHandler = DefaultCommandHandler(
            FilterChainBuilder<ServerCommandExchange<*>>()
                .addFilter(aggregateProcessorFilter)
                .build()
        )
        aggregateDomainAndStateEventHandler = DefaultCommandHandler(
            FilterChainBuilder<ServerCommandExchange<*>>()
                .addFilter(aggregateProcessorFilter)
                .addFilter(SendDomainEventStreamFilter(InMemoryDomainEventBus()))
                .addFilter(SendStateEventFilter(InMemoryStateEventBus()))
                .build()
        )
        aggregateDomainStateAndProcessedNotifierHandler = DefaultCommandHandler(
            FilterChainBuilder<ServerCommandExchange<*>>()
                .addFilter(aggregateProcessorFilter)
                .addFilter(SendDomainEventStreamFilter(InMemoryDomainEventBus()))
                .addFilter(SendStateEventFilter(InMemoryStateEventBus()))
                .addFilter(ProcessedNotifierFilter(commandWaitNotifier))
                .build()
        )
    }

    @TearDown
    fun tearDown() {
        redis.close()
    }

    private fun createAggregateProcessorFactory(eventStore: EventStore): RetryableAggregateProcessorFactory {
        val stateAggregateRepository = EventSourcingStateAggregateRepository(
            ConstructorStateAggregateFactory,
            InMemorySnapshotRepository(),
            eventStore,
        )
        return RetryableAggregateProcessorFactory(
            ConstructorStateAggregateFactory,
            stateAggregateRepository,
            SimpleCommandAggregateFactory(eventStore),
        )
    }

    private fun createServerExchange(): ServerCommandExchange<*> {
        val exchange = SimpleServerCommandExchange(createCommandMessageForNewAggregate())
        exchange.setAggregateMetadata(cartAggregateMetadata)
        return exchange
    }

    private fun run(blackHole: Blackhole, block: () -> Any?) {
        try {
            blackHole.consume(block())
        } catch (wowException: WowException) {
            blackHole.consume(wowException)
        }
    }

    @Benchmark
    fun handleAggregateOnly(blackHole: Blackhole) {
        run(blackHole) {
            aggregateOnlyHandler.handle(createServerExchange()).block()
        }
    }

    @Benchmark
    fun handleAggregateDomainAndStateEvent(blackHole: Blackhole) {
        run(blackHole) {
            aggregateDomainAndStateEventHandler.handle(createServerExchange()).block()
        }
    }

    @Benchmark
    fun handleAggregateDomainStateAndProcessedNotifierWithoutWait(blackHole: Blackhole) {
        run(blackHole) {
            aggregateDomainStateAndProcessedNotifierHandler.handle(createServerExchange()).block()
        }
    }

    @Benchmark
    fun handleAggregateDomainStateAndProcessedNotifierWithLocalWait(blackHole: Blackhole) {
        run(blackHole) {
            val commandMessage = createCommandMessageForNewAggregate()
            val waitStrategy = WaitingForStage.processed(commandMessage.commandId)
            waitStrategy.propagate("", commandMessage.header)
            SimpleWaitStrategyRegistrar.register(waitStrategy)
            waitStrategy.onFinally {
                SimpleWaitStrategyRegistrar.unregister(waitStrategy.waitCommandId)
            }
            val exchange = SimpleServerCommandExchange(commandMessage)
            exchange.setAggregateMetadata(cartAggregateMetadata)
            aggregateDomainStateAndProcessedNotifierHandler
                .handle(exchange)
                .then(waitStrategy.waitingLast())
                .block()
        }
    }
}
