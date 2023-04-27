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

package me.ahoo.wow.spring.boot.starter.modeling

import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.eventsourcing.EventSourcingStateAggregateRepository
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.handler.ErrorHandler
import me.ahoo.wow.messaging.handler.Filter
import me.ahoo.wow.messaging.handler.FilterChain
import me.ahoo.wow.messaging.handler.FilterChainBuilder
import me.ahoo.wow.messaging.handler.LogResumeErrorHandler
import me.ahoo.wow.modeling.command.AggregateDispatcher
import me.ahoo.wow.modeling.command.AggregateProcessorFactory
import me.ahoo.wow.modeling.command.AggregateProcessorFilter
import me.ahoo.wow.modeling.command.CommandAggregateFactory
import me.ahoo.wow.modeling.command.CommandHandler
import me.ahoo.wow.modeling.command.RetryableAggregateProcessorFactory
import me.ahoo.wow.modeling.command.SendDomainEventStreamFilter
import me.ahoo.wow.modeling.command.SimpleCommandAggregateFactory
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.command.AggregateDispatcherLauncher
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

@AutoConfiguration
@ConditionalOnWowEnabled
class AggregateAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun stateAggregateFactory(): StateAggregateFactory {
        return ConstructorStateAggregateFactory
    }

    @Bean
    @ConditionalOnMissingBean
    fun stateAggregateRepository(
        stateAggregateFactory: StateAggregateFactory,
        snapshotRepository: SnapshotRepository,
        eventStore: EventStore,
    ): StateAggregateRepository {
        return EventSourcingStateAggregateRepository(stateAggregateFactory, snapshotRepository, eventStore)
    }

    @Bean
    @ConditionalOnMissingBean
    fun commandAggregateFactory(eventStore: EventStore): CommandAggregateFactory {
        return SimpleCommandAggregateFactory(eventStore)
    }

    @Bean
    @ConditionalOnMissingBean
    fun aggregateProcessorFactory(
        stateAggregateFactory: StateAggregateFactory,
        stateAggregateRepository: StateAggregateRepository,
        commandAggregateFactory: CommandAggregateFactory,
    ): AggregateProcessorFactory {
        return RetryableAggregateProcessorFactory(
            stateAggregateFactory = stateAggregateFactory,
            stateAggregateRepository = stateAggregateRepository,
            commandAggregateFactory = commandAggregateFactory,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun aggregateProcessorFilter(): AggregateProcessorFilter {
        return AggregateProcessorFilter
    }

    @Bean
    @ConditionalOnMissingBean
    fun sendDomainEventStreamFilter(
        domainEventBus: DomainEventBus,
    ): SendDomainEventStreamFilter {
        return SendDomainEventStreamFilter(
            domainEventBus = domainEventBus,
        )
    }

    @Bean
    fun commandFilterChain(
        filters: List<Filter<ServerCommandExchange<Any>>>,
    ): FilterChain<ServerCommandExchange<Any>> {
        return FilterChainBuilder<ServerCommandExchange<Any>>()
            .addFilters(filters)
            .filterCondition(AggregateDispatcher::class)
            .build()
    }

    @Bean("commandErrorHandler")
    @ConditionalOnMissingBean(name = ["commandErrorHandler"])
    fun commandErrorHandler(): ErrorHandler<ServerCommandExchange<Any>> {
        return LogResumeErrorHandler()
    }

    @Bean
    @ConditionalOnMissingBean
    fun commandHandler(
        commandFilterChain: FilterChain<ServerCommandExchange<Any>>,
        @Qualifier("commandErrorHandler") commandErrorHandler: ErrorHandler<ServerCommandExchange<Any>>,
    ): CommandHandler {
        return CommandHandler(
            chain = commandFilterChain,
            errorHandler = commandErrorHandler,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun aggregateDispatcher(
        namedBoundedContext: NamedBoundedContext,
        commandBus: CommandBus,
        aggregateProcessorFactory: AggregateProcessorFactory,
        commandHandler: CommandHandler,
        serviceProvider: ServiceProvider,
    ): AggregateDispatcher {
        return AggregateDispatcher(
            name = "${namedBoundedContext.contextName}.${AggregateDispatcher::class.simpleName}",
            commandBus = commandBus,
            aggregateProcessorFactory = aggregateProcessorFactory,
            commandHandler = commandHandler,
            serviceProvider = serviceProvider,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun aggregateDispatcherLauncher(aggregateDispatcher: AggregateDispatcher): AggregateDispatcherLauncher {
        return AggregateDispatcherLauncher(aggregateDispatcher)
    }
}
