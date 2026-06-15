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
import me.ahoo.wow.command.factory.CommandMessageFactory
import me.ahoo.wow.command.wait.WaitCoordinator
import me.ahoo.wow.event.compensation.StateEventCompensator
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.messaging.compensation.EventCompensateSupporter
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.openapi.RouterSpecs
import me.ahoo.wow.query.event.filter.EventStreamQueryHandler
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryHandler
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.ENABLED_SUFFIX_KEY
import me.ahoo.wow.spring.boot.starter.command.CommandAutoConfiguration
import me.ahoo.wow.spring.boot.starter.kafka.KafkaProperties
import me.ahoo.wow.spring.boot.starter.openapi.OpenAPIAutoConfiguration
import me.ahoo.wow.spring.boot.starter.webflux.route.CommandRouteModule
import me.ahoo.wow.spring.boot.starter.webflux.route.EventRouteModule
import me.ahoo.wow.spring.boot.starter.webflux.route.GlobalRouteModule
import me.ahoo.wow.spring.boot.starter.webflux.route.QueryRouteModule
import me.ahoo.wow.spring.boot.starter.webflux.route.SnapshotRouteModule
import me.ahoo.wow.spring.boot.starter.webflux.route.StateRouteModule
import me.ahoo.wow.spring.boot.starter.webflux.route.WebFluxRouteModule
import me.ahoo.wow.webflux.exception.DefaultGlobalExceptionHandler
import me.ahoo.wow.webflux.exception.DefaultWebFluxErrorStrategy
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.exception.WebFluxErrorStrategy
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.RouteHandlerFunctionRegistrar
import me.ahoo.wow.webflux.route.RouterFunctionBuilder
import me.ahoo.wow.webflux.route.command.DEFAULT_TIME_OUT
import me.ahoo.wow.webflux.route.command.appender.CommandRequestExtendHeaderAppender
import me.ahoo.wow.webflux.route.command.appender.CommandRequestHeaderAppender
import me.ahoo.wow.webflux.route.command.appender.CommandRequestRemoteIpHeaderAppender
import me.ahoo.wow.webflux.route.command.appender.CommandRequestUserAgentHeaderAppender
import me.ahoo.wow.webflux.route.command.extractor.CommandBuilderExtractor
import me.ahoo.wow.webflux.route.command.extractor.CommandMessageExtractor
import me.ahoo.wow.webflux.route.command.extractor.DefaultCommandBuilderExtractor
import me.ahoo.wow.webflux.route.command.extractor.DefaultCommandMessageExtractor
import me.ahoo.wow.webflux.route.policy.BatchExecutionPolicy
import me.ahoo.wow.webflux.route.policy.CommandWaitPolicy
import me.ahoo.wow.webflux.route.policy.TracingPolicy
import me.ahoo.wow.webflux.route.query.DefaultRewriteRequestCondition
import me.ahoo.wow.webflux.route.query.RewriteRequestCondition
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.server.WebExceptionHandler

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
    fun webFluxErrorStrategy(): WebFluxErrorStrategy {
        return DefaultWebFluxErrorStrategy
    }

    @Bean
    @ConditionalOnMissingBean
    fun exceptionHandler(errorStrategy: WebFluxErrorStrategy): RequestExceptionHandler {
        return WebFluxRequestExceptionHandler(errorStrategy)
    }

    @Bean
    @ConditionalOnMissingBean
    fun commandWaitPolicy(): CommandWaitPolicy {
        return CommandWaitPolicy(DEFAULT_TIME_OUT)
    }

    @Bean
    @ConditionalOnMissingBean
    fun tracingPolicy(): TracingPolicy {
        return TracingPolicy()
    }

    @Bean
    @ConditionalOnMissingBean
    fun batchExecutionPolicy(webFluxProperties: WebFluxProperties): BatchExecutionPolicy {
        return BatchExecutionPolicy(
            concurrency = webFluxProperties.batch.concurrency,
            prefetch = webFluxProperties.batch.prefetch,
        )
    }

    @Bean
    @ConditionalOnWebfluxGlobalErrorEnabled
    fun globalExceptionHandler(errorStrategy: WebFluxErrorStrategy): WebExceptionHandler {
        return DefaultGlobalExceptionHandler(errorStrategy)
    }

    @Bean
    fun commandRequestExtendHeaderAppender(): CommandRequestExtendHeaderAppender {
        return CommandRequestExtendHeaderAppender
    }

    @Bean
    @ConditionalOnMissingBean
    fun commandBuilderExtractor(): CommandBuilderExtractor {
        return DefaultCommandBuilderExtractor
    }

    @Bean
    @ConditionalOnProperty(
        value = ["${WebFluxProperties.COMMAND_REQUEST_APPENDER_PREFIX}.agent$ENABLED_SUFFIX_KEY"],
        matchIfMissing = true,
        havingValue = "true"
    )
    fun commandRequestUserAgentHeaderAppender(): CommandRequestUserAgentHeaderAppender {
        return CommandRequestUserAgentHeaderAppender
    }

    @Bean
    @ConditionalOnProperty(
        value = ["${WebFluxProperties.COMMAND_REQUEST_APPENDER_PREFIX}.ip$ENABLED_SUFFIX_KEY"],
        matchIfMissing = true,
        havingValue = "true"
    )
    fun commandRequestRemoteIpHeaderAppender(): CommandRequestRemoteIpHeaderAppender {
        return CommandRequestRemoteIpHeaderAppender
    }

    @Bean
    @ConditionalOnMissingBean
    fun commandMessageExtractor(
        commandMessageFactory: CommandMessageFactory,
        commandBuilderExtractor: CommandBuilderExtractor,
        commandRequestHeaderAppenderObjectProvider: ObjectProvider<CommandRequestHeaderAppender>
    ): CommandMessageExtractor {
        return DefaultCommandMessageExtractor(
            commandMessageFactory = commandMessageFactory,
            commandBuilderExtractor = commandBuilderExtractor,
            commandRequestHeaderAppends = commandRequestHeaderAppenderObjectProvider.toList<CommandRequestHeaderAppender>()
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun rewriteRequestCondition(): RewriteRequestCondition {
        return DefaultRewriteRequestCondition
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean
    fun commandRouteModule(
        waitCoordinator: WaitCoordinator,
        commandGateway: CommandGateway,
        commandMessageExtractor: CommandMessageExtractor,
        exceptionHandler: RequestExceptionHandler,
        commandWaitPolicy: CommandWaitPolicy
    ): CommandRouteModule {
        return CommandRouteModule(
            waitCoordinator = waitCoordinator,
            commandGateway = commandGateway,
            commandMessageExtractor = commandMessageExtractor,
            exceptionHandler = exceptionHandler,
            commandWaitPolicy = commandWaitPolicy
        )
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean
    fun stateRouteModule(
        stateAggregateRepository: StateAggregateRepository,
        stateAggregateFactory: StateAggregateFactory,
        eventStore: EventStore,
        exceptionHandler: RequestExceptionHandler,
        tracingPolicy: TracingPolicy
    ): StateRouteModule {
        return StateRouteModule(
            stateAggregateRepository = stateAggregateRepository,
            stateAggregateFactory = stateAggregateFactory,
            eventStore = eventStore,
            exceptionHandler = exceptionHandler,
            tracingPolicy = tracingPolicy
        )
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean
    fun queryRouteModule(
        snapshotQueryHandler: SnapshotQueryHandler,
        eventStreamQueryHandler: EventStreamQueryHandler,
        rewriteRequestCondition: RewriteRequestCondition,
        exceptionHandler: RequestExceptionHandler
    ): QueryRouteModule {
        return QueryRouteModule(
            snapshotQueryHandler = snapshotQueryHandler,
            eventStreamQueryHandler = eventStreamQueryHandler,
            rewriteRequestCondition = rewriteRequestCondition,
            exceptionHandler = exceptionHandler
        )
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean
    fun snapshotRouteModule(
        stateAggregateFactory: StateAggregateFactory,
        eventStore: EventStore,
        snapshotRepository: SnapshotRepository,
        exceptionHandler: RequestExceptionHandler,
        batchExecutionPolicy: BatchExecutionPolicy
    ): SnapshotRouteModule {
        return SnapshotRouteModule(
            stateAggregateFactory = stateAggregateFactory,
            eventStore = eventStore,
            snapshotRepository = snapshotRepository,
            exceptionHandler = exceptionHandler,
            batchExecutionPolicy = batchExecutionPolicy
        )
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean
    fun eventRouteModule(
        snapshotRepository: SnapshotRepository,
        stateEventCompensator: StateEventCompensator,
        eventCompensateSupporter: EventCompensateSupporter,
        exceptionHandler: RequestExceptionHandler,
        batchExecutionPolicy: BatchExecutionPolicy
    ): EventRouteModule {
        return EventRouteModule(
            snapshotRepository = snapshotRepository,
            stateEventCompensator = stateEventCompensator,
            eventCompensateSupporter = eventCompensateSupporter,
            exceptionHandler = exceptionHandler,
            batchExecutionPolicy = batchExecutionPolicy
        )
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean
    fun globalRouteModule(
        kafkaProperties: ObjectProvider<KafkaProperties>
    ): GlobalRouteModule {
        return GlobalRouteModule(kafkaProperties.getIfAvailable())
    }

    @Bean
    fun routeHandlerFunctionRegistrar(
        routeModules: ObjectProvider<WebFluxRouteModule>,
        factories: ObjectProvider<RouteHandlerFunctionFactory<*>>
    ): RouteHandlerFunctionRegistrar {
        val mergedFactories = mutableListOf<RouteHandlerFunctionFactory<*>>()
        routeModules.orderedStream().forEach { routeModule ->
            mergedFactories.addAll(routeModule.factories)
        }
        factories.orderedStream().forEach { factory ->
            mergedFactories.add(factory)
        }
        return RouteHandlerFunctionRegistrar(mergedFactories)
    }

    @Bean
    fun commandRouterFunction(
        routerSpecs: RouterSpecs,
        routeHandlerFunctionRegistrar: RouteHandlerFunctionRegistrar
    ): RouterFunction<ServerResponse> {
        return RouterFunctionBuilder(
            routerSpecs = routerSpecs,
            routeHandlerFunctionRegistrar = routeHandlerFunctionRegistrar
        ).build()
    }
}
