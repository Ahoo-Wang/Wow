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
import me.ahoo.wow.command.DefaultRequestIdChecker
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.wait.CommandWaitNotifier
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.eventsourcing.EventSourcingStateAggregateRepository
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.LogResumeErrorHandler
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.propagation.MessagePropagators
import me.ahoo.wow.metrics.WowMetrics
import me.ahoo.wow.modeling.command.AggregateProcessorFactory
import me.ahoo.wow.modeling.command.CommandAggregateFactory
import me.ahoo.wow.modeling.command.RetryableAggregateProcessorFactory
import me.ahoo.wow.modeling.command.SimpleCommandAggregateFactory
import me.ahoo.wow.modeling.command.dispatcher.CommandDispatcher
import me.ahoo.wow.modeling.command.dispatcher.CommandHandler
import me.ahoo.wow.modeling.command.dispatcher.CommandInstrumentation
import me.ahoo.wow.modeling.command.dispatcher.DefaultCommandHandler
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.WowAutoConfiguration
import me.ahoo.wow.spring.boot.starter.WowRuntimeComponentOrder
import me.ahoo.wow.spring.boot.starter.command.CommandProperties
import me.ahoo.wow.spring.boot.starter.command.bloomFilterCheckerProvider
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.core.annotation.Order

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
        snapshotStore: SnapshotStore,
        eventStore: EventStore
    ): StateAggregateRepository {
        return EventSourcingStateAggregateRepository(stateAggregateFactory, snapshotStore, eventStore)
    }

    @Bean
    @ConditionalOnMissingBean
    fun commandAggregateFactory(
        eventStore: EventStore,
        messagePropagators: ObjectProvider<MessagePropagators>,
    ): CommandAggregateFactory {
        return SimpleCommandAggregateFactory(
            eventStore,
            messagePropagators.getIfAvailable { MessagePropagators.DEFAULT }
        )
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
    fun retiredCommandFilterCheck(beanFactory: ConfigurableListableBeanFactory): SmartInitializingSingleton =
        RetiredCommandFilterCheck(beanFactory)

    @Bean("commandErrorHandler")
    @ConditionalOnMissingBean(name = ["commandErrorHandler"])
    fun commandErrorHandler(): ErrorHandler<ServerCommandExchange<*>> {
        return LogResumeErrorHandler()
    }

    @Bean
    @ConditionalOnMissingBean
    fun commandHandler(
        serviceProvider: ServiceProvider,
        aggregateProcessorFactory: AggregateProcessorFactory,
        domainEventBus: DomainEventBus,
        stateEventBus: ObjectProvider<StateEventBus>,
        commandWaitNotifier: ObjectProvider<CommandWaitNotifier>,
        instrumentations: ObjectProvider<CommandInstrumentation>,
        commandProperties: ObjectProvider<CommandProperties>,
        eventStore: ObjectProvider<EventStore>,
        @Qualifier("commandErrorHandler") commandErrorHandler: ErrorHandler<ServerCommandExchange<*>>
    ): CommandHandler {
        // The authoritative request-ID check runs here, on the node that owns the event store, before the handler.
        val idempotency = commandProperties.getIfAvailable { CommandProperties() }.idempotency
        val requestIdChecker = eventStore.ifAvailable?.takeIf { idempotency.enabled }?.let {
            DefaultRequestIdChecker(idempotency.bloomFilterCheckerProvider(), it)
        }
        return DefaultCommandHandler(
            serviceProvider = serviceProvider,
            aggregateProcessorFactory = aggregateProcessorFactory,
            domainEventBus = domainEventBus,
            stateEventBus = stateEventBus.ifAvailable,
            commandWaitNotifier = commandWaitNotifier.ifAvailable,
            instrumentations = instrumentations.toList(),
            requestIdChecker = requestIdChecker,
            errorHandler = commandErrorHandler,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    @Order(WowRuntimeComponentOrder.COMMAND)
    fun aggregateDispatcher(
        @Qualifier(WowAutoConfiguration.WOW_CURRENT_BOUNDED_CONTEXT)
        namedBoundedContext: NamedBoundedContext,
        commandBus: CommandGateway,
        commandHandler: CommandHandler,
        metrics: ObjectProvider<WowMetrics>,
    ): CommandDispatcher {
        return CommandDispatcher(
            name = "${namedBoundedContext.contextName}.${CommandDispatcher::class.simpleName}",
            commandBus = commandBus,
            commandHandler = commandHandler,
            metrics = metrics.getIfAvailable { WowMetrics.NONE },
        )
    }
}
