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

package me.ahoo.wow.viewstore.starter

import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.openapi.RouterSpecs
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.query.event.EventStreamQueryGateway
import me.ahoo.wow.query.schema.QuerySchemaRegistration
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.WowAutoConfiguration
import me.ahoo.wow.spring.boot.starter.WowAutoConfiguration.Companion.WOW_CURRENT_BOUNDED_CONTEXT
import me.ahoo.wow.spring.boot.starter.openapi.OpenAPIAutoConfiguration
import me.ahoo.wow.spring.boot.starter.webflux.ConditionalOnWebfluxEnabled
import me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfiguration
import me.ahoo.wow.spring.query.eventStreamQueryGatewayBeanName
import me.ahoo.wow.spring.query.snapshotQueryGatewayBeanName
import me.ahoo.wow.viewstore.domain.preferences.ViewPreferences
import me.ahoo.wow.viewstore.domain.view.SharedBoardReferences
import me.ahoo.wow.viewstore.domain.view.View
import me.ahoo.wow.viewstore.domain.view.ViewState
import me.ahoo.wow.viewstore.starter.system.PropertiesSystemViewProvider
import me.ahoo.wow.viewstore.starter.system.SystemViewProvider
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteHandlerFunctionRegistrar
import me.ahoo.wow.webflux.route.command.CommandHandler
import me.ahoo.wow.webflux.route.command.extractor.CommandMessageExtractor
import me.ahoo.wow.webflux.route.policy.CommandWaitPolicy
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse

/**
 * Puts the view store into a Wow service: the `View` and `ViewPreferences` aggregates come with the domain on the
 * classpath (Wow routes them like the host's own, under `/view-store`); this adds what they need around them. Every
 * bean is named for the view store and touches only the view store's aggregates and paths, so the host's own
 * aggregates, routes and queries are left as they were.
 */
@AutoConfiguration(
    after = [WowAutoConfiguration::class, WebFluxAutoConfiguration::class, OpenAPIAutoConfiguration::class],
)
@ConditionalOnWowEnabled
@ConditionalOnViewStoreEnabled
@ConditionalOnWebfluxEnabled
@ConditionalOnClass(CommandHandler::class)
@EnableConfigurationProperties(ViewStoreProperties::class)
class ViewStoreAutoConfiguration {
    companion object {
        const val ROUTER_FUNCTION_BEAN_NAME = "viewStoreRouterFunction"
        private val viewNamedAggregate = View::class.java.aggregateRouteMetadata().aggregateMetadata.namedAggregate
        private val preferencesNamedAggregate =
            ViewPreferences::class.java.aggregateRouteMetadata().aggregateMetadata.namedAggregate
    }

    @Bean
    fun viewStoreErrorStatuses(): ViewStoreErrorStatuses {
        ViewStoreErrorStatuses.register()
        return ViewStoreErrorStatuses
    }

    @Bean
    fun viewStorePaths(
        @Qualifier(WOW_CURRENT_BOUNDED_CONTEXT) currentContext: NamedBoundedContext
    ): ViewStorePaths = ViewStorePaths(currentContext)

    @Bean
    fun viewStoreAppIdHeaderAppender(viewStorePaths: ViewStorePaths): ViewStoreAppIdHeaderAppender =
        ViewStoreAppIdHeaderAppender(viewStorePaths)

    @Bean
    fun viewStoreQueryPolicy(): ViewStoreQueryPolicy =
        ViewStoreQueryPolicy(setOf(viewNamedAggregate, preferencesNamedAggregate))

    @Bean
    @ConditionalOnMissingBean
    fun systemViewProvider(viewStoreProperties: ViewStoreProperties): SystemViewProvider =
        PropertiesSystemViewProvider(viewStoreProperties.systemViews)

    /** Opens `state.config.kind` and the panel references of `state.config` to snapshot queries. */
    @Bean
    fun viewStoreConfigQuerySchema(): QuerySchemaRegistration = ViewConfigQuerySchema.registration()

    @Suppress("UNCHECKED_CAST")
    @Bean
    @ConditionalOnMissingBean
    fun sharedBoardReferences(beanFactory: BeanFactory): SharedBoardReferences = SnapshotSharedBoardReferences {
        beanFactory.getBean(viewNamedAggregate.snapshotQueryGatewayBeanName()) as SnapshotQueryGateway<ViewState>
    }

    @Bean
    @Suppress("LongParameterList")
    fun viewStoreHandlers(
        systemViewProvider: SystemViewProvider,
        stateAggregateRepository: StateAggregateRepository,
        commandGateway: CommandGateway,
        commandMessageExtractor: CommandMessageExtractor,
        commandWaitPolicy: CommandWaitPolicy,
        exceptionHandler: RequestExceptionHandler,
        beanFactory: BeanFactory,
    ): ViewStoreHandlers = ViewStoreHandlers(
        systemViewProvider = systemViewProvider,
        stateAggregateRepository = stateAggregateRepository,
        commandHandler = CommandHandler(commandGateway, commandMessageExtractor, commandWaitPolicy),
        viewEventStreamQueryGateway = {
            beanFactory.getBean(
                viewNamedAggregate.eventStreamQueryGatewayBeanName(),
                EventStreamQueryGateway::class.java
            )
        },
        exceptionHandler = exceptionHandler,
    )

    @Bean
    @Suppress("LongParameterList")
    fun viewStoreAudienceHandlers(
        viewStorePaths: ViewStorePaths,
        viewStoreRouteGuard: ViewStoreRouteGuard,
        routeHandlerFunctionRegistrar: RouteHandlerFunctionRegistrar,
        stateAggregateRepository: StateAggregateRepository,
        commandGateway: CommandGateway,
        commandMessageExtractor: CommandMessageExtractor,
        commandWaitPolicy: CommandWaitPolicy,
        exceptionHandler: RequestExceptionHandler,
    ): ViewAudienceHandlers {
        val shareContract = viewStoreRouteGuard.openContracts.single {
            it.method == HttpMethod.PUT.name() && it.path == viewStorePaths.share
        }
        val shareDispatch = requireNotNull(routeHandlerFunctionRegistrar.getHttpFactory(shareContract.handlerKey)) {
            "No handler for [${shareContract.handlerKey}] of route [${shareContract.path}]."
        }.create(shareContract)
        return ViewAudienceHandlers(
            stateAggregateRepository = stateAggregateRepository,
            shareDispatch = shareDispatch,
            commandHandler = CommandHandler(commandGateway, commandMessageExtractor, commandWaitPolicy),
            exceptionHandler = exceptionHandler,
        )
    }

    @Bean(ROUTER_FUNCTION_BEAN_NAME)
    @Order(0)
    fun viewStoreRouterFunction(
        viewStorePaths: ViewStorePaths,
        viewStoreHandlers: ViewStoreHandlers,
        viewStoreAudienceHandlers: ViewAudienceHandlers,
    ): RouterFunction<ServerResponse> =
        ViewStoreRoutes.routerFunction(viewStorePaths, viewStoreHandlers, viewStoreAudienceHandlers)

    @Bean
    fun viewStoreRouteGuard(
        viewStorePaths: ViewStorePaths,
        routerSpecs: RouterSpecs,
    ): ViewStoreRouteGuard = ViewStoreRouteGuard(
        viewStorePaths,
        routerSpecs,
        setOf(viewNamedAggregate, preferencesNamedAggregate)
    )

    @Bean
    fun viewStoreWebFilter(
        viewStorePaths: ViewStorePaths,
        systemViewProvider: SystemViewProvider,
        viewStoreRouteGuard: ViewStoreRouteGuard,
    ): ViewStoreWebFilter = ViewStoreWebFilter(viewStorePaths, systemViewProvider, viewStoreRouteGuard)

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["org.springdoc.core.customizers.OpenApiCustomizer"])
    class ViewStoreOpenApiConfiguration {
        @Bean
        fun viewStoreOpenApiCustomizer(
            viewStorePaths: ViewStorePaths,
            viewStoreRouteGuard: ViewStoreRouteGuard,
        ): OpenApiCustomizer {
            val openApi = ViewStoreOpenApi(viewStorePaths)
            return OpenApiCustomizer {
                openApi.withoutClosedRoutes(it, viewStoreRouteGuard.closedContracts)
                openApi.merge(it)
            }
        }
    }
}
