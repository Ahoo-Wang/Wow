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
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.client.PeerClients
import me.ahoo.wow.client.RoutedQueryApi
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.kernel.ContextQueries
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.query.admission.AdaptiveLimitOptions
import me.ahoo.wow.query.admission.AdaptiveQueryLimits
import me.ahoo.wow.query.backend.QueryBindings
import me.ahoo.wow.query.cache.QueryCountCache
import me.ahoo.wow.query.dataset.DatasetQueryGateways
import me.ahoo.wow.query.dataset.DatasetVisibility
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasets
import me.ahoo.wow.query.engine.CommittedStates
import me.ahoo.wow.query.engine.ManifestTypes
import me.ahoo.wow.query.engine.QueryEngine
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.mask.PayloadMasks
import me.ahoo.wow.query.observe.QueryObserver
import me.ahoo.wow.query.policy.QueryEntryPolicy
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.read.InProcessQueryApi
import me.ahoo.wow.query.read.ReadModels
import me.ahoo.wow.query.schema.MaskStrategyResolver
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.state.CommittedState
import me.ahoo.wow.query.store.QueryStores
import me.ahoo.wow.query.store.StoreQueries
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.state.ReplayGate
import me.ahoo.wow.runtime.state.StateReadView
import me.ahoo.wow.runtime.state.StateReads
import me.ahoo.wow.spi.config.configNotNull
import me.ahoo.wow.spi.store.facet
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
 * The query plane (–), active when wow-query is on the classpath (wow-http brings it)
 * and `wow.query.enabled` is not off. Over the node's Manifest it builds:
 *
 * - a [SnapshotQueryBackend] chosen by the classpath as the stores are: the application's own
 *   `QueryBindings<AggregateType>` bean when it has one, else, for each aggregate type, the query provider of the store its
 *   snapshot queries read: its primary snapshot store, or the one of its snapshot stores
 *   `wow.aggregates.[<context>.<aggregate>].query.snapshot.store` names (an Elasticsearch store, say); the snapshots in memory (or of the application's own
 *   [me.ahoo.wow.runtime.node.BackendFactory]) are not queried without one, and their routes are not served;
 * - the [EventQueryBackend] of each aggregate type's log store, from the query provider of the store's type
 *   (PostgreSQL or MongoDB in place, a log in memory scanned in memory unless `wow.query.memory.enabled` is false,
 *   any other log not queried), and the [QueryDatasets] of the dataset files ([HostDatasets]);
 * - the [QueryEngine]: this maps `wow.query.*` (ABAC, sensitivity, the entry gates, the count caches)
 *   and the application's beans (its `QueryBindings<QueryDataset>`, [MaskStrategyResolver], [QuerySchemaSource],
 *   [QueryPolicy], [QueryFilter], [QueryObserver] and [DatasetVisibility] beans) onto [QueryEngine.Builder], whose are
 *   every rule of the read side: the Catalog, the gateways of the three models and their policies, the policy gateways
 *   of the types whose snapshots are not queried, the events' visibility and the state reads'
 *   [me.ahoo.wow.query.engine.ReadFacade]. The host decides no query policy itself (an architecture rule holds it);
 * - the node's typed [QueryApi], in process over the same engine, under `wow.query.budgets.in-process.*`;
 * - [HttpQueries], which the public routes serve (the datasets under `/wow/dataset`, seen as [DatasetVisibility]
 *   says: the application's bean, else by scope and `wow.query.abac`), and [QueryIndexesLifecycle], which manages
 *   the `x-wow-index` indexes at startup as each store's `ddl` says.
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
        fun wowSnapshotQueryBackend(
            manifest: Manifest,
            node: ServiceNode,
            backends: HostBackends,
            applicationBindings: ObjectProvider<QueryBindings<AggregateType>>,
            wow: ObjectProvider<WowProperties>,
        ): SnapshotQueryBackend {
            applicationBindings.ifAvailable?.let { return SnapshotQueryBackend(it) }
            // One backend per store the snapshots are on, each type's routed to its store's, from the
            // query provider of the store's type, with the store's own query settings.
            val byStore = backends.layout?.snapshotStores.orEmpty().associateWith { name ->
                val store = backends.stores.getValue(name)
                val options = wow.ifAvailable.storeQueryOptions(name)
                store.facet<StoreQueries>()?.snapshots(options)?.let(SnapshotQueryBackend::of)
            }
            val types = ManifestTypes.snapshots(manifest, node.contexts.keys)
            val configured = backends.layout?.let { layout ->
                HostSnapshotQueryStores.configured(wow.ifAvailable) { layout.snapshotPlacementOf(it).targets }
            }.orEmpty()
            // A type's snapshot queries read the store its configuration names, else its primary; its commands and its
            // state reads read the primary whatever the queries read.
            val storeOf = { type: AggregateType -> configured[type] ?: backends.snapshotStoreOf(type)?.name }
            val backend = SnapshotQueryBackend.routing(types, storeOf) { type -> storeOf(type)?.let(byStore::get) }
            val unserved = types.filterNot(backend::serves)
            if (unserved.isNotEmpty()) {
                logger.info {
                    "No snapshot query backend serves the snapshots of $unserved (${backends.type ?: "the application's"} " +
                        "stores): they are not queried; give the node a QueryBindings<AggregateType> bean to query them (the " +
                        "memory backend serves them unless wow.query.memory.enabled is false)."
                }
            }
            return backend
        }

        /**
         * The `events` query backend of each aggregate type, by its events store: the store
         * `wow.aggregates.[<context>.<aggregate>].query.events.store` names (an Elasticsearch store its event search
         * Sink writes, say), else the store its log is on. The application's own [EventQueryBackend] bean when it
         * has one, else the one of the query provider of the store's type: PostgreSQL's or MongoDB's over
         * the log in place (`wow.mongo.query-read-preference`), Elasticsearch's over the Sink's events index, and a
         * log in memory scanned in memory unless `wow.query.memory.enabled` is false, as its snapshots are queried
         * then.
         * Any other log, in memory without it, on a store whose type has no query provider, or the
         * application's own, is not queried: its `events` model is not served, and an info log says so. A store named
         * for a type that cannot serve its `events` queries fails the start.
         */
        @Bean
        @ConditionalOnMissingBean
        @InternalWowApi
        fun wowEventQueryBackend(
            manifest: Manifest,
            node: ServiceNode,
            backends: HostBackends,
            wow: ObjectProvider<WowProperties>,
        ): EventQueryBackend {
            val layout = backends.layout
            val configured = HostEventStores.configured(wow.ifAvailable, backends.stores.keys)
            val byStore = (layout?.logStores.orEmpty() + configured.values).associateWith { name ->
                val store = backends.stores.getValue(name)
                val options = wow.ifAvailable.storeQueryOptions(name)
                store.facet<StoreQueries>()?.events(options)
            }
            configured.forEach { (type, name) ->
                configNotNull(
                    byStore[name],
                    "wow.aggregates.[${type.context}.${type.name}].query.events.store",
                    "Name a store whose events a query backend reads.",
                    name,
                ) {
                    "wow.aggregates.[${type.context}.${type.name}].query.events.store is $name, a store of type " +
                        "${backends.stores.getValue(name).type} whose events are not queried."
                }
            }
            val storeOf = { type: AggregateType -> configured[type] ?: layout?.logStoreOf(type) }
            val backend = EventQueryBackend(
                store = { type -> storeOf(type)?.takeIf { byStore[it] != null } },
            ) { type -> storeOf(type)?.let(byStore::get)?.binding(type) }
            val unserved = ManifestTypes.events(manifest, node.contexts.keys)
                .filter { backend.binding(it) == null }
            if (unserved.isNotEmpty()) {
                logger.info {
                    "No events query backend serves the logs of $unserved (${backends.type ?: "the application's"} " +
                        "stores): their events are not queried; give the node an EventQueryBackend bean to query them " +
                        "(the memory backend serves them unless wow.query.memory.enabled is false)."
                }
            }
            return backend
        }

        /**
         * The datasets of the dataset files, `wow.query.dataset.config-directory` first, then
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
         * The node's read side: the [QueryEngine] of the Manifest over the snapshot, events and dataset
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
            beans: QueryBeans,
            counts: ObjectProvider<QueryCountCache>,
            reads: ObjectProvider<StateReads>,
            masks: PayloadMasks,
            stores: QueryStores,
        ): QueryEngine = QueryEngine.Builder(manifest)
            .contexts(node.contexts.keys)
            .snapshots(backend.bindings, backend::serves)
            .events(events.bindings)
            .datasets(datasets, beans.datasetBackends.ifAvailable)
            .datasetVisibility(beans.datasetVisibility.ifAvailable)
            .schemaSources(beans.sources.orderedStream().toList())
            .maskStrategies(beans.strategies.getIfAvailable { MaskStrategyResolver.NONE })
            .sensitivity(properties.sensitivity.policy())
            .masks(masks)
            // The tenants commands and state reads act in.
            .tenants(node.gateway.tenants)
            .crossTenant(properties.crossTenant.enabled)
            .abac(properties.abac.options())
            .policies(beans.policies.orderedStream().toList())
            .filters(beans.filters.orderedStream().toList())
            .observers(beans.observers.orderedStream().toList())
            .entryPolicy(entryPolicy)
            .bulkheads { type, model ->
                when (model) {
                    QueryModel.SNAPSHOT -> backend.storeOf(type)?.let(stores.bulkheads::get)
                    QueryModel.EVENTS -> events.storeOf(type)?.let(stores.bulkheads::get)
                    QueryModel.DATASET -> stores.datasets
                    else -> null
                }
            }
            .unpinnedEvents(stores::unpinned)
            .counts(
                counts.ifAvailable,
                // The events' own cache (a cache is keyed by type, not by model), valid while the type's snapshot
                // write checkpoint holds, which moves as the snapshots follow the log: off unless asked, as the
                // log runs ahead of that checkpoint.
                counts.ifAvailable?.takeIf { properties.countCache.events.enabled }?.let {
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
                // Events read as the model reads them now: current names and revisions, as typed.
                val model = context?.models?.firstOrNull { it.type == type }
                if (context == null || model == null) {
                    { it }
                } else {
                    { record -> context.codec.current(model, record) }
                }
            }
            .build()

        /**
         * How the queries of each store run: the bulkheads of the stores (`wow.stores.<name>.query.
         * max-concurrent`, `wow.query.max-concurrent`, else the provider's from its pool), and whether each type's
         * events queries that name no aggregate run.
         */
        @Bean
        @InternalWowApi
        fun wowQueryStores(
            node: ServiceNode,
            properties: QueryProperties,
            backends: HostBackends,
            events: EventQueryBackend,
            wow: ObjectProvider<WowProperties>,
        ): QueryStores {
            val settings = wow.ifAvailable
            // The bounds are wow-query's: the host maps its properties onto their settings.
            return QueryStores.of(
                properties.storeSettings(settings?.stores.orEmpty()),
                backends.stores,
                { name -> HostQueryBulkheads.pressureOf(node, name, backends.stores[name]) },
            ) { type -> HostQueryBulkheads.unpinned(type, events.storeOf(type), properties.events.unpinned, settings) }
        }

        /**
         * Evaluates the adaptive query bounds of the stores every period of their options, from the
         * bean's start to its end.
         */
        @Bean(initMethod = "start", destroyMethod = "close")
        @InternalWowApi
        fun wowAdaptiveQueryLimits(stores: QueryStores): AdaptiveQueryLimits =
            AdaptiveQueryLimits(stores.limits, AdaptiveLimitOptions.DEFAULT_INTERVAL)

        /**
         * How the state reads replay the log of an aggregate without a snapshot: under the query
         * bulkhead of its log store, as that store's queries run.
         */
        @Bean
        @ConditionalOnMissingBean
        @InternalWowApi
        fun wowReplayGate(stores: QueryStores, backends: HostBackends): ReplayGate =
            if (stores.bulkheads.isEmpty()) {
                ReplayGate.OPEN
            } else {
                object : ReplayGate {
                    override suspend fun <T> replay(ref: AggregateRef, read: suspend () -> T): T =
                        stores.replay(backends.layout?.logStoreOf(ref.type), read)

                    override fun toString(): String = "ReplayGate($stores)"
                }
            }

        /** The application's beans the engine is built of, which it may give or not. */
        @Bean
        @InternalWowApi
        @Suppress("LongParameterList") // One parameter per kind of bean.
        fun wowQueryBeans(
            datasetBackends: ObjectProvider<QueryBindings<QueryDataset>>,
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

        /** The engine's schema Catalog. */
        @Bean
        @InternalWowApi
        fun wowQuerySchemaCatalog(engine: QueryEngine): QuerySchemaCatalog = engine.catalog

        /**
         * The engine's dataset gateways; the dataset bindings forget a dataset that leaves the [QueryDatasets].
         */
        @Bean
        @InternalWowApi
        fun wowDatasetQueryGateways(
            engine: QueryEngine,
            datasets: QueryDatasets,
            datasetBackends: ObjectProvider<QueryBindings<QueryDataset>>,
        ): DatasetQueryGateways = engine.datasets.also {
            datasetBackends.ifAvailable?.let { HostDatasets.forgetOnRemoval(datasets, it) }
        }

        /**
         * The count cache of the snapshot gateways, unless `wow.query.count-cache.enabled` is off: each
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

        /** The query models the public routes serve: the engine's, `wow.query.budgets.http.default-list-size`. */
        @Bean
        @ConditionalOnMissingBean
        @InternalWowApi
        fun wowHttpQueries(engine: QueryEngine, node: ServiceNode, properties: QueryProperties): HttpQueries =
            HttpQueries.of(
                engine,
                properties.budgets.http.defaultListSize,
                node.snapshotStatus,
                properties.stream.streams(),
            )

        /**
         * The node's typed [QueryApi]: the [engine]'s gateways and read facade, each aggregate
         * type found by its model's state class, its states read as the public port's state reads, its lists defaulted
         * by `wow.query.budgets.in-process.default-list-size` and its streams read in `wow.query.stream.chunk-size` chunks.
         */
        @Bean
        @ConditionalOnMissingBean
        fun wowQueryApi(
            engine: QueryEngine,
            node: ServiceNode,
            properties: QueryProperties,
            reads: ObjectProvider<StateReads>,
            peers: PeerClients,
        ): QueryApi {
            val models = node.contexts.values.flatMap { it.models }
            val local = InProcessQueryApi(
                engine,
                ReadModels(
                    models.map { model ->
                        ReadModels.Model(model.type, model.stateType) { name -> model.findEventType(name)?.type }
                    },
                ),
                latest = object : CommittedStates {
                    override suspend fun committed(ref: AggregateRef): CommittedState? =
                        reads.ifAvailable?.latest(ref)?.committed()

                    override suspend fun committed(ref: AggregateRef, minVersion: Int): CommittedState? =
                        (reads.ifAvailable ?: StateReads.NONE).latest(ref, minVersion)?.committed()

                    private fun StateReadView.committed() = CommittedState(ref, version, scope, deleted, tags, state)
                },
                defaultListSize = properties.budgets.inProcess.defaultListSize,
                chunkSize = properties.stream.chunkSize,
            )
            // The peer contexts of wow.peers are read over HTTP.
            return if (peers.contexts.isEmpty()) {
                local
            } else {
                RoutedQueryApi(local, peers, models.mapTo(HashSet()) { it.stateType })
            }
        }

        /**
         * The queries of the subscription handlers ([ContextQueries.of]): the node's [QueryApi] pinned to the tenant of
         * each commit, as trusted code; an application's own [QueryApi] bean as a caller of that tenant.
         */
        @Bean
        @ConditionalOnMissingBean
        @InternalWowApi
        fun wowContextQueries(api: QueryApi): ContextQueries = ContextQueries.of(api)

        /** Ends the open query streams before the public server's graceful shutdown waits for them. */
        @Bean
        @InternalWowApi
        fun wowQueryStreamsLifecycle(queries: HttpQueries): QueryStreamsLifecycle = QueryStreamsLifecycle(
            queries.streams
        )

        @Bean
        @InternalWowApi
        fun wowQueryIndexesLifecycle(
            manifest: Manifest,
            node: ServiceNode,
            wow: ObjectProvider<WowProperties>,
            backend: SnapshotQueryBackend,
            catalog: QuerySchemaCatalog,
        ): QueryIndexesLifecycle = QueryIndexesLifecycle(
            manifest,
            ManifestTypes.snapshots(manifest, node.contexts.keys),
            backend,
            catalog,
        ) { store ->
            store?.takeIf { wow.ifAvailable?.stores?.get(it)?.ddl != null }?.let { "wow.stores.$it.ddl" } ?: "wow.ddl"
        }
    }

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}

/** The application's beans a [QueryEngine] is built of: each it may give, or not. */
@InternalWowApi
class QueryBeans internal constructor(
    internal val datasetBackends: ObjectProvider<QueryBindings<QueryDataset>>,
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
