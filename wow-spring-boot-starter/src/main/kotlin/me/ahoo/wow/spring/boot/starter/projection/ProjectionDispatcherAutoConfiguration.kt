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

package me.ahoo.wow.spring.boot.starter.projection

import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.filter.LogResumeErrorHandler
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.handler.ExchangeFilter
import me.ahoo.wow.projection.DefaultProjectionHandler
import me.ahoo.wow.projection.ProjectionDispatcher
import me.ahoo.wow.projection.ProjectionFunctionFilter
import me.ahoo.wow.projection.ProjectionFunctionRegistrar
import me.ahoo.wow.projection.ProjectionHandler
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.WowAutoConfiguration
import me.ahoo.wow.spring.boot.starter.WowProperties
import me.ahoo.wow.spring.projection.ProjectionDispatcherLauncher
import me.ahoo.wow.spring.projection.ProjectionProcessorAutoRegistrar
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean

@AutoConfiguration
@ConditionalOnWowEnabled
class ProjectionDispatcherAutoConfiguration(private val wowProperties: WowProperties) {

    @Bean
    @ConditionalOnMissingBean
    fun projectionHandlerRegistrar(): ProjectionFunctionRegistrar {
        return ProjectionFunctionRegistrar()
    }

    @Bean
    @ConditionalOnMissingBean
    fun projectionProcessorAutoRegistrar(
        handlerRegistrar: ProjectionFunctionRegistrar,
        applicationContext: ApplicationContext
    ): ProjectionProcessorAutoRegistrar {
        return ProjectionProcessorAutoRegistrar(handlerRegistrar, applicationContext)
    }

    @Bean
    @ConditionalOnMissingBean
    fun projectionFunctionFilter(
        serviceProvider: ServiceProvider
    ): ProjectionFunctionFilter {
        return ProjectionFunctionFilter(serviceProvider)
    }

    @Bean
    fun projectionFilterChain(
        filters: List<ExchangeFilter<DomainEventExchange<*>>>
    ): FilterChain<DomainEventExchange<*>> {
        return FilterChainBuilder<DomainEventExchange<*>>()
            .addFilters(filters)
            .filterCondition(ProjectionDispatcher::class)
            .build()
    }

    @Bean("projectionErrorHandler")
    @ConditionalOnMissingBean(name = ["projectionErrorHandler"])
    fun projectionErrorHandler(): ErrorHandler<DomainEventExchange<*>> {
        return LogResumeErrorHandler()
    }

    @Bean
    fun projectionHandler(
        @Qualifier("projectionFilterChain") chain: FilterChain<DomainEventExchange<*>>,
        @Qualifier("projectionErrorHandler") projectionErrorHandler: ErrorHandler<DomainEventExchange<*>>
    ): ProjectionHandler {
        return DefaultProjectionHandler(chain, projectionErrorHandler)
    }

    @Bean
    @ConditionalOnMissingBean
    fun projectionDispatcher(
        @Qualifier(WowAutoConfiguration.WOW_CURRENT_BOUNDED_CONTEXT)
        namedBoundedContext: NamedBoundedContext,
        handlerRegistrar: ProjectionFunctionRegistrar,
        domainEventBus: DomainEventBus,
        stateEventBus: StateEventBus,
        projectionHandler: ProjectionHandler
    ): ProjectionDispatcher {
        return ProjectionDispatcher(
            name = "${namedBoundedContext.contextName}.${ProjectionDispatcher::class.simpleName}",
            domainEventBus = domainEventBus,
            stateEventBus = stateEventBus,
            functionRegistrar = handlerRegistrar,
            eventHandler = projectionHandler,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun projectionDispatcherLauncher(projectionDispatcher: ProjectionDispatcher): ProjectionDispatcherLauncher {
        return ProjectionDispatcherLauncher(projectionDispatcher, wowProperties.shutdownTimeout)
    }
}
