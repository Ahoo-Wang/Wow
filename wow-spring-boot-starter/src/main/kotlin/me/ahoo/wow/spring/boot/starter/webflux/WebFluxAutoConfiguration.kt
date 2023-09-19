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
import me.ahoo.wow.openapi.OpenAPIBuilder
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.command.CommandAutoConfiguration
import me.ahoo.wow.spring.boot.starter.openapi.OpenAPIAutoConfiguration
import me.ahoo.wow.webflux.exception.DefaultExceptionHandler
import me.ahoo.wow.webflux.exception.ExceptionHandler
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.RouteHandlerFunctionRegistrar
import me.ahoo.wow.webflux.route.RouterFunctionBuilder
import me.ahoo.wow.webflux.route.command.CommandHandlerFunctionFactory
import me.ahoo.wow.webflux.route.command.DEFAULT_TIME_OUT
import me.ahoo.wow.webflux.route.event.DomainEventCompensateHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.LoadEventStreamHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.state.RegenerateStateEventFunctionFactory
import me.ahoo.wow.webflux.route.event.state.StateEventCompensateHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.BatchRegenerateSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.LoadSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.RegenerateSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.state.AggregateTracingHandlerFunctionFactory
import me.ahoo.wow.webflux.route.state.IdsQueryAggregateHandlerFunctionFactory
import me.ahoo.wow.webflux.route.state.LoadAggregateHandlerFunctionFactory
import me.ahoo.wow.webflux.route.state.ScanAggregateHandlerFunctionFactory
import me.ahoo.wow.webflux.wait.CommandWaitHandlerFunctionFactory
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

    @Bean
    @ConditionalOnMissingBean
    fun exceptionHandler(): ExceptionHandler {
        return DefaultExceptionHandler
    }

    @Bean
    fun commandWaitHandlerFunctionFactory(
        waitStrategyRegistrar: WaitStrategyRegistrar
    ): CommandWaitHandlerFunctionFactory {
        return CommandWaitHandlerFunctionFactory(waitStrategyRegistrar)
    }

    @Bean
    fun loadAggregateHandlerFunctionFactory(
        stateAggregateRepository: StateAggregateRepository,
        exceptionHandler: ExceptionHandler
    ): LoadAggregateHandlerFunctionFactory {
        return LoadAggregateHandlerFunctionFactory(stateAggregateRepository, exceptionHandler)
    }

    @Bean
    fun idsQueryAggregateHandlerFunctionFactory(
        stateAggregateRepository: StateAggregateRepository,
        exceptionHandler: ExceptionHandler
    ): IdsQueryAggregateHandlerFunctionFactory {
        return IdsQueryAggregateHandlerFunctionFactory(stateAggregateRepository, exceptionHandler)
    }

    @Bean
    fun scanAggregateHandlerFunctionFactory(
        stateAggregateRepository: StateAggregateRepository,
        eventStore: EventStore,
        exceptionHandler: ExceptionHandler
    ): ScanAggregateHandlerFunctionFactory {
        return ScanAggregateHandlerFunctionFactory(stateAggregateRepository, eventStore, exceptionHandler)
    }

    @Bean
    fun aggregateTracingHandlerFunctionFactory(
        eventStore: EventStore,
        exceptionHandler: ExceptionHandler
    ): AggregateTracingHandlerFunctionFactory {
        return AggregateTracingHandlerFunctionFactory(eventStore, exceptionHandler)
    }

    @Bean
    fun loadSnapshotHandlerFunctionFactory(
        snapshotRepository: SnapshotRepository,
        exceptionHandler: ExceptionHandler
    ): LoadSnapshotHandlerFunctionFactory {
        return LoadSnapshotHandlerFunctionFactory(snapshotRepository, exceptionHandler)
    }

    @Bean
    fun regenerateSnapshotHandlerFunctionFactory(
        stateAggregateFactory: StateAggregateFactory,
        eventStore: EventStore,
        snapshotRepository: SnapshotRepository,
        exceptionHandler: ExceptionHandler
    ): RegenerateSnapshotHandlerFunctionFactory {
        return RegenerateSnapshotHandlerFunctionFactory(
            stateAggregateFactory,
            eventStore,
            snapshotRepository,
            exceptionHandler
        )
    }

    @Bean
    fun batchRegenerateSnapshotHandlerFunctionFactory(
        stateAggregateFactory: StateAggregateFactory,
        eventStore: EventStore,
        snapshotRepository: SnapshotRepository,
        exceptionHandler: ExceptionHandler
    ): BatchRegenerateSnapshotHandlerFunctionFactory {
        return BatchRegenerateSnapshotHandlerFunctionFactory(
            stateAggregateFactory,
            eventStore,
            snapshotRepository,
            exceptionHandler
        )
    }

    @Bean
    fun regenerateStateEventFunctionFactory(
        eventStore: EventStore,
        stateEventCompensator: StateEventCompensator,
        exceptionHandler: ExceptionHandler
    ): RegenerateStateEventFunctionFactory {
        return RegenerateStateEventFunctionFactory(eventStore, stateEventCompensator, exceptionHandler)
    }

    @Bean
    fun domainEventCompensateHandlerFunctionFactory(
        eventCompensator: DomainEventCompensator,
        exceptionHandler: ExceptionHandler
    ): DomainEventCompensateHandlerFunctionFactory {
        return DomainEventCompensateHandlerFunctionFactory(eventCompensator, exceptionHandler)
    }

    @Bean
    fun stateEventCompensateHandlerFunctionFactory(
        eventCompensator: StateEventCompensator,
        exceptionHandler: ExceptionHandler
    ): StateEventCompensateHandlerFunctionFactory {
        return StateEventCompensateHandlerFunctionFactory(eventCompensator, exceptionHandler)
    }

    @Bean
    fun commandHandlerFunctionFactory(
        commandGateway: CommandGateway,
        exceptionHandler: ExceptionHandler,
    ): CommandHandlerFunctionFactory {
        return CommandHandlerFunctionFactory(commandGateway, exceptionHandler, DEFAULT_TIME_OUT)
    }

    @Bean
    fun loadEventStreamHandlerFunctionFactory(eventStore: EventStore): LoadEventStreamHandlerFunctionFactory {
        return LoadEventStreamHandlerFunctionFactory(eventStore)
    }

    @Bean
    fun routeHandlerFunctionRegistrar(factories: List<RouteHandlerFunctionFactory<*>>): RouteHandlerFunctionRegistrar {
        val registrar = RouteHandlerFunctionRegistrar()
        factories.forEach { registrar.register(it) }
        return registrar
    }

    @Bean
    fun commandRouterFunction(
        openApiBuilder: OpenAPIBuilder,
        routeHandlerFunctionRegistrar: RouteHandlerFunctionRegistrar
    ): RouterFunction<ServerResponse> {
        return RouterFunctionBuilder(
            openApiBuilder = openApiBuilder,
            routeHandlerFunctionRegistrar = routeHandlerFunctionRegistrar
        ).build()
    }
}
