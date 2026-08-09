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

@file:OptIn(
    me.ahoo.wow.query.backend.ExperimentalQueryBackendApi::class,
    me.ahoo.wow.query.cursor.ExperimentalQueryCursorApi::class,
    me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class,
)

package me.ahoo.wow.spring.boot.starter.query

import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.query.analytics.AnalyticsQueryServiceFactory
import me.ahoo.wow.query.analytics.AnalyticsQueryTrustedContextResolver
import me.ahoo.wow.query.analytics.CompositeAnalyticsQueryTrustedContextResolver
import me.ahoo.wow.query.backend.QueryBackendComposition
import me.ahoo.wow.query.cursor.QueryCursorLeaseConfiguration
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.gateway.AnalyticsQueryGateway
import me.ahoo.wow.query.gateway.CompositeQueryTrustedContextResolver
import me.ahoo.wow.query.gateway.QueryAuthorityResolver
import me.ahoo.wow.query.gateway.QueryCallResolver
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryExecutionMode
import me.ahoo.wow.query.gateway.QueryExecutionProfiles
import me.ahoo.wow.query.gateway.QueryGateway
import me.ahoo.wow.query.gateway.QueryGatewayConfiguration
import me.ahoo.wow.query.gateway.QueryGatewayRuntime
import me.ahoo.wow.query.gateway.QueryLegacyDialectResolver
import me.ahoo.wow.query.gateway.QueryOperation
import me.ahoo.wow.query.gateway.QueryResultMaterializer
import me.ahoo.wow.query.gateway.QueryResultMaterializers
import me.ahoo.wow.query.gateway.QueryRuntimeHealthObserver
import me.ahoo.wow.query.gateway.QueryShadowConfiguration
import me.ahoo.wow.query.gateway.QueryShadowObserver
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.query.gateway.QueryTrustedContextResolver
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.WowAutoConfiguration
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.EventStreamQueryServiceFactoryBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.SnapshotQueryServiceFactoryBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.StorageRoutingAutoConfiguration
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.StorageRoutingProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreProperties
import me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfiguration
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Installs the Query Gateway as the single framework-managed application query facade.
 *
 * Raw MongoDB/Elasticsearch/custom factories remain storage bindings and are never injected back as the facade.
 */
@AutoConfiguration(
    after = [
        QueryAutoConfiguration::class,
        StorageRoutingAutoConfiguration::class,
        WebFluxAutoConfiguration::class,
    ],
)
@ConditionalOnWowEnabled
class QueryGatewayAutoConfiguration {
    @Bean
    internal fun storageBindingQueryRawServiceRegistry(
        @Qualifier(WowAutoConfiguration.WOW_CURRENT_BOUNDED_CONTEXT)
        namedBoundedContext: NamedBoundedContext,
        storageRoutingProperties: StorageRoutingProperties,
        eventStoreProperties: EventStoreProperties,
        snapshotProperties: SnapshotProperties,
        eventStreamBindings: List<EventStreamQueryServiceFactoryBinding>,
        snapshotBindings: List<SnapshotQueryServiceFactoryBinding>,
    ): StorageBindingQueryRawServiceRegistry =
        StorageBindingQueryRawServiceRegistry(
            contextName = namedBoundedContext.contextName,
            storageRoutingProperties = storageRoutingProperties,
            eventStreamBindings = eventStreamBindings,
            snapshotBindings = snapshotBindings,
            snapshotEnabled = snapshotProperties.enabled,
            defaultEventStorage = eventStoreProperties.storage,
            defaultSnapshotStorage = snapshotProperties.storage,
        )

    @Bean
    @ConditionalOnMissingBean(QueryGatewayConfiguration::class)
    fun queryGatewayConfiguration(): QueryGatewayConfiguration = QueryGatewayConfiguration()

    @Bean
    @ConditionalOnMissingBean(QueryBackendComposition::class)
    internal fun storageRoutedQueryBackendComposition(
        rawServiceRegistry: StorageBindingQueryRawServiceRegistry,
        sources: List<StorageQueryBackendSource>,
        configuration: QueryGatewayConfiguration,
        executionProfilesProvider: ObjectProvider<QueryExecutionProfiles>,
    ): QueryBackendComposition {
        val profiles = executionProfilesProvider.getIfAvailable { QueryExecutionProfiles.fixed(configuration) }
        return StorageRoutedQueryBackendComposition.create(
            sources = sources,
            shouldPrepare = { target ->
                RECORD_QUERY_OPERATIONS.any { operation ->
                    profiles.resolve(target, operation).executionMode != QueryExecutionMode.LEGACY
                }
            },
            storageResolver = rawServiceRegistry::resolveStorage,
        )
    }

    @Bean
    @ConditionalOnMissingBean(QueryGatewayRuntime::class)
    @ConditionalOnQueryGatewayWiring
    internal fun queryGatewayRuntime(
        rawServiceRegistry: StorageBindingQueryRawServiceRegistry,
        dialectResolverProvider: ObjectProvider<QueryLegacyDialectResolver>,
        authorityResolverProvider: ObjectProvider<QueryAuthorityResolver>,
        callResolverProvider: ObjectProvider<QueryCallResolver>,
        trustedContextResolvers: List<QueryTrustedContextResolver>,
        analyticsTrustedContextResolvers: List<AnalyticsQueryTrustedContextResolver>,
        configuration: QueryGatewayConfiguration,
        executionProfilesProvider: ObjectProvider<QueryExecutionProfiles>,
        shadowConfigurationProvider: ObjectProvider<QueryShadowConfiguration>,
        shadowObserverProvider: ObjectProvider<QueryShadowObserver>,
        runtimeHealthObserverProvider: ObjectProvider<QueryRuntimeHealthObserver>,
        backendCompositionProvider: ObjectProvider<QueryBackendComposition>,
        cursorLeaseConfigurationProvider: ObjectProvider<QueryCursorLeaseConfiguration>,
        customResultMaterializers: List<QueryResultMaterializer<*>>,
    ): QueryGatewayRuntime {
        val aggregateTypes = MetadataSearcher.namedAggregateType
        val standardMaterializers = createStandardMaterializers(aggregateTypes)
        val dialectResolver = dialectResolverProvider.getIfAvailable {
            QueryLegacyDialectResolver(rawServiceRegistry::resolveDialect)
        }
        val composition = backendCompositionProvider.getIfAvailable { QueryBackendComposition.EMPTY }
        val authorityResolver = resolveDirectAuthorityResolver(authorityResolverProvider)
        val recordTrustedResolver = resolveTrustedContextResolver(
            trustedContextResolvers,
            callResolverProvider,
            authorityResolverProvider,
        )
        val analyticsTrustedResolver = when (analyticsTrustedContextResolvers.size) {
            0 -> AnalyticsQueryTrustedContextResolver { Mono.empty() }
            1 -> analyticsTrustedContextResolvers.single()
            else -> CompositeAnalyticsQueryTrustedContextResolver(analyticsTrustedContextResolvers)
        }
        val trustedResolver = FrozenQueryTrustedContextResolvers(recordTrustedResolver, analyticsTrustedResolver)
        val profiles = executionProfilesProvider.getIfAvailable { QueryExecutionProfiles.fixed(configuration) }
        val shadowConfiguration = shadowConfigurationProvider.getIfAvailable { QueryShadowConfiguration() }
        val shadowObserver = shadowObserverProvider.getIfAvailable { QueryShadowObserver.NONE }
        val runtimeHealthObserver = runtimeHealthObserverProvider.getIfAvailable { QueryRuntimeHealthObserver.NONE }
        val cursorConfiguration = cursorLeaseConfigurationProvider.getIfAvailable()
        return if (cursorConfiguration == null) {
            QueryGatewayRuntime.create(
                aggregateTypes.keys,
                composition,
                rawServiceRegistry,
                dialectResolver,
                authorityResolver,
                trustedResolver,
                standardMaterializers + customResultMaterializers,
                configuration,
                profiles,
                shadowConfiguration,
                shadowObserver,
                runtimeHealthObserver,
            )
        } else {
            QueryGatewayRuntime.create(
                aggregateTypes.keys,
                composition,
                cursorConfiguration,
                rawServiceRegistry,
                dialectResolver,
                authorityResolver,
                trustedResolver,
                standardMaterializers + customResultMaterializers,
                configuration,
                profiles,
                shadowConfiguration,
                shadowObserver,
                runtimeHealthObserver,
            )
        }
    }

    private fun createStandardMaterializers(
        aggregateTypes: Map<MaterializedNamedAggregate, Class<*>>,
    ): List<QueryResultMaterializer<*>> = aggregateTypes.flatMap { (namedAggregate, aggregateType) ->
        val snapshotTarget = QueryTarget(namedAggregate, QueryDocumentKind.SNAPSHOT)
        val eventStreamTarget = QueryTarget(namedAggregate, QueryDocumentKind.EVENT_STREAM)
        listOf<QueryResultMaterializer<*>>(
            QueryResultMaterializers.snapshot(
                snapshotTarget,
                aggregateType.aggregateMetadata<Any, Any>().state.aggregateType,
            ),
            QueryResultMaterializers.eventStream(eventStreamTarget),
        )
    }

    @Bean
    @ConditionalOnMissingBean(QueryGateway::class)
    @ConditionalOnQueryGatewayWiring
    fun queryGateway(runtime: QueryGatewayRuntime): QueryGateway = runtime.gateway

    @Bean
    @ConditionalOnMissingBean(AnalyticsQueryGateway::class)
    @ConditionalOnQueryGatewayWiring
    fun analyticsQueryGateway(runtime: QueryGatewayRuntime): AnalyticsQueryGateway = runtime.analyticsGateway

    @Bean
    @Primary
    @ConditionalOnQueryGatewayWiring
    fun analyticsQueryServiceFactory(
        runtime: QueryGatewayRuntime,
    ): AnalyticsQueryServiceFactory = runtime.analyticsQueryServiceFactory()

    @Bean
    @ConditionalOnQueryGatewayWiring
    internal fun queryGatewayRuntimeOwnership(
        runtime: QueryGatewayRuntime,
        gateways: List<QueryGateway>,
        analyticsGateways: List<AnalyticsQueryGateway>,
    ): SmartInitializingSingleton = SmartInitializingSingleton {
        require(gateways.size == 1 && gateways.single() === runtime.gateway) {
            "A custom QueryGateway cannot partially override framework Gateway wiring. " +
                "Provide one complete QueryGatewayRuntime override instead."
        }
        require(analyticsGateways.size == 1 && analyticsGateways.single() === runtime.analyticsGateway) {
            "A custom AnalyticsQueryGateway cannot partially override framework Gateway wiring. " +
                "Provide one complete QueryGatewayRuntime override instead."
        }
    }

    @Bean
    @Primary
    @ConditionalOnQueryGatewayWiring
    fun gatewaySnapshotQueryServiceFactory(runtime: QueryGatewayRuntime): SnapshotQueryServiceFactory =
        runtime.snapshotQueryServiceFactory()

    @Bean
    @Primary
    @ConditionalOnQueryGatewayWiring
    fun gatewayEventStreamQueryServiceFactory(runtime: QueryGatewayRuntime): EventStreamQueryServiceFactory =
        runtime.eventStreamQueryServiceFactory()
}

private class FrozenQueryTrustedContextResolvers(
    private val record: QueryTrustedContextResolver,
    private val analytics: AnalyticsQueryTrustedContextResolver,
) : QueryTrustedContextResolver,
    AnalyticsQueryTrustedContextResolver {
    override fun resolve(request: me.ahoo.wow.query.gateway.QueryTrustedContextRequest) = record.resolve(request)

    override fun resolve(request: me.ahoo.wow.query.analytics.AnalyticsQueryTrustedContextRequest) =
        analytics.resolve(request)
}

private val RECORD_QUERY_OPERATIONS = listOf(
    QueryOperation.SINGLE,
    QueryOperation.STREAM,
    QueryOperation.PAGE,
    QueryOperation.COUNT,
    QueryOperation.ANALYZE,
)

private fun failClosedQueryAuthorityResolver(): QueryAuthorityResolver =
    QueryAuthorityResolver { Mono.empty() }

private fun resolveTrustedContextResolver(
    trustedResolvers: List<QueryTrustedContextResolver>,
    callResolverProvider: ObjectProvider<QueryCallResolver>,
    authorityResolverProvider: ObjectProvider<QueryAuthorityResolver>,
): QueryTrustedContextResolver {
    val callResolvers = callResolverProvider.orderedStream()
        .filter { resolver -> resolver !is QueryTrustedContextResolver }
        .toList()
    val authorityResolvers = authorityResolverProvider.orderedStream()
        .filter { resolver -> resolver !is QueryTrustedContextResolver }
        .toList()
    require(callResolvers.size <= 1) {
        "Separate QueryCallResolver compatibility beans must be unique. " +
            "Use QueryTrustedContextResolver for ordered composition."
    }
    if (callResolvers.isNotEmpty()) {
        require(authorityResolvers.size == 1) {
            "A separate QueryCallResolver compatibility bean requires exactly one QueryAuthorityResolver partner."
        }
    }
    val directPair = callResolvers.singleOrNull()?.let { callResolver ->
        val authorityResolver = authorityResolvers.single()
        QueryTrustedContextResolver { request ->
            callResolver.resolve(request.callRequest)
                .flatMap { call ->
                    authorityResolver.resolve(
                        me.ahoo.wow.query.gateway.QueryAuthorityRequest(
                            call,
                            request.executionMode,
                            request.validationMode,
                        ),
                    ).map { authority -> me.ahoo.wow.query.gateway.QueryTrustedContext(call, authority) }
                }
        }
    }
    val resolvers = directPair?.let { trustedResolvers + it } ?: trustedResolvers
    if (resolvers.isEmpty()) {
        return QueryTrustedContextResolver { Mono.empty() }
    }
    return CompositeQueryTrustedContextResolver(resolvers)
}

private fun resolveDirectAuthorityResolver(
    directProvider: ObjectProvider<QueryAuthorityResolver>,
): QueryAuthorityResolver {
    val resolvers = directProvider.orderedStream()
        .filter { resolver -> resolver !is QueryTrustedContextResolver }
        .toList()
    return when (resolvers.size) {
        0 -> failClosedQueryAuthorityResolver()
        1 -> resolvers.single()
        else -> QueryAuthorityResolver { request ->
            Flux.fromIterable(resolvers)
                .concatMap { resolver -> Mono.defer { resolver.resolve(request) } }
                .next()
        }
    }
}
