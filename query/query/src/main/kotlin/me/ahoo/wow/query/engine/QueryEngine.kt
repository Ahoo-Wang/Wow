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

package me.ahoo.wow.query.engine

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.query.AbacQueryOptions
import me.ahoo.wow.query.AbacQueryPolicy
import me.ahoo.wow.query.DefaultQueryGateway
import me.ahoo.wow.query.EventsMode
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.QueryBackendFactory
import me.ahoo.wow.query.QueryBackendStorages
import me.ahoo.wow.query.QueryBulkhead
import me.ahoo.wow.query.QueryCountCache
import me.ahoo.wow.query.QueryEntryPolicy
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.QueryLogObserver
import me.ahoo.wow.query.QueryObserver
import me.ahoo.wow.query.QueryOwnership
import me.ahoo.wow.query.QueryPolicy
import me.ahoo.wow.query.QueryTargets
import me.ahoo.wow.query.ScopeQueryPolicy
import me.ahoo.wow.query.dataset.DatasetQueryBackendFactory
import me.ahoo.wow.query.dataset.DatasetQueryGateways
import me.ahoo.wow.query.dataset.DatasetVisibility
import me.ahoo.wow.query.dataset.QueryDatasets
import me.ahoo.wow.query.events.EventQueryGateways
import me.ahoo.wow.query.events.EventVisibility
import me.ahoo.wow.query.events.UnpinnedEvents
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.mask.PayloadMasks
import me.ahoo.wow.query.schema.EventsQuerySchemas
import me.ahoo.wow.query.schema.MaskStrategyResolver
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.schema.QuerySensitivityPolicy
import me.ahoo.wow.query.state.SnapshotPolicyGateway
import me.ahoo.wow.query.under
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.tenant.TenantPolicy

/**
 * The read side of a node, composed once (decision D237, review R3.1-1): the gateways of the three query models (the
 * [snapshots] and [events] of each aggregate type a backend serves, the [datasets]), the schema [catalog] they compile
 * against, which aggregates and datasets a caller sees ([visibility], [datasetVisibility]), and the [reads] every entry
 * reads a state through. A host only maps its configuration onto a [Builder]; the security rules are the builder's:
 *
 * - every gateway runs the scope policy (the tenant, the owner of the aggregates their owners hold, decisions D129,
 *   D189) and ABAC first, then the application's policies, and is observed by the query log and the application's
 *   observers;
 * - when ABAC or an application's policy may hide an aggregate from a caller of its scope, a type whose snapshots are
 *   not queried still has its policies evaluated on its states ([SnapshotPolicyGateway], review R3.3-3), so its state
 *   reads and its events fail closed;
 * - an aggregate the snapshot queries hide is hidden from the events model and the state reads too (reviews A3-11,
 *   R2.3-6);
 * - every query of the snapshots and the events runs under its store's [QueryBulkhead] (review R3.4-2), whichever entry
 *   asks, and an events query that names no aggregate is refused where [UnpinnedEvents.REFUSE] says so.
 */
@InternalWowApi
class QueryEngine private constructor(
    val catalog: QuerySchemaCatalog,
    snapshots: List<QueryGateway>,
    events: List<QueryGateway>,
    policies: List<QueryGateway>,
    val datasets: DatasetQueryGateways,
    val datasetVisibility: DatasetVisibility,
    masks: PayloadMasks,
    observer: QueryObserver,
    /** The tenant a caller's queries of each aggregate type are restricted to (decision D116). */
    val tenants: TenantPolicy,
    /** Who each aggregate type belongs to besides its tenant (decision D189), as the Manifest says. */
    val ownerships: QueryOwnership,
    /** Whether a caller that may act across scopes may query across tenants (decision D254 ⑦). */
    val crossTenant: Boolean,
) {
    /**
     * The read side's scope rules over [tenants] and [crossTenant] (review R5.C-1): an edge builds its callers with
     * them, and every gateway and state read checks an HTTP caller against them again ([QueryTargets.recheck]).
     */
    @InternalWowApi
    val targets: QueryTargets = QueryTargets(tenants, crossTenant)

    private val snapshotGateways = snapshots.associateBy { it.aggregateType }
    private val eventGateways = events.associateBy { it.aggregateType }
    private val policyGateways = policies.associateBy { it.aggregateType }

    /** The snapshot query gateways, one per aggregate type a backend serves the snapshots of. */
    val snapshots: Collection<QueryGateway> get() = snapshotGateways.values

    /** The events query gateways, one per aggregate type a backend serves the events of. */
    val events: Collection<QueryGateway> get() = eventGateways.values

    /** The policy gateways of the types whose snapshots are not queried while policies may hide one (R3.3-3). */
    val policies: Collection<QueryGateway> get() = policyGateways.values

    /** The snapshot query gateway of [type], or null when no backend serves its snapshots. */
    fun snapshots(type: AggregateType): QueryGateway? = snapshotGateways[type]

    /** The events query gateway of [type], or null when no backend serves its events. */
    fun events(type: AggregateType): QueryGateway? = eventGateways[type]

    /** The gateway whose policies decide which of [type]'s states a caller sees, or null when none applies. */
    fun visibility(type: AggregateType): QueryGateway? = snapshotGateways[type] ?: policyGateways[type]

    /** Every entry's state reads: read, check against [visibility], mask, observed as the queries are. */
    val reads: ReadFacade = ReadFacade(::visibility, masks, observer, targets)

    /**
     * What a host gives the engine of [manifest], each from its configuration or the application: the [contexts] the
     * node hosts, the backends of the models, the schema sources and masks, the tenant and ABAC options, the
     * application's policies, filters and observers, the count caches, the committed states and how events are read.
     */
    @InternalWowApi
    @Suppress("TooManyFunctions") // One setter per input of the engine.
    class Builder(private val manifest: Manifest) {
        private var contexts: Set<String> = manifest.contexts.map { it.name }.toSet()
        private var snapshotBackend: QueryBackendFactory<QueryBackend>? = null
        private var servesSnapshots: (AggregateType) -> Boolean = { snapshotBackend != null }
        private var eventBackend: (AggregateType) -> QueryBackendBinding<QueryBackend>? = { null }
        private var datasets: QueryDatasets = QueryDatasets.NONE
        private var datasetBackend: DatasetQueryBackendFactory? = null
        private var datasetVisibility: DatasetVisibility? = null
        private var sources: List<QuerySchemaSource> = emptyList()
        private var strategies: MaskStrategyResolver = MaskStrategyResolver.NONE
        private var sensitivity: QuerySensitivityPolicy = QuerySensitivityPolicy.DEFAULT
        private var masks: PayloadMasks? = null
        private var tenants: TenantPolicy = TenantPolicy.CALLERS
        private var crossTenant: Boolean = false
        private var abac: AbacQueryOptions = AbacQueryOptions.DEFAULT
        private var policies: List<QueryPolicy> = emptyList()
        private var filters: List<QueryFilter> = emptyList()
        private var observers: List<QueryObserver> = emptyList()
        private var entryPolicy: QueryEntryPolicy = QueryEntryPolicy.DEFAULT
        private var snapshotCounts: QueryCountCache? = null
        private var eventCounts: QueryCountCache? = null
        private var committed: CommittedStates = CommittedStates.NONE
        private var reading: (AggregateType) -> ((EventRecord) -> EventRecord?) = { { it } }
        private var bulkheads: (AggregateType, QueryModel) -> QueryBulkhead? = { _, _ -> null }
        private var unpinned: (AggregateType) -> UnpinnedEvents = { UnpinnedEvents.ALLOW }

        /** The contexts the node hosts: every context of the Manifest by default. */
        fun contexts(contexts: Set<String>) = apply { this.contexts = contexts }

        /** The snapshot query backend, serving the snapshots of each type [serves] says (every type by default). */
        fun snapshots(backend: QueryBackendFactory<QueryBackend>?, serves: ((AggregateType) -> Boolean)? = null) =
            apply {
                snapshotBackend = backend
                servesSnapshots = serves ?: { backend != null }
            }

        /** The `events` query backend of each aggregate type (decision D235), null for a type it does not serve. */
        fun events(backend: (AggregateType) -> QueryBackendBinding<QueryBackend>?) = apply { eventBackend = backend }

        /** The datasets (decision D145) and their backend; without one, no dataset is served. */
        fun datasets(datasets: QueryDatasets, backend: DatasetQueryBackendFactory?) = apply {
            this.datasets = datasets
            datasetBackend = backend
        }

        /** Which datasets a caller sees; by default, by scope and ABAC ([DatasetVisibility.of]). */
        fun datasetVisibility(visibility: DatasetVisibility?) = apply { datasetVisibility = visibility }

        /** The schema sources beyond the Manifest's state schemas, the application's. */
        fun schemaSources(sources: List<QuerySchemaSource>) = apply { this.sources = sources }

        /** The mask strategies the schemas and payload masks resolve. */
        fun maskStrategies(strategies: MaskStrategyResolver) = apply { this.strategies = strategies }

        /** Whether a filter or sort may compare a `DISPLAY` value. */
        fun sensitivity(sensitivity: QuerySensitivityPolicy) = apply { this.sensitivity = sensitivity }

        /** The payload masks of states and events; by default the Manifest's, with the [maskStrategies]. */
        fun masks(masks: PayloadMasks?) = apply { this.masks = masks }

        /** The tenant a caller queries an aggregate type in (decision D116). */
        fun tenants(tenants: TenantPolicy) = apply { this.tenants = tenants }

        /**
         * Whether a caller that may act across scopes may query across tenants (decision D254 ⑦,
         * `wow.query.cross-tenant.enabled`); off by default.
         */
        fun crossTenant(enabled: Boolean) = apply { crossTenant = enabled }

        /** The ABAC options (`wow.query.abac`, decision D129). */
        fun abac(abac: AbacQueryOptions) = apply { this.abac = abac }

        /** The application's query policies, after the scope and ABAC ones. */
        fun policies(policies: List<QueryPolicy>) = apply { this.policies = policies }

        /** The application's query filters. */
        fun filters(filters: List<QueryFilter>) = apply { this.filters = filters }

        /** The application's query observers, beside the query log. */
        fun observers(observers: List<QueryObserver>) = apply { this.observers = observers }

        /** The entry gates and the in-process timeout. */
        fun entryPolicy(entryPolicy: QueryEntryPolicy) = apply { this.entryPolicy = entryPolicy }

        /** The count caches of the snapshot and the events gateways (decision D152); none by default. */
        fun counts(snapshots: QueryCountCache?, events: QueryCountCache?) = apply {
            snapshotCounts = snapshots
            eventCounts = events
        }

        /** The committed states the events model's visibility reads (the runtime's state reads). */
        fun committedStates(committed: CommittedStates) = apply { this.committed = committed }

        /** How each aggregate type's events are read: as its model reads them now (finding A4-16). */
        fun reading(reading: (AggregateType) -> ((EventRecord) -> EventRecord?)) = apply { this.reading = reading }

        /**
         * The bulkhead of the store each aggregate type's [model] (snapshots or events) is queried on (review R3.4-2),
         * one instance per store, null for none: the queries of every model on one store share it; for
         * [QueryModel.DATASET], the one the queries of the dataset of that name run under (decision D247).
         */
        fun bulkheads(bulkheads: (type: AggregateType, model: QueryModel) -> QueryBulkhead?) = apply {
            this.bulkheads = bulkheads
        }

        /** Whether each aggregate type's events queries that name no aggregate run ([UnpinnedEvents.ALLOW] by default). */
        fun unpinnedEvents(unpinned: (AggregateType) -> UnpinnedEvents) = apply { this.unpinned = unpinned }

        fun build(): QueryEngine {
            val masks = masks ?: PayloadMasks(manifest, strategies)
            val catalog = catalog()
            val observer = QueryObserver.of(listOf(QueryLogObserver()) + observers)
            val ownerships = QueryOwnership.of(manifest)
            val gatewayPolicies = scopePolicies(ownerships) + policies
            val snapshots = snapshotGateways(catalog, gatewayPolicies, observer)
            // Only ABAC and the application's policies hide an aggregate from a caller of its scope: a state read
            // checks them through the policy gateway of a type whose snapshots are not queried.
            val restricts = abac.enabled || policies.isNotEmpty()
            // Only a pinned one hides an aggregate's streams beyond its filter on them (decisions D254, D259).
            val hides = gatewayPolicies.any { it.events == EventsMode.PINNED }
            val queried = snapshots.map { it.aggregateType }.toSet()
            val policyGateways = if (restricts) {
                ManifestTypes.hosted(manifest, contexts).filterNot(queried::contains).map { type ->
                    SnapshotPolicyGateway(type, catalog.policyProvider(type), gatewayPolicies, entryPolicy)
                }
            } else {
                emptyList()
            }
            val byType = (snapshots + policyGateways).associateBy { it.aggregateType }
            val eventVisibility = EventVisibility.of(
                byType::get,
                hides,
                queried::contains,
                observer,
                committed::committed
            )
            val events = ManifestTypes.events(manifest, contexts).mapNotNull { type ->
                val binding = eventBackend(type) ?: return@mapNotNull null
                EventQueryGateways.create(
                    aggregateType = type,
                    backend = binding.backend.under(bulkheads(type, QueryModel.EVENTS)),
                    schemaProvider = catalog.provider(type, QueryModel.EVENTS),
                    filters = filters,
                    policies = gatewayPolicies,
                    observer = observer,
                    entryPolicy = entryPolicy,
                    counts = eventCounts,
                    visibility = eventVisibility,
                    tenants = tenants,
                    masks = masks,
                    reading = reading(type),
                    unpinned = unpinned(type),
                )
            }
            val datasetGateways = datasetGateways(catalog, gatewayPolicies, observer)
            return QueryEngine(
                catalog,
                snapshots,
                events,
                policyGateways,
                datasetGateways,
                datasetVisibility ?: DatasetVisibility.of(abac),
                masks,
                observer,
                tenants,
                ownerships,
                crossTenant,
            )
        }

        /** The gateway of each dataset over the dataset backend; none without one, with a warning if any is declared. */
        private fun datasetGateways(
            catalog: QuerySchemaCatalog,
            gatewayPolicies: List<QueryPolicy>,
            observer: QueryObserver,
        ): DatasetQueryGateways {
            val factory = datasetBackend
            if (factory == null) {
                if (datasets.all.isNotEmpty()) {
                    logger.warn {
                        "No dataset query backend is wired: the datasets ${datasets.all.map { it.name }} are not " +
                            "served; add wow-clickhouse with wow.clickhouse.endpoint, or a DatasetQueryBackendFactory bean."
                    }
                }
                return DatasetQueryGateways.NONE
            }
            return DatasetQueryGateways.of(
                datasets,
                catalog,
                factory,
                filters = filters,
                policies = gatewayPolicies,
                observer = observer,
                entryPolicy = entryPolicy,
                bulkhead = { name -> bulkheads(name, QueryModel.DATASET) },
            )
        }

        /** The types whose snapshots a backend serves. */
        private fun servedSnapshots(): List<AggregateType> =
            if (snapshotBackend == null) {
                emptyList()
            } else {
                ManifestTypes.snapshots(manifest, contexts).filter(servesSnapshots)
            }

        private fun catalog(): QuerySchemaCatalog = QuerySchemaCatalog(
            storages = QueryBackendStorages(snapshotBackend, datasetBackend, datasets, eventBackend),
            compiler = QueryModelCompiler.of(
                listOf(ManifestStateSchemas(manifest, strategies)) + sources,
                sensitivity,
                datasets,
                EventsQuerySchemas(manifest, strategies),
            ),
            aggregates = (servedSnapshots() + ManifestTypes.events(manifest, contexts)).distinct(),
            datasets = datasets,
        )

        private fun snapshotGateways(
            catalog: QuerySchemaCatalog,
            gatewayPolicies: List<QueryPolicy>,
            observer: QueryObserver,
        ): List<QueryGateway> {
            val factory = snapshotBackend ?: return emptyList()
            return servedSnapshots().map { type ->
                DefaultQueryGateway(
                    aggregateType = type,
                    backend = factory.create(type).backend.under(bulkheads(type, QueryModel.SNAPSHOT)),
                    schemaProvider = catalog.provider(type, QueryModel.SNAPSHOT),
                    filters = filters,
                    policies = gatewayPolicies,
                    observer = observer,
                    entryPolicy = entryPolicy,
                    counts = snapshotCounts,
                )
            }
        }

        /**
         * The scope and ABAC policies every gateway runs first (decision D129), the owner applying to the aggregates
         * the Manifest says belong to their owners (decision D189).
         */
        private fun scopePolicies(ownerships: QueryOwnership): List<QueryPolicy> = listOf(
            ScopeQueryPolicy(tenants, ownerships = ownerships, crossTenant = crossTenant),
            AbacQueryPolicy(abac),
        )
    }

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}
