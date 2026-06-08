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

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedTypedAggregate
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkCommands
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.command.wait.CommandWaitNotifier
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.command.wait.ProcessedNotifierFilter
import me.ahoo.wow.command.wait.SimpleWaitStrategyRegistrar
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.eventsourcing.EventSourcingStateAggregateRepository
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.NoopEventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.eventsourcing.state.SendStateEventFilter
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.modeling.command.AggregateProcessor
import me.ahoo.wow.modeling.command.AggregateProcessorFactory
import me.ahoo.wow.modeling.command.CommandAggregateFactory
import me.ahoo.wow.modeling.command.RetryableAggregateProcessorFactory
import me.ahoo.wow.modeling.command.SimpleCommandAggregateFactory
import me.ahoo.wow.modeling.command.dispatcher.AggregateProcessorFilter
import me.ahoo.wow.modeling.command.dispatcher.CommandHandler
import me.ahoo.wow.modeling.command.dispatcher.DefaultCommandHandler
import me.ahoo.wow.modeling.command.dispatcher.SendDomainEventStreamFilter
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateRepository
import reactor.core.publisher.Mono

class CommandPipelineScenario private constructor(
    val aggregateOnlyHandler: CommandHandler,
    val aggregateOnlyWithoutRetryHandler: CommandHandler,
    val aggregateAndDomainEventHandler: CommandHandler,
    val aggregateDomainAndStateEventHandler: CommandHandler,
    val aggregateDomainStateAndProcessedNotifierHandler: CommandHandler,
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val newAggregateCommandFactory: () -> CommandMessage<*>,
) {

    fun createServerExchange(newAggregate: Boolean = true): ServerCommandExchange<*> {
        val commandMessage = if (newAggregate) {
            newAggregateCommandFactory()
        } else {
            BenchmarkCommands.fixedAggregateAddCartItem()
        }
        return createServerExchange(commandMessage)
    }

    fun <C : Any> createServerExchange(commandMessage: CommandMessage<C>): ServerCommandExchange<C> {
        val exchange = SimpleServerCommandExchange(commandMessage)
        exchange.setAggregateMetadata(aggregateMetadata)
        return exchange
    }

    companion object {
        fun create(
            eventStore: EventStore = NoopEventStore,
            snapshotRepository: SnapshotRepository = InMemorySnapshotRepository(),
            domainEventBus: DomainEventBus = InMemoryDomainEventBus(),
            stateEventBus: StateEventBus = InMemoryStateEventBus(),
            commandWaitNotifier: CommandWaitNotifier = LocalCommandWaitNotifier(SimpleWaitStrategyRegistrar),
            aggregateMetadata: AggregateMetadata<*, *> = BenchmarkAggregates.cartMetadata,
            newAggregateCommandFactory: () -> CommandMessage<*> = BenchmarkCommands::commandPathAddCartItem,
        ): CommandPipelineScenario {
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
            return CommandPipelineScenario(
                aggregateOnlyHandler = createHandler(aggregateProcessorFactory),
                aggregateOnlyWithoutRetryHandler = createHandler(directAggregateProcessorFactory),
                aggregateAndDomainEventHandler = createHandler(aggregateProcessorFactory) {
                    addFilter(SendDomainEventStreamFilter(domainEventBus))
                },
                aggregateDomainAndStateEventHandler = createHandler(aggregateProcessorFactory) {
                    addFilter(SendDomainEventStreamFilter(domainEventBus))
                    addFilter(SendStateEventFilter(stateEventBus))
                },
                aggregateDomainStateAndProcessedNotifierHandler = createHandler(aggregateProcessorFactory) {
                    addFilter(SendDomainEventStreamFilter(domainEventBus))
                    addFilter(SendStateEventFilter(stateEventBus))
                    addFilter(ProcessedNotifierFilter(commandWaitNotifier))
                },
                aggregateMetadata = aggregateMetadata,
                newAggregateCommandFactory = newAggregateCommandFactory,
            )
        }

        private fun createHandler(
            aggregateProcessorFactory: AggregateProcessorFactory,
            configure: FilterChainBuilder<ServerCommandExchange<*>>.() -> Unit = {},
        ): CommandHandler {
            val chainBuilder = FilterChainBuilder<ServerCommandExchange<*>>()
                .addFilter(AggregateProcessorFilter(SimpleServiceProvider(), aggregateProcessorFactory))
            chainBuilder.configure()
            return DefaultCommandHandler(chainBuilder.build())
        }
    }
}

private class DirectAggregateProcessorFactory(
    private val stateAggregateFactory: StateAggregateFactory,
    private val stateAggregateRepository: StateAggregateRepository,
    private val commandAggregateFactory: CommandAggregateFactory,
) : AggregateProcessorFactory {
    override fun <C : Any, S : Any> create(
        aggregateId: AggregateId,
        aggregateMetadata: AggregateMetadata<C, S>,
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
    private val commandAggregateFactory: CommandAggregateFactory,
) : AggregateProcessor<C>, NamedTypedAggregate<C> by aggregateMetadata.command {
    override val processorName: String =
        DirectAggregateProcessor::class.simpleName ?: "DirectAggregateProcessor"

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
