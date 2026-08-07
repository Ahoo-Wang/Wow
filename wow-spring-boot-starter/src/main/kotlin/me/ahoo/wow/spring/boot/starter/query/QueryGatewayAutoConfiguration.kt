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

@file:OptIn(me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class)

package me.ahoo.wow.spring.boot.starter.query

import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.gateway.GatewayEventStreamQueryServiceFactory
import me.ahoo.wow.query.gateway.GatewaySnapshotQueryServiceFactory
import me.ahoo.wow.query.gateway.QueryAuthorityResolver
import me.ahoo.wow.query.gateway.QueryCallResolver
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryGateway
import me.ahoo.wow.query.gateway.QueryGatewayConfiguration
import me.ahoo.wow.query.gateway.QueryGatewayRuntime
import me.ahoo.wow.query.gateway.QueryLegacyDialectResolver
import me.ahoo.wow.query.gateway.QueryResultMaterializer
import me.ahoo.wow.query.gateway.QueryResultMaterializers
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.WowAutoConfiguration
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.EventStreamQueryServiceFactoryBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.SnapshotQueryServiceFactoryBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.StorageRoutingAutoConfiguration
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.StorageRoutingProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
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
    @ConditionalOnMissingBean(QueryCallResolver::class)
    fun failClosedQueryCallResolver(): QueryCallResolver = QueryCallResolver { Mono.empty() }

    @Bean
    @ConditionalOnMissingBean(QueryAuthorityResolver::class)
    fun failClosedQueryAuthorityResolver(): QueryAuthorityResolver = QueryAuthorityResolver { Mono.empty() }

    @Bean
    @ConditionalOnMissingBean(QueryGatewayConfiguration::class)
    fun queryGatewayConfiguration(): QueryGatewayConfiguration = QueryGatewayConfiguration()

    @Bean
    @ConditionalOnMissingBean(QueryGateway::class)
    internal fun queryGatewayRuntime(
        rawServiceRegistry: StorageBindingQueryRawServiceRegistry,
        dialectResolverProvider: ObjectProvider<QueryLegacyDialectResolver>,
        authorityResolver: QueryAuthorityResolver,
        configuration: QueryGatewayConfiguration,
        customResultMaterializers: List<QueryResultMaterializer<*>>,
    ): QueryGatewayRuntime {
        val aggregateTypes = MetadataSearcher.namedAggregateType
        val standardMaterializers = aggregateTypes.flatMap { (namedAggregate, aggregateType) ->
            val snapshotTarget = QueryTarget(namedAggregate, QueryDocumentKind.SNAPSHOT)
            val eventStreamTarget = QueryTarget(namedAggregate, QueryDocumentKind.EVENT_STREAM)
            listOf(
                QueryResultMaterializers.snapshot(
                    snapshotTarget,
                    aggregateType.aggregateMetadata<Any, Any>().state.aggregateType,
                ),
                QueryResultMaterializers.eventStream(eventStreamTarget),
            )
        }
        val dialectResolver = dialectResolverProvider.getIfAvailable {
            QueryLegacyDialectResolver(rawServiceRegistry::resolveDialect)
        }
        return QueryGatewayRuntime.create(
            namedAggregates = aggregateTypes.keys,
            rawServiceSource = rawServiceRegistry,
            dialectResolver = dialectResolver,
            authorityResolver = authorityResolver,
            resultMaterializers = standardMaterializers + customResultMaterializers,
            configuration = configuration,
        )
    }

    @Bean
    @ConditionalOnMissingBean(QueryGateway::class)
    fun queryGateway(runtime: QueryGatewayRuntime): QueryGateway = runtime.gateway

    @Bean
    @Primary
    fun gatewaySnapshotQueryServiceFactory(
        gateway: QueryGateway,
        callResolver: QueryCallResolver,
    ): SnapshotQueryServiceFactory = GatewaySnapshotQueryServiceFactory(gateway, callResolver)

    @Bean
    @Primary
    fun gatewayEventStreamQueryServiceFactory(
        gateway: QueryGateway,
        callResolver: QueryCallResolver,
    ): EventStreamQueryServiceFactory = GatewayEventStreamQueryServiceFactory(gateway, callResolver)
}
