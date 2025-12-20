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

package me.ahoo.wow.spring.boot.starter.saga

import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.factory.CommandMessageFactory
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.filter.LogResumeErrorHandler
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.handler.ExchangeFilter
import me.ahoo.wow.saga.stateless.DefaultStatelessSagaHandler
import me.ahoo.wow.saga.stateless.StatelessSagaDispatcher
import me.ahoo.wow.saga.stateless.StatelessSagaFunctionFilter
import me.ahoo.wow.saga.stateless.StatelessSagaFunctionRegistrar
import me.ahoo.wow.saga.stateless.StatelessSagaHandler
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.WowAutoConfiguration
import me.ahoo.wow.spring.boot.starter.WowProperties
import me.ahoo.wow.spring.saga.StatelessSagaDispatcherLauncher
import me.ahoo.wow.spring.saga.StatelessSagaProcessorAutoRegistrar
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean

@AutoConfiguration
@ConditionalOnWowEnabled
class StatelessSagaAutoConfiguration(private val wowProperties: WowProperties) {

    @Bean
    @ConditionalOnMissingBean
    fun statelessSagaHandlerRegistrar(
        commandGateway: CommandGateway,
        commandMessageFactory: CommandMessageFactory
    ): StatelessSagaFunctionRegistrar {
        return StatelessSagaFunctionRegistrar(commandGateway, commandMessageFactory)
    }

    @Bean
    @ConditionalOnMissingBean
    fun statelessSagaProcessorAutoRegistrar(
        handlerRegistrar: StatelessSagaFunctionRegistrar,
        applicationContext: ApplicationContext
    ): StatelessSagaProcessorAutoRegistrar {
        return StatelessSagaProcessorAutoRegistrar(handlerRegistrar, applicationContext)
    }

    @Bean
    fun statelessSagaFunctionFilter(
        serviceProvider: ServiceProvider
    ): StatelessSagaFunctionFilter {
        return StatelessSagaFunctionFilter(serviceProvider)
    }

    @Bean
    fun statelessSagaFilterChain(
        filters: List<ExchangeFilter<DomainEventExchange<*>>>
    ): FilterChain<DomainEventExchange<*>> {
        return FilterChainBuilder<DomainEventExchange<*>>()
            .addFilters(filters)
            .filterCondition(StatelessSagaDispatcher::class)
            .build()
    }

    @Bean("statelessSagaErrorHandler")
    @ConditionalOnMissingBean(name = ["statelessSagaErrorHandler"])
    fun statelessSagaErrorHandler(): ErrorHandler<DomainEventExchange<*>> {
        return LogResumeErrorHandler()
    }

    @Bean
    fun statelessSagaHandler(
        @Qualifier("statelessSagaFilterChain") chain: FilterChain<DomainEventExchange<*>>,
        @Qualifier("statelessSagaErrorHandler") statelessSagaErrorHandler: ErrorHandler<DomainEventExchange<*>>
    ): StatelessSagaHandler {
        return DefaultStatelessSagaHandler(chain, statelessSagaErrorHandler)
    }

    @Bean
    @ConditionalOnMissingBean
    fun statelessSagaDispatcher(
        @Qualifier(WowAutoConfiguration.WOW_CURRENT_BOUNDED_CONTEXT)
        namedBoundedContext: NamedBoundedContext,
        handlerRegistrar: StatelessSagaFunctionRegistrar,
        domainEventBus: DomainEventBus,
        stateEventBus: StateEventBus,
        statelessSagaHandler: StatelessSagaHandler
    ): StatelessSagaDispatcher {
        return StatelessSagaDispatcher(
            name = "${namedBoundedContext.contextName}.${StatelessSagaDispatcher::class.simpleName}",
            domainEventBus = domainEventBus,
            stateEventBus = stateEventBus,
            functionRegistrar = handlerRegistrar,
            eventHandler = statelessSagaHandler,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun statelessSagaDispatcherLauncher(
        statelessSagaDispatcher: StatelessSagaDispatcher
    ): StatelessSagaDispatcherLauncher {
        return StatelessSagaDispatcherLauncher(statelessSagaDispatcher, wowProperties.shutdownTimeout)
    }
}
