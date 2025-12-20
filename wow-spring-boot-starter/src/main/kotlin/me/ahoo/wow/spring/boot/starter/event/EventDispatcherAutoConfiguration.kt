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

package me.ahoo.wow.spring.boot.starter.event

import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.dispatcher.DefaultDomainEventHandler
import me.ahoo.wow.event.dispatcher.DomainEventDispatcher
import me.ahoo.wow.event.dispatcher.DomainEventFunctionFilter
import me.ahoo.wow.event.dispatcher.DomainEventFunctionRegistrar
import me.ahoo.wow.event.dispatcher.DomainEventHandler
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.filter.LogResumeErrorHandler
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.handler.ExchangeFilter
import me.ahoo.wow.messaging.handler.RetryableFilter
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.WowAutoConfiguration
import me.ahoo.wow.spring.event.DomainEventDispatcherLauncher
import me.ahoo.wow.spring.event.EventProcessorAutoRegistrar
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean

@AutoConfiguration
@ConditionalOnWowEnabled
class EventDispatcherAutoConfiguration {

    @Bean
    fun domainEventHandlerRegistrar(): DomainEventFunctionRegistrar {
        return DomainEventFunctionRegistrar()
    }

    @Bean
    fun eventProcessorAutoRegistrar(
        handlerRegistrar: DomainEventFunctionRegistrar,
        applicationContext: ApplicationContext
    ): EventProcessorAutoRegistrar {
        return EventProcessorAutoRegistrar(handlerRegistrar, applicationContext)
    }

    @Bean
    fun eventDispatcherRetryableFilter(): RetryableFilter<DomainEventExchange<Any>> {
        return RetryableFilter()
    }

    @Bean
    fun eventDispatcherFunctionFilter(
        serviceProvider: ServiceProvider
    ): DomainEventFunctionFilter {
        return DomainEventFunctionFilter(serviceProvider)
    }

    @Bean
    fun eventDispatcherFilterChain(
        filters: List<ExchangeFilter<DomainEventExchange<*>>>
    ): FilterChain<DomainEventExchange<*>> {
        return FilterChainBuilder<DomainEventExchange<*>>()
            .addFilters(filters)
            .filterCondition(DomainEventDispatcher::class)
            .build()
    }

    @Bean("eventProcessorErrorHandler")
    @ConditionalOnMissingBean(name = ["eventProcessorErrorHandler"])
    fun eventProcessorErrorHandler(): ErrorHandler<DomainEventExchange<*>> {
        return LogResumeErrorHandler()
    }

    @Bean
    fun eventDispatcherHandler(
        @Qualifier("eventDispatcherFilterChain") chain: FilterChain<DomainEventExchange<*>>,
        @Qualifier("eventProcessorErrorHandler") eventProcessorErrorHandler: ErrorHandler<DomainEventExchange<*>>
    ): DomainEventHandler {
        return DefaultDomainEventHandler(chain, eventProcessorErrorHandler)
    }

    @Bean
    @ConditionalOnMissingBean
    fun domainEventDispatcher(
        @Qualifier(WowAutoConfiguration.WOW_CURRENT_BOUNDED_CONTEXT)
        namedBoundedContext: NamedBoundedContext,
        domainEventBus: DomainEventBus,
        stateEventBus: StateEventBus,
        handlerRegistrar: DomainEventFunctionRegistrar,
        eventDispatcherHandler: DomainEventHandler
    ): DomainEventDispatcher {
        return DomainEventDispatcher(
            name = "${namedBoundedContext.contextName}.${DomainEventDispatcher::class.simpleName}",
            domainEventBus = domainEventBus,
            stateEventBus = stateEventBus,
            functionRegistrar = handlerRegistrar,
            eventHandler = eventDispatcherHandler,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun domainEventDispatcherLauncher(domainEventDispatcher: DomainEventDispatcher): DomainEventDispatcherLauncher {
        return DomainEventDispatcherLauncher(domainEventDispatcher)
    }
}
