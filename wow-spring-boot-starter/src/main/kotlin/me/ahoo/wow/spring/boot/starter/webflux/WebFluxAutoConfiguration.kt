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
package me.ahoo.wow.spring.boot.starter.webflux

import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.wait.WaitStrategyRegistrar
import me.ahoo.wow.event.compensation.DomainEventCompensator
import me.ahoo.wow.event.compensation.StateEventCompensator
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.openapi.Router
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.command.CommandAutoConfiguration
import me.ahoo.wow.spring.boot.starter.openapi.OpenAPIAutoConfiguration
import me.ahoo.wow.webflux.exception.DefaultExceptionHandler
import me.ahoo.wow.webflux.exception.ExceptionHandler
import me.ahoo.wow.webflux.route.AggregateRouterFunctionAutoRegistrar
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse

/**
 * WebFlux Auto Configuration .
 *
 * @author ahoo wang
 */
@AutoConfiguration(after = [CommandAutoConfiguration::class, OpenAPIAutoConfiguration::class])
@ConditionalOnWowEnabled
@ConditionalOnWebfluxEnabled
@EnableConfigurationProperties(WebFluxProperties::class)
@ConditionalOnClass(
    name = ["org.springframework.web.server.WebFilter", "me.ahoo.wow.webflux.route.command.CommandHandlerFunction"],
)
class WebFluxAutoConfiguration {

    @Suppress("LongParameterList")
    @Bean
    fun commandRouterFunction(
        router: Router,
        waitStrategyRegistrar: WaitStrategyRegistrar,
        commandGateway: CommandGateway,
        stateAggregateRepository: StateAggregateRepository,
        snapshotRepository: SnapshotRepository,
        stateAggregateFactory: StateAggregateFactory,
        domainEventCompensator: DomainEventCompensator,
        stateEventCompensator: StateEventCompensator,
        eventStore: EventStore,
        exceptionHandler: ExceptionHandler
    ): RouterFunction<ServerResponse> {
        return AggregateRouterFunctionAutoRegistrar(
            router = router,
            waitStrategyRegistrar = waitStrategyRegistrar,
            commandGateway = commandGateway,
            stateAggregateRepository = stateAggregateRepository,
            snapshotRepository = snapshotRepository,
            stateAggregateFactory = stateAggregateFactory,
            eventStore = eventStore,
            domainEventCompensator = domainEventCompensator,
            stateEventCompensator = stateEventCompensator,
            exceptionHandler = exceptionHandler,
        ).routerFunction
    }

    @Bean
    @ConditionalOnMissingBean
    fun exceptionHandler(): ExceptionHandler {
        return DefaultExceptionHandler
    }
}
