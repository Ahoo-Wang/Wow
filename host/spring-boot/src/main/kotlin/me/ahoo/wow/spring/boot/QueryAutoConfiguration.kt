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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.query.AbacQueryPolicy
import me.ahoo.wow.query.DefaultQueryGateway
import me.ahoo.wow.query.QueryBackendFactory
import me.ahoo.wow.query.QueryCountCache
import me.ahoo.wow.query.QueryEntryPolicy
import me.ahoo.wow.query.QueryLogObserver
import me.ahoo.wow.query.QueryObserver
import me.ahoo.wow.query.QueryPolicy
import me.ahoo.wow.query.QueryTenantPolicy
import me.ahoo.wow.query.ScopeQueryPolicy
import me.ahoo.wow.query.dataset.DatasetQueryBackendFactory
import me.ahoo.wow.query.dataset.DatasetQueryGateways
import me.ahoo.wow.query.dataset.DatasetVisibility
import me.ahoo.wow.query.dataset.QueryDatasets
import me.ahoo.wow.query.engine.ManifestTypes
import me.ahoo.wow.query.engine.QueryEngine
import me.ahoo.wow.query.events.EventQueryGateways
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.mask.PayloadMasks
import me.ahoo.wow.query.schema.EventsQuerySchemas
import me.ahoo.wow.query.schema.MaskStrategyResolver
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.state.CommittedState
import me.ahoo.wow.query.state.SnapshotPolicyGateway
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.state.StateReads
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
 *   ([me.ahoo.wow.query.engine.ManifestStateSchemas], with the application's [MaskStrategyResolver] and [QuerySchemaSource] beans) under
 *   `wow.query.sensitivity`;
 * - a gateway per aggregate type the node hosts: [DefaultQueryGateway] for its snapshots, under the
 *   [ScopeQueryPolicy] (tenant by [HostQueryTenants], D116), the [AbacQueryPolicy] (`wow.query.abac`) and the
 *   application's [QueryPolicy] and [QueryFilter] beans, observed by [QueryLogObserver] and the [QueryObserver] beans,
 *   its counts and paged totals cached in the [QueryCountCache] (`wow.query.count-cache`, D152); and, on the same
 *   engine, policies, filters and observers, the gateway of its events ([EventQueryGateways], D235) over the
 *   [EventQueryBackend] of its log store (PostgreSQL or MongoDB in place, a log in memory scanned in memory under
 *   `wow.query.memory.enabled`, any other log not queried), the events' schema from the Manifest
 *   ([EventsQuerySchemas]), A3-11 visibility as a policy, and a count cache of its own; with ABAC or an application's
 *   policy, a [SnapshotPolicyGateway] for each type whose snapshots are not queried, so its events and state reads
 *   stay under those policies (review R3.3-3);
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
            providers: StoreProviders,
            factories: ObjectProvider<QueryBackendFactory<*>>,
        ): SnapshotQueryBackend {
            factories.ifAvailable?.let { return SnapshotQueryBackend(it) }
            val options = properties.storeQueryOptions()
            // One backend per store the snapshots are on (decision D177), each type's routed to its store's, from the
            // query provider of the store's type (review R3.3-1).
            val byStore = backends.layout?.snapshotStores.orEmpty().associateWith { name ->
                val store = backends.stores.getValue(name)
                providers.queriesOf(store.type)?.snapshots(store, options)?.let(SnapshotQueryBackend::of)
            }
            val types = ManifestTypes.snapshots(manifest, node.contexts.keys)
            val backend = SnapshotQueryBackend.routing(types) { type ->
                backends.snapshotStoreOf(type)?.let { byStore[it.name] }
            }
            val unserved = types.filterNot(backend::serves)
            if (unserved.isNotEmpty()) {
                logger.info {
                    "No snapshot query backend serves the snapshots of $unserved (${backends.type ?: "the application's"} " +
                        "stores): they are not queried; give the node a QueryBackendFactory bean to query them, or set " +
                        "wow.query.memory.enabled on the memory backend."
                }
            }
            return backend
        }

        /**
         * The `events` query backend of each aggregate type (decision D235), by the store its log is on (D177): the
         * application's own [EventQueryBackend] bean when it has one, else the one of the query provider of the store's
         * type (review R3.3-1): PostgreSQL's or MongoDB's over the log in place (`wow.query.events.mongo-read-preference`),
         * and a log in memory scanned in memory when `wow.query.memory.enabled` is on, as its snapshots are queried then
         * (review R3.4-1). Any other log, in memory without it, on a store whose type has no query provider, or the
         * application's own, is not queried: its `events` model is not served, and an info log says so.
         */
        @Bean
        @ConditionalOnMissingBean
        @InternalWowApi
        fun wowEventQueryBackend(
            manifest: Manifest,
            node: ServiceNode,
            properties: QueryProperties,
            backends: HostBackends,
            providers: StoreProviders,
        ): EventQueryBackend {
            val layout = backends.layout
            val options = properties.storeQueryOptions()
            val byStore = layout?.logStores.orEmpty().associateWith { name ->
                val store = backends.stores.getValue(name)
                providers.queriesOf(store.type)?.events(store, options)
            }
            val backend = EventQueryBackend { type -> layout?.let { byStore[it.logStoreOf(type)] }?.binding(type) }
            val unserved = ManifestTypes.events(manifest, node.contexts.keys)
                .filter { backend.binding(it) == null }
            if (unserved.isNotEmpty()) {
                logger.info {
                    "No events query backend serves the logs of $unserved (${backends.type ?: "the application's"} " +
                        "stores): their events are not queried; give the node an EventQueryBackend bean to query them, " +
                        "or set wow.query.memory.enabled on the memory backend."
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

        /**
         * The node's read side (decision D237): the [QueryEngine] of the Manifest over the snapshot, events and dataset
         * backends, the application's schema sources, mask strategies, policies, filters and observers, its count
         * caches and the node's committed states, under `wow.query.*` (ABAC, sensitivity, entry policy). The engine
         * holds every security rule of the read side; this only maps the configuration onto it.
         */
        @Bean
        @ConditionalOnMissingBean
        @Suppress("LongParameterList") // One parameter per input of the engine, each a bean the application may give.
        @InternalWowApi
        fun wowQueryEngine(
            manifest: Manifest,
            node: ServiceNode,
            properties: QueryProperties,
            backend: SnapshotQueryBackend,
            events: EventQueryBackend,
            datasets: QueryDatasets,
            entryPolicy: QueryEntryPolicy,
            tenants: QueryTenantPolicy,
            beans: QueryBeans,
            counts: ObjectProvider<QueryCountCache>,
            reads: ObjectProvider<StateReads>,
            masks: PayloadMasks,
        ): QueryEngine = QueryEngine.Builder(manifest)
            .contexts(node.contexts.keys)
            .snapshots(backend.factory, backend::serves)
            .events(events::binding)
            .datasets(datasets, beans.datasetBackends.ifAvailable)
            .datasetVisibility(beans.datasetVisibility.ifAvailable)
            .schemaSources(beans.sources.orderedStream().toList())
            .maskStrategies(beans.strategies.getIfAvailable { MaskStrategyResolver.NONE })
            .sensitivity(properties.sensitivity.policy())
            .masks(masks)
            .tenants(tenants)
            .abac(properties.abac.options())
            .policies(beans.policies.orderedStream().toList())
            .filters(beans.filters.orderedStream().toList())
            .observers(beans.observers.orderedStream().toList())
            .entryPolicy(entryPolicy)
            .counts(
                counts.ifAvailable,
                // The events' own cache (a cache is keyed by type, not by model), valid while the type's snapshot
                // write checkpoint holds, which moves as the snapshots follow the log.
                counts.ifAvailable?.let {
                    QueryCountCache(
                        node::snapshotCheckpointOf,
                        properties.countCache.options()
                    )
                },
            )
            .committedStates { ref ->
                reads.ifAvailable?.committed(ref)?.let {
                    CommittedState(it.ref, it.version, it.scope, it.deleted, it.tags, it.state)
                }
            }
            .reading { type ->
                val context = node.contexts[type.context]
                // Events read as the model reads them now (finding A4-16): current names and revisions, as typed.
                val model = context?.models?.firstOrNull { it.type == type }
                if (context == null || model == null) {
                    { it }
                } else {
                    { record -> context.codec.current(model, record) }
                }
            }
            .build()

        /** The application's beans the engine is built of, which it may give or not. */
        @Bean
        @InternalWowApi
        @Suppress("LongParameterList") // One parameter per kind of bean.
        fun wowQueryBeans(
            datasetBackends: ObjectProvider<DatasetQueryBackendFactory>,
            datasetVisibility: ObjectProvider<DatasetVisibility>,
            strategies: ObjectProvider<MaskStrategyResolver>,
            sources: ObjectProvider<QuerySchemaSource>,
            policies: ObjectProvider<QueryPolicy>,
            filters: ObjectProvider<QueryFilter>,
            observers: ObjectProvider<QueryObserver>,
        ): QueryBeans = QueryBeans(
            datasetBackends,
            datasetVisibility,
            strategies,
            sources,
            policies,
            filters,
            observers
        )

        /** The engine's schema Catalog (decision D150). */
        @Bean
        @InternalWowApi
        fun wowQuerySchemaCatalog(engine: QueryEngine): QuerySchemaCatalog = engine.catalog

        /**
         * The engine's dataset gateways (decision D145); a dataset that leaves the [QueryDatasets] is evicted from the
         * dataset backend.
         */
        @Bean
        @InternalWowApi
        fun wowDatasetQueryGateways(
            engine: QueryEngine,
            datasets: QueryDatasets,
            datasetBackends: ObjectProvider<DatasetQueryBackendFactory>,
        ): DatasetQueryGateways = engine.datasets.also {
            datasetBackends.ifAvailable?.let { HostDatasets.evictOnRemoval(datasets, it) }
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

        /** The query models the public routes serve: the engine's, `wow.query.http.default-list-size`. */
        @Bean
        @ConditionalOnMissingBean
        @InternalWowApi
        fun wowHttpQueries(engine: QueryEngine, node: ServiceNode, properties: QueryProperties): HttpQueries =
            HttpQueries.of(engine, properties.http.defaultListSize, node.snapshotStatus)

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
            ManifestTypes.snapshots(manifest, node.contexts.keys),
            backend,
            catalog,
            properties.index.ddl,
        )
    }

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}

/** The application's beans a [QueryEngine] is built of: each it may give, or not. */
@InternalWowApi
class QueryBeans internal constructor(
    internal val datasetBackends: ObjectProvider<DatasetQueryBackendFactory>,
    internal val datasetVisibility: ObjectProvider<DatasetVisibility>,
    internal val strategies: ObjectProvider<MaskStrategyResolver>,
    internal val sources: ObjectProvider<QuerySchemaSource>,
    internal val policies: ObjectProvider<QueryPolicy>,
    internal val filters: ObjectProvider<QueryFilter>,
    internal val observers: ObjectProvider<QueryObserver>,
)

/** A class of wow-query, present only when the application has the module. */
internal const val QUERY_MODULE = "me.ahoo.wow.query.QueryModule"

/** The snapshot write checkpoint of [type] on this node, or null when its context is not hosted here. */
private suspend fun ServiceNode.snapshotCheckpointOf(type: AggregateType): Long? =
    contexts[type.context]?.snapshotCheckpoint(type)
