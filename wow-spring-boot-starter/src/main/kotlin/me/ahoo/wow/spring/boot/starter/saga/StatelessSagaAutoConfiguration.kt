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
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.DomainEventHandler
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.handler.ErrorHandler
import me.ahoo.wow.messaging.handler.Filter
import me.ahoo.wow.messaging.handler.FilterChain
import me.ahoo.wow.messaging.handler.FilterChainBuilder
import me.ahoo.wow.messaging.handler.LogResumeErrorHandler
import me.ahoo.wow.saga.stateless.StatelessSagaDispatcher
import me.ahoo.wow.saga.stateless.StatelessSagaFunctionFilter
import me.ahoo.wow.saga.stateless.StatelessSagaFunctionRegistrar
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.saga.StatelessSagaDispatcherLauncher
import me.ahoo.wow.spring.saga.StatelessSagaProcessorAutoRegistrar
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean

@AutoConfiguration
@ConditionalOnWowEnabled
class StatelessSagaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun statelessSagaHandlerRegistrar(): StatelessSagaFunctionRegistrar {
        return StatelessSagaFunctionRegistrar()
    }

    @Bean
    @ConditionalOnMissingBean
    fun statelessSagaProcessorAutoRegistrar(
        handlerRegistrar: StatelessSagaFunctionRegistrar,
        commandGateway: CommandGateway,
        applicationContext: ApplicationContext,
    ): StatelessSagaProcessorAutoRegistrar {
        return StatelessSagaProcessorAutoRegistrar(handlerRegistrar, commandGateway, applicationContext)
    }

    @Bean
    fun statelessSagaFunctionFilter(
        serviceProvider: ServiceProvider,
    ): StatelessSagaFunctionFilter {
        return StatelessSagaFunctionFilter(serviceProvider)
    }

    @Bean
    fun statelessSagaFilterChain(filters: List<Filter<DomainEventExchange<Any>>>): FilterChain<DomainEventExchange<Any>> {
        return FilterChainBuilder<DomainEventExchange<Any>>()
            .addFilters(filters)
            .filterCondition(StatelessSagaDispatcher::class)
            .build()
    }

    @Bean("statelessSagaErrorHandler")
    @ConditionalOnMissingBean(name = ["statelessSagaErrorHandler"])
    fun statelessSagaErrorHandler(): ErrorHandler<DomainEventExchange<Any>> {
        return LogResumeErrorHandler()
    }

    @Bean
    fun statelessSagaHandler(
        @Qualifier("statelessSagaFilterChain") chain: FilterChain<DomainEventExchange<Any>>,
        @Qualifier("statelessSagaErrorHandler") statelessSagaErrorHandler: ErrorHandler<DomainEventExchange<Any>>,
    ): DomainEventHandler {
        return DomainEventHandler(chain, statelessSagaErrorHandler)
    }

    @Bean
    @ConditionalOnMissingBean
    fun statelessSagaDispatcher(
        namedBoundedContext: NamedBoundedContext,
        handlerRegistrar: StatelessSagaFunctionRegistrar,
        domainEventBus: DomainEventBus,
        @Qualifier("statelessSagaHandler") statelessSagaHandler: DomainEventHandler,
    ): StatelessSagaDispatcher {
        return StatelessSagaDispatcher(
            name = "${namedBoundedContext.contextName}.${StatelessSagaDispatcher::class.simpleName}",
            domainEventBus = domainEventBus,
            functionRegistrar = handlerRegistrar,
            statelessSagaHandler = statelessSagaHandler,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun statelessSagaDispatcherLauncher(statelessSagaDispatcher: StatelessSagaDispatcher): StatelessSagaDispatcherLauncher {
        return StatelessSagaDispatcherLauncher(statelessSagaDispatcher)
    }
}
