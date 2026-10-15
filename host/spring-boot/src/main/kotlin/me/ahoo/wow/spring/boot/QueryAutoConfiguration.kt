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

package me.ahoo.wow.spring.boot

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.http.query.DatasetVisibility
import me.ahoo.wow.http.query.EventQueryGateway
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.query.AbacQueryPolicy
import me.ahoo.wow.query.DefaultQueryGateway
import me.ahoo.wow.query.QueryBackendFactory
import me.ahoo.wow.query.QueryBackendStorages
import me.ahoo.wow.query.QueryCountCache
import me.ahoo.wow.query.QueryEntryPolicy
import me.ahoo.wow.query.QueryLogObserver
import me.ahoo.wow.query.QueryObserver
import me.ahoo.wow.query.QueryOwnership
import me.ahoo.wow.query.QueryPolicy
import me.ahoo.wow.query.QueryTenantPolicy
import me.ahoo.wow.query.ScopeQueryPolicy
import me.ahoo.wow.query.dataset.DatasetQueryBackendFactory
import me.ahoo.wow.query.dataset.DatasetQueryGateways
import me.ahoo.wow.query.dataset.QueryDatasets
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.schema.MaskStrategyResolver
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.runtime.node.ServiceNode
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path

/**
 * The query plane (decisions D126–D131, D150, D151), active when wow-query is on the classpath (wow-http brings it)
 * and `wow.query.enabled` is not off. Over the node's Manifest it builds:
 *
 * - a [SnapshotQueryBackend] chosen by the classpath as the stores are (decision D110): the application's own
 *   [QueryBackendFactory] bean when it has one, else, for each aggregate type, PostgreSQL or MongoDB when its
 *   snapshots are on a store of that type (decision D177); the snapshots in memory (or of the application's own
 *   [me.ahoo.wow.runtime.node.BackendFactory]) are not queried without one, and their routes are not served;
 * - a [QuerySchemaCatalog] compiling each aggregate's snapshot model from the Manifest
 *   ([ManifestStateSchemas], with the application's [MaskStrategyResolver] and [QuerySchemaSource] beans) under
 *   `wow.query.sensitivity`;
 * - a gateway per aggregate type the node hosts: [DefaultQueryGateway] for its snapshots, under the
 *   [ScopeQueryPolicy] (tenant by [HostQueryTenants], D116), the [AbacQueryPolicy] (`wow.query.abac`) and the
 *   application's [QueryPolicy] and [QueryFilter] beans, observed by [QueryLogObserver] and the [QueryObserver] beans,
 *   its counts and paged totals cached in the [QueryCountCache] (`wow.query.count-cache`, D152); and
 *   [EventQueryGateway] for its events, over the context's log;
 * - the [QueryDatasets] of the dataset files (decisions D75, D145; [HostDatasets]), compiled by the same Catalog
 *   against the storage of the application's [DatasetQueryBackendFactory] bean (ClickHouse's, when wow-clickhouse
 *   is wired), and their [DatasetQueryGateways] under the same policies, filters and observers;
 * - [HttpQueries], which the public routes serve (the datasets under `/wow/dataset`, seen as [DatasetVisibility]
 *   says: the application's bean, else by scope and `wow.query.abac`), and [QueryIndexesLifecycle], which manages
 *   the `x-wow-index` indexes at startup as `wow.query.index.ddl` says.
 */
@AutoConfiguration(after = [WowAutoConfiguration::class])
@ConditionalOnClass(name = [QUERY_MODULE])
@ConditionalOnProperty(prefix = QueryProperties.PREFIX, name = ["enabled"], matchIfMissing = true)
@EnableConfigurationProperties(QueryProperties::class)
class QueryAutoConfiguration {
    /** The node's queries, once it has a [ServiceNode]. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(ServiceNode::class)
    class NodeQueries {
        @Bean
        @ConditionalOnMissingBean
        fun wowQueryEntryPolicy(properties: QueryProperties): QueryEntryPolicy = properties.entryPolicy()

        @Bean
        @ConditionalOnMissingBean
        @InternalWowApi
        fun wowQueryTenantPolicy(node: ServiceNode): QueryTenantPolicy = HostQueryTenants(node.gateway.tenants)

        @Bean
        @ConditionalOnMissingBean
        @InternalWowApi
        fun wowSnapshotQueryBackend(
            manifest: Manifest,
            node: ServiceNode,
            properties: QueryProperties,
            backends: HostBackends,
            factories: ObjectProvider<QueryBackendFactory<*>>,
        ): SnapshotQueryBackend {
            factories.ifAvailable?.let { return SnapshotQueryBackend(it) }
            val ddl = properties.index.ddl
            // One backend per store the snapshots are on (decision D177), each type's routed to its store's.
            val byStore = backends.layout?.snapshotStores.orEmpty().associateWith { name ->
                val store = backends.stores.getValue(name)
                when (store.kind) {
                    BackendKind.POSTGRES -> PostgresQueries.open(store, ddl)
                    BackendKind.MONGO -> MongoQueries.open(store, ddl)
                    else -> MemoryQueries.open(store)
                }
            }
            val types = snapshotTypes(manifest, node.contexts.keys)
            val backend = SnapshotQueryBackend.routing(types) { type ->
                backends.snapshotStoreOf(type)?.let { byStore[it.name] }
            }
            val unserved = types.filterNot(backend::serves)
            if (unserved.isNotEmpty()) {
                logger.info {
                    "No snapshot query backend serves the snapshots of $unserved (${backends.kind ?: "the application's"} " +
                        "stores): they are not queried; give the node a QueryBackendFactory bean to query them, or set " +
                        "wow.query.memory.enabled on the memory backend."
                }
            }
            return backend
        }

        /**
         * The datasets of the dataset files (decisions D75, D145), `wow.query.dataset.config-directory` first, then
         * the classpath; an application may give its own [QueryDatasets], or add to this one while the node runs.
         */
        @Bean
        @ConditionalOnMissingBean
        fun wowQueryDatasets(properties: QueryProperties, beanFactory: ConfigurableListableBeanFactory): QueryDatasets =
            HostDatasets.load(
                Path.of(properties.dataset.configDirectory),
                beanFactory.beanClassLoader ?: QueryAutoConfiguration::class.java.classLoader,
            )

        @Bean
        @ConditionalOnMissingBean
        @Suppress("LongParameterList") // One parameter per part of the Catalog, each a bean the application may give.
        @InternalWowApi
        fun wowQuerySchemaCatalog(
            manifest: Manifest,
            node: ServiceNode,
            properties: QueryProperties,
            backend: SnapshotQueryBackend,
            datasets: QueryDatasets,
            datasetBackends: ObjectProvider<DatasetQueryBackendFactory>,
            strategies: ObjectProvider<MaskStrategyResolver>,
            sources: ObjectProvider<QuerySchemaSource>,
        ): QuerySchemaCatalog {
            val manifestSource = ManifestStateSchemas(manifest, strategies.getIfAvailable { MaskStrategyResolver.NONE })
            return QuerySchemaCatalog(
                storages = QueryBackendStorages(backend.factory, datasetBackends.ifAvailable, datasets),
                compiler = QueryModelCompiler.of(
                    listOf(manifestSource) + sources.orderedStream().toList(),
                    properties.sensitivity.policy(),
                    datasets,
                ),
                aggregates = snapshotTypes(manifest, node.contexts.keys).filter(backend::serves),
                datasets = datasets,
            )
        }

        /**
         * A gateway per dataset (decision D145) over the [DatasetQueryBackendFactory] bean (ClickHouse's, say), under
         * the snapshots' policies, filters, observers and entry policy; it follows the [QueryDatasets] as datasets join,
         * change or leave, and a dataset that leaves is evicted from the backend. Without a dataset backend, none.
         */
        @Bean
        @ConditionalOnMissingBean
        @Suppress("LongParameterList") // One parameter per part of a gateway, each a bean the application may give.
        @InternalWowApi
        fun wowDatasetQueryGateways(
            manifest: Manifest,
            properties: QueryProperties,
            datasets: QueryDatasets,
            catalog: QuerySchemaCatalog,
            entryPolicy: QueryEntryPolicy,
            tenants: QueryTenantPolicy,
            datasetBackends: ObjectProvider<DatasetQueryBackendFactory>,
            policies: ObjectProvider<QueryPolicy>,
            filters: ObjectProvider<QueryFilter>,
            observers: ObjectProvider<QueryObserver>,
        ): DatasetQueryGateways {
            val factory = datasetBackends.ifAvailable
            if (factory == null) {
                if (datasets.all.isNotEmpty()) {
                    logger.warn {
                        "No dataset query backend is wired: the datasets ${datasets.all.map { it.name }} are not " +
                            "served; add wow-clickhouse with wow.clickhouse.endpoint, or a DatasetQueryBackendFactory bean."
                    }
                }
                return DatasetQueryGateways.NONE
            }
            val gateways = DatasetQueryGateways.of(
                datasets,
                catalog,
                factory,
                filters = filters.orderedStream().toList(),
                policies = scopePolicies(manifest, tenants, properties) + policies.orderedStream().toList(),
                observer = observerOf(observers),
                entryPolicy = entryPolicy,
            )
            HostDatasets.evictOnRemoval(datasets, factory)
            return gateways
        }

        /**
         * The count cache of the snapshot gateways (decision D152), unless `wow.query.count-cache.enabled` is off: each
         * count valid while the snapshot write checkpoint of its type on this node
         * ([me.ahoo.wow.runtime.node.ContextNode.snapshotCheckpoint]) holds.
         */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = QueryProperties.PREFIX, name = ["count-cache.enabled"], matchIfMissing = true)
        fun wowQueryCountCache(node: ServiceNode, properties: QueryProperties): QueryCountCache = QueryCountCache(
            { type -> node.contexts[type.context]?.snapshotCheckpoint(type) },
            properties.countCache.options(),
        )

        @Bean
        @ConditionalOnMissingBean
        @Suppress("LongParameterList") // One parameter per part of a gateway, each a bean the application may give.
        @InternalWowApi
        fun wowHttpQueries(
            manifest: Manifest,
            node: ServiceNode,
            properties: QueryProperties,
            backend: SnapshotQueryBackend,
            catalog: QuerySchemaCatalog,
            entryPolicy: QueryEntryPolicy,
            tenants: QueryTenantPolicy,
            policies: ObjectProvider<QueryPolicy>,
            filters: ObjectProvider<QueryFilter>,
            observers: ObjectProvider<QueryObserver>,
            datasets: DatasetQueryGateways,
            visibility: ObjectProvider<DatasetVisibility>,
            counts: ObjectProvider<QueryCountCache>,
        ): HttpQueries {
            val contexts = node.contexts.keys
            val observer = observerOf(observers)
            val scope = scopePolicies(manifest, tenants, properties)
            val factory = backend.factory
            val snapshots = if (factory == null) {
                emptyList()
            } else {
                snapshotTypes(manifest, contexts).filter(backend::serves).map { type ->
                    DefaultQueryGateway(
                        aggregateType = type,
                        backend = factory.create(type).backend,
                        schemaProvider = catalog.provider(type, QueryModel.SNAPSHOT),
                        filters = filters.orderedStream().toList(),
                        policies = scope + policies.orderedStream().toList(),
                        observer = observer,
                        entryPolicy = entryPolicy,
                        counts = counts.ifAvailable,
                    )
                }
            }
            val events = queryTypes(manifest, contexts, QueryRoutes.EVENTS).map { type ->
                EventQueryGateway(
                    type,
                    node.contexts.getValue(type.context).log,
                    tenants,
                    entryPolicy,
                    QueryOwnership.of(manifest).ownershipOf(type),
                )
            }
            return HttpQueries(
                snapshots,
                events,
                properties.http.defaultListSize,
                datasets,
                visibility.getIfAvailable { DatasetVisibility.of(properties.abac.options()) },
                node.snapshotStatus,
            )
        }

        @Bean
        @InternalWowApi
        fun wowQueryIndexesLifecycle(
            manifest: Manifest,
            node: ServiceNode,
            properties: QueryProperties,
            backend: SnapshotQueryBackend,
            catalog: QuerySchemaCatalog,
        ): QueryIndexesLifecycle = QueryIndexesLifecycle(
            manifest,
            snapshotTypes(manifest, node.contexts.keys),
            backend,
            catalog,
            properties.index.ddl,
        )
    }

    private companion object {
        val logger = KotlinLogging.logger {}

        /**
         * The scope and ABAC policies every gateway runs first (decision D129), the owner applying to the aggregates
         * [manifest] says belong to their owners (decision D189).
         */
        fun scopePolicies(
            manifest: Manifest,
            tenants: QueryTenantPolicy,
            properties: QueryProperties,
        ): List<QueryPolicy> = listOf(
            ScopeQueryPolicy(tenants, ownerships = QueryOwnership.of(manifest)),
            AbacQueryPolicy(properties.abac.options()),
        )

        /** The query log and the application's observers. */
        fun observerOf(observers: ObjectProvider<QueryObserver>): QueryObserver =
            QueryObserver.of(listOf(QueryLogObserver()) + observers.orderedStream().toList())
    }
}

/** A class of wow-query, present only when the application has the module. */
internal const val QUERY_MODULE = "me.ahoo.wow.query.QueryModule"
