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
import me.ahoo.wow.openapi.RouterSpecs
import me.ahoo.wow.query.SnapshotQueryServiceFactory
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.command.CommandAutoConfiguration
import me.ahoo.wow.spring.boot.starter.kafka.KafkaProperties
import me.ahoo.wow.spring.boot.starter.openapi.OpenAPIAutoConfiguration
import me.ahoo.wow.webflux.exception.DefaultExceptionHandler
import me.ahoo.wow.webflux.exception.ExceptionHandler
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.RouteHandlerFunctionRegistrar
import me.ahoo.wow.webflux.route.RouterFunctionBuilder
import me.ahoo.wow.webflux.route.bi.GenerateBIScriptHandlerFunctionFactory
import me.ahoo.wow.webflux.route.command.CommandHandlerFunctionFactory
import me.ahoo.wow.webflux.route.command.DEFAULT_TIME_OUT
import me.ahoo.wow.webflux.route.event.ArchiveAggregateIdHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.DomainEventCompensateHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.LoadEventStreamHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.state.ResendStateEventFunctionFactory
import me.ahoo.wow.webflux.route.event.state.StateEventCompensateHandlerFunctionFactory
import me.ahoo.wow.webflux.route.id.GlobalIdHandlerFunctionFactory
import me.ahoo.wow.webflux.route.metadata.GetWowMetadataHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.BatchRegenerateSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.CountSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.LoadSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.PagedQuerySnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.PagedQuerySnapshotStateHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.QuerySnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.QuerySnapshotStateHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.RegenerateSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.SingleSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.SingleSnapshotStateHandlerFunctionFactory
import me.ahoo.wow.webflux.route.state.AggregateTracingHandlerFunctionFactory
import me.ahoo.wow.webflux.route.state.IdsQueryAggregateHandlerFunctionFactory
import me.ahoo.wow.webflux.route.state.LoadAggregateHandlerFunctionFactory
import me.ahoo.wow.webflux.route.state.LoadVersionedAggregateHandlerFunctionFactory
import me.ahoo.wow.webflux.route.state.ScanAggregateHandlerFunctionFactory
import me.ahoo.wow.webflux.wait.CommandWaitHandlerFunctionFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.lang.Nullable
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
    companion object {
        const val COMMAND_WAIT_HANDLER_FUNCTION_FACTORY_BEAN_NAME = "commandWaitHandlerFunctionFactory"
        const val LOAD_AGGREGATE_HANDLER_FUNCTION_FACTORY_BEAN_NAME = "loadAggregateHandlerFunctionFactory"
        const val LOAD_VERSIONED_AGGREGATE_HANDLER_FUNCTION_FACTORY_BEAN_NAME =
            "loadVersionedAggregateHandlerFunctionFactory"
        const val IDS_QUERY_AGGREGATE_HANDLER_FUNCTION_FACTORY_BEAN_NAME =
            "idsQueryAggregateHandlerFunctionFactory"
        const val ARCHIVE_AGGREGATE_ID_HANDLER_FUNCTION_FACTORY_BEAN_NAME = "archiveAggregateIdHandlerFunctionFactory"
        const val SCAN_AGGREGATE_HANDLER_FUNCTION_FACTORY_BEAN_NAME = "scanAggregateHandlerFunctionFactory"
        const val AGGREGATE_TRACING_HANDLER_FUNCTION_FACTORY_BEAN_NAME = "aggregateTracingHandlerFunctionFactory"
        const val LOAD_SNAPSHOT_HANDLER_FUNCTION_FACTORY_BEAN_NAME = "loadSnapshotHandlerFunctionFactory"
        const val PAGED_QUERY_SNAPSHOT_HANDLER_FUNCTION_FACTORY_BEAN_NAME = "pagedQuerySnapshotHandlerFunctionFactory"
        const val PAGED_QUERY_SNAPSHOT_STATE_HANDLER_FUNCTION_FACTORY_BEAN_NAME =
            "pagedQuerySnapshotStateHandlerFunctionFactory"
        const val QUERY_SNAPSHOT_HANDLER_FUNCTION_FACTORY_BEAN_NAME = "querySnapshotHandlerFunctionFactory"
        const val QUERY_SNAPSHOT_STATE_HANDLER_FUNCTION_FACTORY_BEAN_NAME = "querySnapshotStateHandlerFunctionFactory"
        const val COUNT_SNAPSHOT_HANDLER_FUNCTION_FACTORY_BEAN_NAME = "countSnapshotHandlerFunctionFactory"
        const val SINGLE_SNAPSHOT_HANDLER_FUNCTION_FACTORY_BEAN_NAME = "singleSnapshotHandlerFunctionFactory"
        const val SINGLE_SNAPSHOT_STATE_HANDLER_FUNCTION_FACTORY_BEAN_NAME = "singleSnapshotStateHandlerFunctionFactory"
        const val REGENERATE_SNAPSHOT_HANDLER_FUNCTION_FACTORY_BEAN_NAME = "regenerateSnapshotHandlerFunctionFactory"
        const val BATCH_REGENERATE_SNAPSHOT_HANDLER_FUNCTION_FACTORY_BEAN_NAME =
            "batchRegenerateSnapshotHandlerFunctionFactory"
        const val RESEND_STATE_EVENT_FUNCTION_FACTORY_BEAN_NAME = "resendStateEventFunctionFactory"
        const val DOMAIN_EVENT_COMPENSATE_HANDLER_FUNCTION_FACTORY_BEAN_NAME =
            "domainEventCompensateHandlerFunctionFactory"
        const val STATE_EVENT_COMPENSATE_HANDLER_FUNCTION_FACTORY_BEAN_NAME =
            "stateEventCompensateHandlerFunctionFactory"
        const val COMMAND_HANDLER_FUNCTION_FACTORY_BEAN_NAME = "commandHandlerFunctionFactory"
        const val LOAD_EVENT_STREAM_HANDLER_FUNCTION_FACTORY_BEAN_NAME = "loadEventStreamHandlerFunctionFactory"
        const val GLOBAL_ID_HANDLER_FUNCTION_FACTORY_BEAN_NAME = "globalIdHandlerFunctionFactory"
        const val GENERATE_BI_SCRIPT_HANDLER_FUNCTION_FACTORY_BEAN_NAME = "generateBIScriptHandlerFunctionFactory"
        const val GET_WOW_METADATA_HANDLER_FUNCTION_FACTORY_BEAN_NAME = "getWowMetadataHandlerFunctionFactory"
    }

    @Bean
    @ConditionalOnMissingBean
    fun exceptionHandler(): ExceptionHandler {
        return DefaultExceptionHandler
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = [COMMAND_WAIT_HANDLER_FUNCTION_FACTORY_BEAN_NAME])
    fun commandWaitHandlerFunctionFactory(
        waitStrategyRegistrar: WaitStrategyRegistrar
    ): CommandWaitHandlerFunctionFactory {
        return CommandWaitHandlerFunctionFactory(waitStrategyRegistrar)
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = [LOAD_AGGREGATE_HANDLER_FUNCTION_FACTORY_BEAN_NAME])
    fun loadAggregateHandlerFunctionFactory(
        stateAggregateRepository: StateAggregateRepository,
        exceptionHandler: ExceptionHandler
    ): LoadAggregateHandlerFunctionFactory {
        return LoadAggregateHandlerFunctionFactory(stateAggregateRepository, exceptionHandler)
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = [LOAD_VERSIONED_AGGREGATE_HANDLER_FUNCTION_FACTORY_BEAN_NAME])
    fun loadVersionedAggregateHandlerFunctionFactory(
        stateAggregateRepository: StateAggregateRepository,
        exceptionHandler: ExceptionHandler
    ): LoadVersionedAggregateHandlerFunctionFactory {
        return LoadVersionedAggregateHandlerFunctionFactory(stateAggregateRepository, exceptionHandler)
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = [IDS_QUERY_AGGREGATE_HANDLER_FUNCTION_FACTORY_BEAN_NAME])
    fun idsQueryAggregateHandlerFunctionFactory(
        stateAggregateRepository: StateAggregateRepository,
        exceptionHandler: ExceptionHandler
    ): IdsQueryAggregateHandlerFunctionFactory {
        return IdsQueryAggregateHandlerFunctionFactory(stateAggregateRepository, exceptionHandler)
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = [ARCHIVE_AGGREGATE_ID_HANDLER_FUNCTION_FACTORY_BEAN_NAME])
    fun archiveAggregateIdHandlerFunctionFactory(
        eventStore: EventStore,
        exceptionHandler: ExceptionHandler
    ): ArchiveAggregateIdHandlerFunctionFactory {
        return ArchiveAggregateIdHandlerFunctionFactory(eventStore, exceptionHandler)
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = [SCAN_AGGREGATE_HANDLER_FUNCTION_FACTORY_BEAN_NAME])
    fun scanAggregateHandlerFunctionFactory(
        stateAggregateRepository: StateAggregateRepository,
        eventStore: EventStore,
        exceptionHandler: ExceptionHandler
    ): ScanAggregateHandlerFunctionFactory {
        return ScanAggregateHandlerFunctionFactory(stateAggregateRepository, eventStore, exceptionHandler)
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = [AGGREGATE_TRACING_HANDLER_FUNCTION_FACTORY_BEAN_NAME])
    fun aggregateTracingHandlerFunctionFactory(
        eventStore: EventStore,
        exceptionHandler: ExceptionHandler
    ): AggregateTracingHandlerFunctionFactory {
        return AggregateTracingHandlerFunctionFactory(eventStore, exceptionHandler)
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = [LOAD_SNAPSHOT_HANDLER_FUNCTION_FACTORY_BEAN_NAME])
    fun loadSnapshotHandlerFunctionFactory(
        snapshotRepository: SnapshotRepository,
        exceptionHandler: ExceptionHandler
    ): LoadSnapshotHandlerFunctionFactory {
        return LoadSnapshotHandlerFunctionFactory(snapshotRepository, exceptionHandler)
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = [QUERY_SNAPSHOT_HANDLER_FUNCTION_FACTORY_BEAN_NAME])
    fun querySnapshotHandlerFunctionFactory(
        snapshotQueryServiceFactory: SnapshotQueryServiceFactory,
        exceptionHandler: ExceptionHandler
    ): QuerySnapshotHandlerFunctionFactory {
        return QuerySnapshotHandlerFunctionFactory(snapshotQueryServiceFactory, exceptionHandler)
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = [QUERY_SNAPSHOT_STATE_HANDLER_FUNCTION_FACTORY_BEAN_NAME])
    fun querySnapshotStateHandlerFunctionFactory(
        snapshotQueryServiceFactory: SnapshotQueryServiceFactory,
        exceptionHandler: ExceptionHandler
    ): QuerySnapshotStateHandlerFunctionFactory {
        return QuerySnapshotStateHandlerFunctionFactory(snapshotQueryServiceFactory, exceptionHandler)
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = [PAGED_QUERY_SNAPSHOT_HANDLER_FUNCTION_FACTORY_BEAN_NAME])
    fun pagedQuerySnapshotHandlerFunctionFactory(
        snapshotQueryServiceFactory: SnapshotQueryServiceFactory,
        exceptionHandler: ExceptionHandler
    ): PagedQuerySnapshotHandlerFunctionFactory {
        return PagedQuerySnapshotHandlerFunctionFactory(snapshotQueryServiceFactory, exceptionHandler)
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = [PAGED_QUERY_SNAPSHOT_STATE_HANDLER_FUNCTION_FACTORY_BEAN_NAME])
    fun pagedQuerySnapshotStateHandlerFunctionFactory(
        snapshotQueryServiceFactory: SnapshotQueryServiceFactory,
        exceptionHandler: ExceptionHandler
    ): PagedQuerySnapshotStateHandlerFunctionFactory {
        return PagedQuerySnapshotStateHandlerFunctionFactory(snapshotQueryServiceFactory, exceptionHandler)
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = [SINGLE_SNAPSHOT_HANDLER_FUNCTION_FACTORY_BEAN_NAME])
    fun singleSnapshotHandlerFunctionFactory(
        snapshotQueryServiceFactory: SnapshotQueryServiceFactory,
        exceptionHandler: ExceptionHandler
    ): SingleSnapshotHandlerFunctionFactory {
        return SingleSnapshotHandlerFunctionFactory(snapshotQueryServiceFactory, exceptionHandler)
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = [SINGLE_SNAPSHOT_STATE_HANDLER_FUNCTION_FACTORY_BEAN_NAME])
    fun singleSnapshotStateHandlerFunctionFactory(
        snapshotQueryServiceFactory: SnapshotQueryServiceFactory,
        exceptionHandler: ExceptionHandler
    ): SingleSnapshotStateHandlerFunctionFactory {
        return SingleSnapshotStateHandlerFunctionFactory(snapshotQueryServiceFactory, exceptionHandler)
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = [COUNT_SNAPSHOT_HANDLER_FUNCTION_FACTORY_BEAN_NAME])
    fun countSnapshotHandlerFunctionFactory(
        snapshotQueryServiceFactory: SnapshotQueryServiceFactory,
        exceptionHandler: ExceptionHandler
    ): CountSnapshotHandlerFunctionFactory {
        return CountSnapshotHandlerFunctionFactory(snapshotQueryServiceFactory, exceptionHandler)
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = [REGENERATE_SNAPSHOT_HANDLER_FUNCTION_FACTORY_BEAN_NAME])
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
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = [BATCH_REGENERATE_SNAPSHOT_HANDLER_FUNCTION_FACTORY_BEAN_NAME])
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
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = [RESEND_STATE_EVENT_FUNCTION_FACTORY_BEAN_NAME])
    fun resendStateEventFunctionFactory(
        eventStore: EventStore,
        stateEventCompensator: StateEventCompensator,
        exceptionHandler: ExceptionHandler
    ): ResendStateEventFunctionFactory {
        return ResendStateEventFunctionFactory(eventStore, stateEventCompensator, exceptionHandler)
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = [DOMAIN_EVENT_COMPENSATE_HANDLER_FUNCTION_FACTORY_BEAN_NAME])
    fun domainEventCompensateHandlerFunctionFactory(
        eventCompensator: DomainEventCompensator,
        exceptionHandler: ExceptionHandler
    ): DomainEventCompensateHandlerFunctionFactory {
        return DomainEventCompensateHandlerFunctionFactory(eventCompensator, exceptionHandler)
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = [STATE_EVENT_COMPENSATE_HANDLER_FUNCTION_FACTORY_BEAN_NAME])
    fun stateEventCompensateHandlerFunctionFactory(
        eventCompensator: StateEventCompensator,
        exceptionHandler: ExceptionHandler
    ): StateEventCompensateHandlerFunctionFactory {
        return StateEventCompensateHandlerFunctionFactory(eventCompensator, exceptionHandler)
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = [COMMAND_HANDLER_FUNCTION_FACTORY_BEAN_NAME])
    fun commandHandlerFunctionFactory(
        commandGateway: CommandGateway,
        exceptionHandler: ExceptionHandler,
    ): CommandHandlerFunctionFactory {
        return CommandHandlerFunctionFactory(commandGateway, exceptionHandler, DEFAULT_TIME_OUT)
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = [LOAD_EVENT_STREAM_HANDLER_FUNCTION_FACTORY_BEAN_NAME])
    fun loadEventStreamHandlerFunctionFactory(eventStore: EventStore): LoadEventStreamHandlerFunctionFactory {
        return LoadEventStreamHandlerFunctionFactory(eventStore)
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = [GLOBAL_ID_HANDLER_FUNCTION_FACTORY_BEAN_NAME])
    fun globalIdHandlerFunctionFactory(): GlobalIdHandlerFunctionFactory {
        return GlobalIdHandlerFunctionFactory()
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = [GENERATE_BI_SCRIPT_HANDLER_FUNCTION_FACTORY_BEAN_NAME])
    fun generateBIScriptHandlerFunctionFactory(
        @Nullable kafkaProperties: KafkaProperties?
    ): GenerateBIScriptHandlerFunctionFactory {
        if (kafkaProperties == null) {
            return GenerateBIScriptHandlerFunctionFactory()
        }
        return GenerateBIScriptHandlerFunctionFactory(
            kafkaProperties.bootstrapServersToString(),
            kafkaProperties.topicPrefix
        )
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = [GET_WOW_METADATA_HANDLER_FUNCTION_FACTORY_BEAN_NAME])
    fun getWowMetadataHandlerFunctionFactory(): GetWowMetadataHandlerFunctionFactory {
        return GetWowMetadataHandlerFunctionFactory()
    }

    @Bean
    fun routeHandlerFunctionRegistrar(
        factories: ObjectProvider<RouteHandlerFunctionFactory<*>>
    ): RouteHandlerFunctionRegistrar {
        val registrar = RouteHandlerFunctionRegistrar()
        factories.orderedStream().forEach { registrar.register(it) }
        return registrar
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
