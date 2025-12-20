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
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.eventsourcing.EventSourcingStateAggregateRepository
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.filter.LogResumeErrorHandler
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.handler.ExchangeFilter
import me.ahoo.wow.modeling.command.AggregateProcessorFactory
import me.ahoo.wow.modeling.command.CommandAggregateFactory
import me.ahoo.wow.modeling.command.RetryableAggregateProcessorFactory
import me.ahoo.wow.modeling.command.SimpleCommandAggregateFactory
import me.ahoo.wow.modeling.command.dispatcher.AggregateProcessorFilter
import me.ahoo.wow.modeling.command.dispatcher.CommandDispatcher
import me.ahoo.wow.modeling.command.dispatcher.CommandHandler
import me.ahoo.wow.modeling.command.dispatcher.DefaultCommandHandler
import me.ahoo.wow.modeling.command.dispatcher.SendDomainEventStreamFilter
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.WowAutoConfiguration
import me.ahoo.wow.spring.command.CommandDispatcherLauncher
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
        eventStore: EventStore
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
        commandAggregateFactory: CommandAggregateFactory
    ): AggregateProcessorFactory {
        return RetryableAggregateProcessorFactory(
            stateAggregateFactory = stateAggregateFactory,
            stateAggregateRepository = stateAggregateRepository,
            commandAggregateFactory = commandAggregateFactory,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun aggregateProcessorFilter(
        serviceProvider: ServiceProvider,
        aggregateProcessorFactory: AggregateProcessorFactory,
    ): AggregateProcessorFilter {
        return AggregateProcessorFilter(serviceProvider, aggregateProcessorFactory)
    }

    @Bean
    @ConditionalOnMissingBean
    fun sendDomainEventStreamFilter(
        domainEventBus: DomainEventBus
    ): SendDomainEventStreamFilter {
        return SendDomainEventStreamFilter(
            domainEventBus = domainEventBus,
        )
    }

    @Bean
    fun commandFilterChain(
        filters: List<ExchangeFilter<ServerCommandExchange<*>>>
    ): FilterChain<ServerCommandExchange<*>> {
        return FilterChainBuilder<ServerCommandExchange<*>>()
            .addFilters(filters)
            .filterCondition(CommandDispatcher::class)
            .build()
    }

    @Bean("commandErrorHandler")
    @ConditionalOnMissingBean(name = ["commandErrorHandler"])
    fun commandErrorHandler(): ErrorHandler<ServerCommandExchange<*>> {
        return LogResumeErrorHandler()
    }

    @Bean
    @ConditionalOnMissingBean
    fun commandHandler(
        commandFilterChain: FilterChain<ServerCommandExchange<*>>,
        @Qualifier("commandErrorHandler") commandErrorHandler: ErrorHandler<ServerCommandExchange<*>>
    ): CommandHandler {
        return DefaultCommandHandler(
            chain = commandFilterChain,
            errorHandler = commandErrorHandler,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun aggregateDispatcher(
        @Qualifier(WowAutoConfiguration.WOW_CURRENT_BOUNDED_CONTEXT)
        namedBoundedContext: NamedBoundedContext,
        commandBus: CommandGateway,
        commandHandler: CommandHandler,
    ): CommandDispatcher {
        return CommandDispatcher(
            name = "${namedBoundedContext.contextName}.${CommandDispatcher::class.simpleName}",
            commandBus = commandBus,
            commandHandler = commandHandler,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun aggregateDispatcherLauncher(commandDispatcher: CommandDispatcher): CommandDispatcherLauncher {
        return CommandDispatcherLauncher(commandDispatcher)
    }
}
