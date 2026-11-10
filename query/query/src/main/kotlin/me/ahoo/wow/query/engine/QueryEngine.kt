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
import me.ahoo.wow.api.access.AccessPolicy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.query.DefaultQueryGateway
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.admission.QueryBulkhead
import me.ahoo.wow.query.admission.QueryTargets
import me.ahoo.wow.query.admission.under
import me.ahoo.wow.query.backend.QueryBackendStorages
import me.ahoo.wow.query.backend.QueryBindings
import me.ahoo.wow.query.cache.QueryCountCache
import me.ahoo.wow.query.dataset.DatasetQueryGateways
import me.ahoo.wow.query.dataset.DatasetVisibility
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasetChange
import me.ahoo.wow.query.dataset.QueryDatasetListener
import me.ahoo.wow.query.dataset.QueryDatasetScope
import me.ahoo.wow.query.dataset.QueryDatasets
import me.ahoo.wow.query.events.EventQueryGateways
import me.ahoo.wow.query.events.UnpinnedEvents
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.mask.PayloadMasks
import me.ahoo.wow.query.observe.QueryLogObserver
import me.ahoo.wow.query.observe.QueryObserver
import me.ahoo.wow.query.policy.AccessPolicies
import me.ahoo.wow.query.policy.AccessQueryPolicy
import me.ahoo.wow.query.policy.QueryEntryPolicy
import me.ahoo.wow.query.policy.QueryOwnership
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.policy.ReadAuthorizer
import me.ahoo.wow.query.policy.ScopeQueryPolicy
import me.ahoo.wow.query.schema.EventsQuerySchemas
import me.ahoo.wow.query.schema.MaskStrategyResolver
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.schema.QuerySensitivityPolicy
import me.ahoo.wow.query.state.SnapshotPolicyGateway
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.tenant.TenantPolicy

/**
 * The read side of a node, composed once: the gateways of the three query models (the
 * [snapshots] and [events] of each aggregate type a backend serves, the [datasets]), the schema [catalog] they compile
 * against, which aggregates and datasets a caller sees ([visibility], [datasetVisibility]), and the [reads] every entry
 * reads a state through. A host only maps its configuration onto a [Builder]; the security rules are the builder's:
 *
 * - every gateway runs the scope policy (the tenant, the owner of the aggregates their owners hold)
 *   first, then the access policies of the aggregates that have one ([AccessQueryPolicy], checked against the
 *   Manifest as the engine is built: [AccessPolicies.of]), then the application's policies, and is observed by the
 *   query log and the application's observers;
 * - when an access policy or an application's policy may hide an aggregate from a caller of its scope, a type whose
 *   snapshots are not queried still has its policies evaluated on its states ([SnapshotPolicyGateway]), so its state
 *   reads and its events fail closed;
 * - an aggregate the snapshot queries hide is hidden from the state reads too, and from the events model: an access
 *   policy by its filter on the access attributes each stream records, the aggregate's current ones; an
 *   application's policy, which reads the state, by pinning the events queries to the aggregates they name;
 * - every query of the snapshots and the events runs under its store's [QueryBulkhead], whichever entry
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
    /** The tenant a caller's queries of each aggregate type are restricted to. */
    val tenants: TenantPolicy,
    /** Who each aggregate type belongs to besides its tenant, as the Manifest says. */
    val ownerships: QueryOwnership,
    /** Whether a caller that may act across scopes may query across tenants. */
    val crossTenant: Boolean,
    /** The access policies of the node's aggregates, each resolved to its aggregate. */
    val access: AccessPolicies,
    /** Who may read each model at all, asked before every query, state read and trace. */
    readAuthorizer: ReadAuthorizer,
) {
    /**
     * The read side's scope rules over [tenants] and [crossTenant]: an edge builds its callers with
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

    /** The policy gateways of the types whose snapshots are not queried while policies may hide one. */
    val policies: Collection<QueryGateway> get() = policyGateways.values

    /** The snapshot query gateway of [type], or null when no backend serves its snapshots. */
    fun snapshots(type: AggregateType): QueryGateway? = snapshotGateways[type]

    /** The events query gateway of [type], or null when no backend serves its events. */
    fun events(type: AggregateType): QueryGateway? = eventGateways[type]

    /** The gateway whose policies decide which of [type]'s states a caller sees, or null when none applies. */
    fun visibility(type: AggregateType): QueryGateway? = snapshotGateways[type] ?: policyGateways[type]

    /** Every entry's state reads: read, check against [visibility], mask, observed as the queries are. */
    val reads: ReadFacade = ReadFacade(::visibility, masks, observer, targets, readAuthorizer)

    /**
     * What a host gives the engine of [manifest], each from its configuration or the application: the [contexts] the
     * node hosts, the backends of the models, the schema sources and masks, the tenant options, the
     * application's policies, filters and observers, the count caches and how events are read.
     */
    @InternalWowApi
    @Suppress("TooManyFunctions") // One setter per input of the engine.
    class Builder(private val manifest: Manifest) {
        private var contexts: Set<String> = manifest.contexts.map { it.name }.toSet()
        private var snapshotBackend: QueryBindings<AggregateType>? = null
        private var servesSnapshots: (AggregateType) -> Boolean = { snapshotBackend != null }
        private var eventBackend: QueryBindings<AggregateType>? = null
        private var datasets: QueryDatasets = QueryDatasets.NONE
        private var datasetBackend: QueryBindings<QueryDataset>? = null
        private var datasetVisibility: DatasetVisibility? = null
        private var sources: List<QuerySchemaSource> = emptyList()
        private var strategies: MaskStrategyResolver = MaskStrategyResolver.NONE
        private var sensitivity: QuerySensitivityPolicy = QuerySensitivityPolicy.DEFAULT
        private var masks: PayloadMasks? = null
        private var tenants: TenantPolicy = TenantPolicy.CALLERS
        private var crossTenant: Boolean = false
        private var policies: List<QueryPolicy> = emptyList()
        private var accessPolicies: List<AccessPolicy<*>> = emptyList()
        private var aggregatesOf: (Class<*>) -> List<AggregateType> = { emptyList() }
        private var strictAccess: Boolean = true
        private var filters: List<QueryFilter> = emptyList()
        private var observers: List<QueryObserver> = emptyList()
        private var entryPolicy: QueryEntryPolicy = QueryEntryPolicy.DEFAULT
        private var readAuthorizer: ReadAuthorizer = ReadAuthorizer.ALLOW_ALL
        private var snapshotCounts: QueryCountCache? = null
        private var eventCounts: QueryCountCache? = null
        private var reading: (AggregateType) -> ((EventRecord) -> EventRecord?) = { { it } }
        private var bulkheads: (AggregateType, QueryModel) -> QueryBulkhead? = { _, _ -> null }
        private var unpinned: (AggregateType) -> UnpinnedEvents = { UnpinnedEvents.ALLOW }

        /** The contexts the node hosts: every context of the Manifest by default. */
        fun contexts(contexts: Set<String>) = apply { this.contexts = contexts }

        /** The snapshot query backend, serving the snapshots of each type [serves] says (every type by default). */
        fun snapshots(backend: QueryBindings<AggregateType>?, serves: ((AggregateType) -> Boolean)? = null) =
            apply {
                snapshotBackend = backend
                servesSnapshots = serves ?: { backend != null }
            }

        /** The `events` query backend of each aggregate type, none for a type it does not serve. */
        fun events(backend: QueryBindings<AggregateType>?) = apply { eventBackend = backend }

        /** The datasets and their backend; without one, no dataset is served. */
        fun datasets(datasets: QueryDatasets, backend: QueryBindings<QueryDataset>?) = apply {
            this.datasets = datasets
            datasetBackend = backend
        }

        /** Which datasets a caller sees; by default, by scope ([DatasetVisibility.DEFAULT]). */
        fun datasetVisibility(visibility: DatasetVisibility?) = apply { datasetVisibility = visibility }

        /** The schema sources beyond the Manifest's state schemas, the application's. */
        fun schemaSources(sources: List<QuerySchemaSource>) = apply { this.sources = sources }

        /** The mask strategies the schemas and payload masks resolve. */
        fun maskStrategies(strategies: MaskStrategyResolver) = apply { this.strategies = strategies }

        /** Whether a filter or sort may compare a `DISPLAY` value. */
        fun sensitivity(sensitivity: QuerySensitivityPolicy) = apply { this.sensitivity = sensitivity }

        /** The payload masks of states and events; by default the Manifest's, with the [maskStrategies]. */
        fun masks(masks: PayloadMasks?) = apply { this.masks = masks }

        /** The tenant a caller queries an aggregate type in. */
        fun tenants(tenants: TenantPolicy) = apply { this.tenants = tenants }

        /**
         * Whether a caller that may act across scopes may query across tenants
         * (`wow.query.allow-cross-tenant`); off by default.
         */
        fun crossTenant(enabled: Boolean) = apply { crossTenant = enabled }

        /** Wow's own query policies, after the scope one. */
        fun policies(policies: List<QueryPolicy>) = apply { this.policies = policies }

        /**
         * The access policies of the node's aggregates (the service's [AccessPolicy] beans), each of the aggregate
         * [aggregatesOf] gives for its state type, the types whose state is of that class. [strict] when the node hosts
         * every context of the service: a policy of no aggregate of the node is then refused, rather than left to the
         * node hosting its aggregate. [build] checks them against the Manifest ([AccessPolicies.of]).
         */
        @JvmOverloads
        fun accessPolicies(
            policies: List<AccessPolicy<*>>,
            aggregatesOf: (Class<*>) -> List<AggregateType>,
            strict: Boolean = true,
        ) = apply {
            accessPolicies = policies
            this.aggregatesOf = aggregatesOf
            strictAccess = strict
        }

        /** The application's query filters. */
        fun filters(filters: List<QueryFilter>) = apply { this.filters = filters }

        /** The application's query observers, beside the query log. */
        fun observers(observers: List<QueryObserver>) = apply { this.observers = observers }

        /** The entry gates and the in-process timeout. */
        fun entryPolicy(entryPolicy: QueryEntryPolicy) = apply { this.entryPolicy = entryPolicy }

        /**
         * Who may read each model at all ([ReadAuthorizer]), all of [authorizers] asked in their order: once per query,
         * state read and trace, before anything is read. None by default: every caller may read, the rows still the
         * access policies' and the default rule's.
         */
        fun readAuthorizers(authorizers: List<ReadAuthorizer>) =
            apply { this.readAuthorizer = ReadAuthorizer.allOf(authorizers) }

        /** The count caches of the snapshot and the events gateways; none by default. */
        fun counts(snapshots: QueryCountCache?, events: QueryCountCache?) = apply {
            snapshotCounts = snapshots
            eventCounts = events
        }

        /** How each aggregate type's events are read: as its model reads them now. */
        fun reading(reading: (AggregateType) -> ((EventRecord) -> EventRecord?)) = apply { this.reading = reading }

        /**
         * The bulkhead of the store each aggregate type's [model] (snapshots or events) is queried on,
         * one instance per store, null for none: the queries of every model on one store share it; for
         * [QueryModel.DATASET], the one the queries of the dataset of that name run under.
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
            val access = AccessPolicies.of(manifest, contexts, accessPolicies, aggregatesOf, strictAccess)
            val scope = scopePolicies(ownerships, access)
            val gatewayPolicies = scope + accessPolicy(access) + policies
            datasets.requireAccess(access)
            val snapshots = snapshotGateways(catalog, gatewayPolicies, observer)
            val queried = snapshots.map { it.aggregateType }.toSet()
            val policyGateways = policyGateways(catalog, queried, gatewayPolicies, access)
            val events = ManifestTypes.events(manifest, contexts).mapNotNull { type ->
                val binding = eventBackend?.binding(type) ?: return@mapNotNull null
                EventQueryGateways.create(
                    aggregateType = type,
                    backend = binding.backend.under(bulkheads(type, QueryModel.EVENTS)),
                    schemaProvider = catalog.provider(type, QueryModel.EVENTS),
                    filters = filters,
                    policies = gatewayPolicies,
                    observer = observer,
                    entryPolicy = entryPolicy,
                    counts = eventCounts,
                    masks = masks,
                    reading = reading(type),
                    unpinned = unpinned(type),
                    authorizer = readAuthorizer,
                )
            }
            val datasetGateways = datasetGateways(catalog, gatewayPolicies, observer)
            return QueryEngine(
                catalog,
                snapshots,
                events,
                policyGateways,
                datasetGateways,
                datasetVisibility ?: DatasetVisibility.DEFAULT,
                masks,
                observer,
                tenants,
                ownerships,
                crossTenant,
                access,
                readAuthorizer,
            )
        }

        /**
         * The policy gateways of the types whose snapshots are not [queried], when an access or an application's
         * policy may hide an aggregate from a caller of its scope: a state read checks them through one.
         */
        private fun policyGateways(
            catalog: QuerySchemaCatalog,
            queried: Set<AggregateType>,
            gatewayPolicies: List<QueryPolicy>,
            access: AccessPolicies,
        ): List<QueryGateway> {
            if (policies.isEmpty() && access.governed.isEmpty()) {
                return emptyList()
            }
            return ManifestTypes.hosted(manifest, contexts).filterNot(queried::contains).map { type ->
                SnapshotPolicyGateway(type, catalog.policyProvider(type), gatewayPolicies, entryPolicy)
            }
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
                            "served; add wow-clickhouse with wow.clickhouse.endpoint, or a QueryBindings<QueryDataset> bean."
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
                authorizer = readAuthorizer,
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
                    backend = checkNotNull(factory.binding(type)) {
                        "The snapshots of $type are served, but the snapshot query backend answers no binding for them."
                    }.backend.under(bulkheads(type, QueryModel.SNAPSHOT)),
                    schemaProvider = catalog.provider(type, QueryModel.SNAPSHOT),
                    filters = filters,
                    policies = gatewayPolicies,
                    observer = observer,
                    entryPolicy = entryPolicy,
                    counts = snapshotCounts,
                    authorizer = readAuthorizer,
                )
            }
        }

        /**
         * The scope policy every gateway runs first, the owner applying to the aggregates the Manifest says belong to
         * their owners, the owner and space to those without an access policy of their own.
         */
        private fun scopePolicies(ownerships: QueryOwnership, access: AccessPolicies): List<QueryPolicy> = listOf(
            ScopeQueryPolicy(tenants, ownerships = ownerships, crossTenant = crossTenant, access = access),
        )

        /** The access policies' query policy, when an aggregate has one. */
        private fun accessPolicy(access: AccessPolicies): List<QueryPolicy> =
            if (access.governed.isEmpty()) emptyList() else listOf(AccessQueryPolicy(access))

        /**
         * Refuses a dataset bound to an aggregate the node does not have ([QueryDatasetScope.access]), now and as one
         * joins: no policy of it could decide its rows.
         */
        private fun QueryDatasets.requireAccess(access: AccessPolicies) {
            val check = { dataset: QueryDataset ->
                val bound = dataset.scope.access?.aggregate
                require(bound == null || access.has(bound)) {
                    "Dataset [${dataset.name}] is bound to the aggregate $bound, which is not an aggregate of this " +
                        "node: its access policy cannot decide the dataset's rows."
                }
            }
            all.forEach(check)
            if (this === QueryDatasets.NONE) return
            listen(
                object : QueryDatasetListener {
                    override fun validate(change: QueryDatasetChange) {
                        if (change !is QueryDatasetChange.Removed) check(change.dataset)
                    }

                    override fun apply(change: QueryDatasetChange) = Unit
                },
            )
        }
    }

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}
