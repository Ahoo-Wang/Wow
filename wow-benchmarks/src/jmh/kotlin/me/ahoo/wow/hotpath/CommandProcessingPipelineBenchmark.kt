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
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedTypedAggregate
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.DefaultCommandGateway
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.command.createBloomFilterIdempotencyChecker
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.command.wait.ProcessedNotifierFilter
import me.ahoo.wow.command.wait.SimpleCommandWaitEndpoint
import me.ahoo.wow.command.wait.SimpleWaitStrategyRegistrar
import me.ahoo.wow.command.wait.stage.WaitingForStage
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.DomainEventStream
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
import me.ahoo.wow.modeling.command.AggregateProcessor
import me.ahoo.wow.modeling.command.AggregateProcessorFactory
import me.ahoo.wow.modeling.command.CommandAggregateFactory
import me.ahoo.wow.modeling.command.dispatcher.AggregateProcessorFilter
import me.ahoo.wow.modeling.command.dispatcher.CommandDispatcher
import me.ahoo.wow.modeling.command.dispatcher.CommandHandler
import me.ahoo.wow.modeling.command.dispatcher.DefaultCommandHandler
import me.ahoo.wow.modeling.command.dispatcher.SendDomainEventStreamFilter
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateRepository
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import reactor.core.publisher.Mono

@State(Scope.Benchmark)
open class CommandProcessingPipelineBenchmark {
    private lateinit var commandGateway: CommandGateway
    private lateinit var commandDispatcher: CommandDispatcher
    private lateinit var aggregateOnlyHandler: CommandHandler
    private lateinit var aggregateOnlyWithoutRetryHandler: CommandHandler
    private lateinit var aggregateAndDomainEventHandler: CommandHandler
    private lateinit var aggregateDomainAndStateEventHandler: CommandHandler
    private lateinit var aggregateDomainStateAndProcessedNotifierHandler: CommandHandler

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
        val directAggregateProcessorFactory = DirectAggregateProcessorFactory(
            ConstructorStateAggregateFactory,
            stateAggregateRepository,
            SimpleCommandAggregateFactory(eventStore),
        )
        aggregateOnlyHandler = DefaultCommandHandler(
            FilterChainBuilder<ServerCommandExchange<*>>()
                .addFilter(AggregateProcessorFilter(SimpleServiceProvider(), aggregateProcessorFactory))
                .build()
        )
        aggregateOnlyWithoutRetryHandler = DefaultCommandHandler(
            FilterChainBuilder<ServerCommandExchange<*>>()
                .addFilter(AggregateProcessorFilter(SimpleServiceProvider(), directAggregateProcessorFactory))
                .build()
        )
        aggregateAndDomainEventHandler = DefaultCommandHandler(
            FilterChainBuilder<ServerCommandExchange<*>>()
                .addFilter(AggregateProcessorFilter(SimpleServiceProvider(), aggregateProcessorFactory))
                .addFilter(SendDomainEventStreamFilter(domainEventBus))
                .build()
        )
        aggregateDomainAndStateEventHandler = DefaultCommandHandler(
            FilterChainBuilder<ServerCommandExchange<*>>()
                .addFilter(AggregateProcessorFilter(SimpleServiceProvider(), aggregateProcessorFactory))
                .addFilter(SendDomainEventStreamFilter(domainEventBus))
                .addFilter(SendStateEventFilter(stateEventBus))
                .build()
        )
        aggregateDomainStateAndProcessedNotifierHandler = DefaultCommandHandler(
            FilterChainBuilder<ServerCommandExchange<*>>()
                .addFilter(AggregateProcessorFilter(SimpleServiceProvider(), aggregateProcessorFactory))
                .addFilter(SendDomainEventStreamFilter(domainEventBus))
                .addFilter(SendStateEventFilter(stateEventBus))
                .addFilter(ProcessedNotifierFilter(commandWaitNotifier))
                .build()
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

    private fun createServerExchange(): ServerCommandExchange<*> {
        val exchange = SimpleServerCommandExchange(HotPathFixture.createCommandMessage())
        exchange.setAggregateMetadata(HotPathFixture.aggregateMetadata)
        return exchange
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
    fun handleAggregateOnly(blackhole: Blackhole) {
        try {
            val result = aggregateOnlyHandler.handle(createServerExchange()).block()
            blackhole.consume(result)
        } catch (e: WowException) {
            blackhole.consume(e)
        }
    }

    @Benchmark
    fun handleAggregateOnlyWithoutRetry(blackhole: Blackhole) {
        try {
            val result = aggregateOnlyWithoutRetryHandler.handle(createServerExchange()).block()
            blackhole.consume(result)
        } catch (e: WowException) {
            blackhole.consume(e)
        }
    }

    @Benchmark
    fun handleAggregateAndDomainEvent(blackhole: Blackhole) {
        try {
            val result = aggregateAndDomainEventHandler.handle(createServerExchange()).block()
            blackhole.consume(result)
        } catch (e: WowException) {
            blackhole.consume(e)
        }
    }

    @Benchmark
    fun handleAggregateDomainAndStateEvent(blackhole: Blackhole) {
        try {
            val result = aggregateDomainAndStateEventHandler.handle(createServerExchange()).block()
            blackhole.consume(result)
        } catch (e: WowException) {
            blackhole.consume(e)
        }
    }

    @Benchmark
    fun handleAggregateDomainStateAndProcessedNotifierWithoutWait(blackhole: Blackhole) {
        try {
            val result = aggregateDomainStateAndProcessedNotifierHandler.handle(createServerExchange()).block()
            blackhole.consume(result)
        } catch (e: WowException) {
            blackhole.consume(e)
        }
    }

    @Benchmark
    fun handleAggregateDomainStateAndProcessedNotifierWithLocalWait(blackhole: Blackhole) {
        try {
            val commandMessage = HotPathFixture.createCommandMessage()
            val waitStrategy = WaitingForStage.processed(commandMessage.commandId)
            waitStrategy.propagate("", commandMessage.header)
            SimpleWaitStrategyRegistrar.register(waitStrategy)
            waitStrategy.onFinally {
                SimpleWaitStrategyRegistrar.unregister(waitStrategy.waitCommandId)
            }
            val exchange = SimpleServerCommandExchange(commandMessage)
            exchange.setAggregateMetadata(HotPathFixture.aggregateMetadata)
            val result = aggregateDomainStateAndProcessedNotifierHandler
                .handle(exchange)
                .then(waitStrategy.waitingLast())
                .block()
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

private class DirectAggregateProcessorFactory(
    private val stateAggregateFactory: StateAggregateFactory,
    private val stateAggregateRepository: StateAggregateRepository,
    private val commandAggregateFactory: CommandAggregateFactory
) : AggregateProcessorFactory {
    override fun <C : Any, S : Any> create(
        aggregateId: AggregateId,
        aggregateMetadata: AggregateMetadata<C, S>
    ): AggregateProcessor<C> =
        DirectAggregateProcessor(
            aggregateId = aggregateId,
            aggregateMetadata = aggregateMetadata,
            stateAggregateFactory = stateAggregateFactory,
            stateAggregateRepository = stateAggregateRepository,
            commandAggregateFactory = commandAggregateFactory,
        )
}

private class DirectAggregateProcessor<C : Any, S : Any>(
    override val aggregateId: AggregateId,
    private val aggregateMetadata: AggregateMetadata<C, S>,
    private val stateAggregateFactory: StateAggregateFactory,
    private val stateAggregateRepository: StateAggregateRepository,
    private val commandAggregateFactory: CommandAggregateFactory
) : AggregateProcessor<C>, NamedTypedAggregate<C> by aggregateMetadata.command {
    override val processorName: String = DirectAggregateProcessor::class.simpleName!!

    override fun process(exchange: ServerCommandExchange<*>): Mono<DomainEventStream> {
        val stateAggregateMono = if (exchange.message.isCreate) {
            stateAggregateFactory.createAsMono(aggregateMetadata.state, exchange.message.aggregateId)
        } else {
            stateAggregateRepository.load(aggregateId, aggregateMetadata.state)
        }
        return stateAggregateMono
            .map {
                commandAggregateFactory.create(aggregateMetadata, it)
            }
            .flatMap {
                exchange.clearError()
                it.process(exchange)
            }
    }
}
