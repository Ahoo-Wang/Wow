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

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.models.OpenAPI
import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.modeling.getContextAliasPrefix
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.openapi.RouterSpecs
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.query.event.EventStreamQueryGateway
import me.ahoo.wow.query.schema.QuerySchemaRegistration
import me.ahoo.wow.query.schema.UnavailableQueryStorageAdapter
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.WowAutoConfiguration
import me.ahoo.wow.spring.boot.starter.WowAutoConfiguration.Companion.WOW_CURRENT_BOUNDED_CONTEXT
import me.ahoo.wow.spring.boot.starter.bi.BiScriptAggregateExclusion
import me.ahoo.wow.spring.boot.starter.openapi.ConditionalOnOpenAPIEnabled
import me.ahoo.wow.spring.boot.starter.openapi.OpenAPIAutoConfiguration
import me.ahoo.wow.spring.boot.starter.openapi.WowOpenApiCustomizer
import me.ahoo.wow.spring.boot.starter.webflux.ConditionalOnWebfluxEnabled
import me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfiguration
import me.ahoo.wow.spring.query.eventStreamQueryGatewayBeanName
import me.ahoo.wow.spring.query.snapshotQueryGatewayBeanName
import me.ahoo.wow.viewstore.domain.preferences.ViewPreferences
import me.ahoo.wow.viewstore.domain.view.SharedBoardReferences
import me.ahoo.wow.viewstore.domain.view.View
import me.ahoo.wow.viewstore.domain.view.ViewState
import me.ahoo.wow.viewstore.starter.system.PropertiesSystemViewProvider
import me.ahoo.wow.viewstore.starter.system.SnapshotStoredSystemViewSource
import me.ahoo.wow.viewstore.starter.system.StoredSystemViewSource
import me.ahoo.wow.viewstore.starter.system.StoredSystemViews
import me.ahoo.wow.viewstore.starter.system.SystemViewProvider
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteHandlerFunctionRegistrar
import me.ahoo.wow.webflux.route.command.CommandHandler
import me.ahoo.wow.webflux.route.command.extractor.CommandMessageExtractor
import me.ahoo.wow.webflux.route.policy.CommandWaitPolicy
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.env.Environment
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
        private val log = KotlinLogging.logger {}
        const val ROUTER_FUNCTION_BEAN_NAME = "viewStoreRouterFunction"
        private val viewNamedAggregate = View::class.java.aggregateRouteMetadata().aggregateMetadata.namedAggregate
        private val preferencesNamedAggregate =
            ViewPreferences::class.java.aggregateRouteMetadata().aggregateMetadata.namedAggregate
    }

    @Bean("viewStoreErrorStatuses")
    internal fun viewStoreErrorStatuses(): ViewStoreErrorStatuses {
        ViewStoreErrorStatuses.register()
        return ViewStoreErrorStatuses
    }

    @Bean("viewStorePaths")
    internal fun viewStorePaths(
        @Qualifier(WOW_CURRENT_BOUNDED_CONTEXT) currentContext: NamedBoundedContext
    ): ViewStorePaths = ViewStorePaths(currentContext)

    @Bean("viewStoreAppIdHeaderAppender")
    internal fun viewStoreAppIdHeaderAppender(viewStorePaths: ViewStorePaths): ViewStoreAppIdHeaderAppender =
        ViewStoreAppIdHeaderAppender(viewStorePaths)

    @Bean("viewStoreQueryPolicy")
    internal fun viewStoreQueryPolicy(): ViewStoreQueryPolicy =
        ViewStoreQueryPolicy(setOf(viewNamedAggregate, preferencesNamedAggregate))

    @Bean("viewStoreScopeContributor")
    internal fun viewStoreScopeContributor(): ViewStoreScopeContributor =
        ViewStoreScopeContributor(setOf(viewNamedAggregate, preferencesNamedAggregate))

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

    /**
     * The system views stored under `(platform)/(system)`, from the view snapshots, when the host has a snapshot
     * query backend for views; otherwise none (Wow's fallback backend answers every query with an error, which would
     * take the configured system views down with it), with one warning.
     */
    @OptIn(WowSpi::class) // Asks the views' query backend binding whether its storage is available.
    @Suppress("UNCHECKED_CAST")
    @Bean
    @ConditionalOnMissingBean
    fun storedSystemViewSource(
        snapshotQueryBackendFactories: ObjectProvider<SnapshotQueryBackendFactory>,
        beanFactory: BeanFactory,
    ): StoredSystemViewSource {
        val factory = snapshotQueryBackendFactories.ifAvailable
        if (factory == null || factory.create(viewNamedAggregate).storage is UnavailableQueryStorageAdapter) {
            log.warn {
                "The view store has no snapshot query backend for [$viewNamedAggregate]: stored system views are " +
                    "off. Only the configured system views are served, and views written to " +
                    "[tenant/(platform)/owner/(system)] are not served as system views."
            }
            return StoredSystemViewSource.NONE
        }
        return SnapshotStoredSystemViewSource {
            beanFactory.getBean(viewNamedAggregate.snapshotQueryGatewayBeanName()) as SnapshotQueryGateway<ViewState>
        }
    }

    @Bean("viewStoreStoredSystemViews")
    internal fun viewStoreStoredSystemViews(
        systemViewProvider: SystemViewProvider,
        storedSystemViewSource: StoredSystemViewSource,
    ): StoredSystemViews = StoredSystemViews(systemViewProvider, storedSystemViewSource)

    @Bean("viewStoreHandlers")
    @Suppress("LongParameterList")
    internal fun viewStoreHandlers(
        viewStoreStoredSystemViews: StoredSystemViews,
        stateAggregateRepository: StateAggregateRepository,
        commandGateway: CommandGateway,
        commandMessageExtractor: CommandMessageExtractor,
        commandWaitPolicy: CommandWaitPolicy,
        exceptionHandler: RequestExceptionHandler,
        beanFactory: BeanFactory,
    ): ViewStoreHandlers = ViewStoreHandlers(
        systemViews = viewStoreStoredSystemViews,
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

    @Bean("viewStoreAudienceHandlers")
    @Suppress("LongParameterList")
    internal fun viewStoreAudienceHandlers(
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
    internal fun viewStoreRouterFunction(
        viewStorePaths: ViewStorePaths,
        viewStoreHandlers: ViewStoreHandlers,
        viewStoreAudienceHandlers: ViewAudienceHandlers,
    ): RouterFunction<ServerResponse> =
        ViewStoreRoutes.routerFunction(viewStorePaths, viewStoreHandlers, viewStoreAudienceHandlers)

    @Bean("viewStoreRouteGuard")
    internal fun viewStoreRouteGuard(
        viewStorePaths: ViewStorePaths,
        routerSpecs: RouterSpecs,
    ): ViewStoreRouteGuard = ViewStoreRouteGuard(
        viewStorePaths,
        routerSpecs,
        setOf(viewNamedAggregate, preferencesNamedAggregate)
    )

    @Bean("viewStoreWebFilter")
    internal fun viewStoreWebFilter(
        viewStorePaths: ViewStorePaths,
        systemViewProvider: SystemViewProvider,
        viewStoreRouteGuard: ViewStoreRouteGuard,
    ): ViewStoreWebFilter = ViewStoreWebFilter(viewStorePaths, systemViewProvider, viewStoreRouteGuard)

    /**
     * The view store's own Kafka topic prefix, when [ViewStoreKafkaProperties.TOPIC_PREFIX] is set to text (a blank
     * prefix is no prefix). Its beans are static, so that Spring creates the `BeanPostProcessor` before, and without,
     * this class.
     */
    @Suppress("UtilityClassWithPublicConstructor")
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["me.ahoo.wow.kafka.CommandTopicConverter"])
    @Conditional(OnViewStoreTopicPrefixCondition::class)
    class ViewStoreKafkaConfiguration {
        companion object {
            @JvmStatic
            @Bean("viewStoreTopicConverterPostProcessor")
            internal fun viewStoreTopicConverterPostProcessor(
                environment: Environment,
            ): ViewStoreTopicConverterPostProcessor =
                ViewStoreTopicConverterPostProcessor(requireNotNull(environment.viewStoreTopicPrefix()))

            /**
             * The BI script reads every aggregate's topics under BI's one `topic-prefix`, which does not name the
             * view store's topics under their own: the view store is left out of it.
             */
            @JvmStatic
            @Bean
            fun viewStoreBiScriptAggregateExclusion(): BiScriptAggregateExclusion =
                BiScriptAggregateExclusion { it.isViewStoreAggregate() }
        }
    }

    /**
     * Warns at startup when the view store's Kafka topics are Wow's default ones, which every other deployment of the
     * view store left at its defaults shares ([viewStoreSharedTopicsWarning]).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["me.ahoo.wow.kafka.KafkaCommandBus"])
    class ViewStoreSharedTopicsConfiguration {
        @Bean("viewStoreSharedTopicsWarning")
        internal fun viewStoreSharedTopicsWarning(environment: Environment): ViewStoreSharedTopicsWarning =
            ViewStoreSharedTopicsWarning(environment)
    }

    /**
     * Documents the view store's own routes when Wow's OpenAPI document is served (as Wow's springdoc customizer). The
     * customizer is created eagerly, even under `spring.main.lazy-initialization`, so their schemas are generated at
     * startup and never on a request thread. It runs after [WowOpenApiCustomizer], so the routes it takes out of the
     * document as closed are already there.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnOpenAPIEnabled
    @ConditionalOnClass(name = ["org.springdoc.core.customizers.OpenApiCustomizer"])
    class ViewStoreOpenApiConfiguration {
        @Bean("viewStoreOpenApiCustomizer")
        @Lazy(false)
        internal fun viewStoreOpenApiCustomizer(
            @Qualifier(WOW_CURRENT_BOUNDED_CONTEXT) currentContext: NamedBoundedContext,
            viewStorePaths: ViewStorePaths,
            viewStoreRouteGuard: ViewStoreRouteGuard,
        ): OpenApiCustomizer {
            // Generates the schemas at startup, so a document built on a request thread generates none.
            val openApi = ViewStoreOpenApi(viewStorePaths, currentContext.getContextAliasPrefix()).render()
            return object : OpenApiCustomizer, Ordered {
                override fun customise(document: OpenAPI) {
                    openApi.withoutClosedRoutes(document, viewStoreRouteGuard.closedContracts)
                    openApi.merge(document)
                }

                override fun getOrder(): Int = WowOpenApiCustomizer.ORDER + 1
            }
        }
    }
}
