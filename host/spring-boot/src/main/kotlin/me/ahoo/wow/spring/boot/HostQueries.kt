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
import kotlinx.coroutines.runBlocking
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.QueryBackendFactory
import me.ahoo.wow.query.QueryBulkhead
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryTenantPolicy
import me.ahoo.wow.query.events.UnpinnedEvents
import me.ahoo.wow.query.index.QueryIndexDdl
import me.ahoo.wow.query.index.QueryIndexDeclaration
import me.ahoo.wow.query.memory.InMemoryEventQueryStorage
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.store.EventQueries
import me.ahoo.wow.query.store.QueryIndexReport
import me.ahoo.wow.query.store.SnapshotQueries
import me.ahoo.wow.query.store.StoreQueryOptions
import me.ahoo.wow.query.store.StoreQueryProvider
import me.ahoo.wow.runtime.admission.TenantPolicy
import me.ahoo.wow.runtime.state.ReplayGate
import me.ahoo.wow.runtime.store.StoreTypes
import me.ahoo.wow.spi.store.Store
import org.springframework.context.SmartLifecycle
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.toKotlinDuration

/**
 * The query tenant policy over the host's [TenantPolicy] (decision D116): a query of an aggregate type whose tenant
 * `wow.aggregates.[<context>.<aggregate>].tenant` fixes is restricted to that tenant, any other to its caller's, as a
 * command or a state read of it is.
 */
internal class HostQueryTenants(private val tenants: TenantPolicy) : QueryTenantPolicy {
    override fun tenantOf(type: AggregateType, caller: QueryCaller): String? =
        tenants.fixedTenant(type) ?: caller.tenantId
}

/**
 * The snapshot query backend of the node (decision D110): the [factory] of each aggregate type's backend and storage
 * facts (null when no backend serves the node's snapshots), and, for a backend the host manages, how it [prepare]s an aggregate's storage and [manages][manageIndexes]
 * its `x-wow-index` indexes at startup (decision D151).
 */
@InternalWowApi
class SnapshotQueryBackend(
    val factory: QueryBackendFactory<*>?,
    private val prepare: (AggregateType) -> Unit = {},
    private val indexes: ((AggregateType, QueryModelSchema, List<QueryIndexDeclaration>) -> QueryIndexReport)? = null,
    /** Whether the backend queries [AggregateType]'s snapshots: every type's, when it has a [factory]. */
    private val serves: (AggregateType) -> Boolean = { factory != null },
    /** The store whose query bulkhead [AggregateType]'s snapshot queries run under; none for the application's own. */
    private val store: (AggregateType) -> String? = { null },
) {
    /** Whether the host manages this backend's indexes; an application's own backend manages its own. */
    val managesIndexes: Boolean get() = factory != null && indexes != null

    /** Whether the backend queries [type]'s snapshots: their store has a query backend (decision D177). */
    fun serves(type: AggregateType): Boolean = serves.invoke(type)

    /** Makes sure [type]'s storage exists, so its facts are the storage's own. */
    fun prepare(type: AggregateType) = prepare.invoke(type)

    /** The store [type]'s snapshot queries run on (review R3.4-2), or null when the host does not know it. */
    fun storeOf(type: AggregateType): String? = store.invoke(type)

    /** Manages [declarations], [type]'s indexes on its query model [schema], as `wow.query.index.ddl` says. */
    fun manageIndexes(
        type: AggregateType,
        schema: QueryModelSchema,
        declarations: List<QueryIndexDeclaration>,
    ): QueryIndexReport = indexes?.invoke(type, schema, declarations) ?: QueryIndexReport.NONE

    @InternalWowApi
    companion object {
        /** No backend: the node's snapshots are not queried. */
        @JvmField
        val NONE: SnapshotQueryBackend = SnapshotQueryBackend(null)

        /** The snapshot queries of one store ([StoreQueryProvider.snapshots]) as a backend. */
        fun of(queries: SnapshotQueries): SnapshotQueryBackend = SnapshotQueryBackend(
            factory = QueryBackendFactory { type -> queries.binding(type) },
            prepare = queries::prepare,
            indexes = queries::manageIndexes.takeIf { queries.managesIndexes },
        )

        /**
         * The backend of each aggregate type's snapshot store (decision D177), [storeOf]: [backendOf] gives it, null
         * when its store has none, whose snapshots are then not queried. [NONE] when no type's store has one.
         */
        fun routing(
            types: Collection<AggregateType>,
            storeOf: (AggregateType) -> String? = { null },
            backendOf: (AggregateType) -> SnapshotQueryBackend?,
        ): SnapshotQueryBackend {
            if (types.none { backendOf(it) != null }) {
                return NONE
            }
            return SnapshotQueryBackend(
                factory = QueryBackendFactory { type ->
                    checkNotNull(
                        backendOf(type)?.factory
                    ) { "The snapshots of $type are on a store no query backend reads." }
                        .create(type)
                },
                prepare = { type -> backendOf(type)?.prepare(type) },
                indexes = { type, schema, declarations ->
                    backendOf(type)?.manageIndexes(type, schema, declarations) ?: QueryIndexReport.NONE
                },
                serves = { type -> backendOf(type)?.serves(type) == true },
                store = { type -> storeOf(type)?.takeIf { backendOf(type) != null } },
            )
        }
    }
}

/**
 * The queries of a store in memory, built into the host, both only when `wow.query.memory.enabled` ([enabled]) is on
 * (decision D209, review R3.4-1): its snapshots from the in-memory reference query backend it was given
 * ([MemoryStore.queries]), and its logs scanned in memory ([InMemoryEventQueryStorage.of]) for the `events` model.
 */
internal class MemoryQueryProvider(private val enabled: Boolean = false) : StoreQueryProvider {
    override val type: String = StoreTypes.MEMORY

    override fun snapshots(store: Store, options: StoreQueryOptions): SnapshotQueries? =
        (store as? MemoryStore)?.queries?.let { queries ->
            object : SnapshotQueries {
                override fun binding(type: AggregateType): QueryBackendBinding<QueryBackend> = queries.create(type)
            }
        }

    override fun events(store: Store, options: StoreQueryOptions): EventQueries? {
        if (!enabled) return null
        val scanned = ConcurrentHashMap<String, InMemoryEventQueryStorage>()
        return EventQueries { type ->
            store.openedLog(type.context)?.let { log ->
                scanned.computeIfAbsent(type.context) { InMemoryEventQueryStorage.of(log) }.create(type)
            }
        }
    }
}

/**
 * The `events` query backend of the node (decision D235): the [binding] of each aggregate type's events backend and
 * storage facts, null for a type no backend serves (its events are then not queried), and the [store] its queries run
 * on (review R3.4-2), null for the application's own backend.
 */
@InternalWowApi
class EventQueryBackend(
    private val store: (AggregateType) -> String? = { null },
    private val binding: (AggregateType) -> QueryBackendBinding<QueryBackend>?,
) {
    /** The binding of [type]'s events backend, or null when none serves them. */
    fun binding(type: AggregateType): QueryBackendBinding<QueryBackend>? = binding.invoke(type)

    /** The store [type]'s events queries run on, or null when the host does not know it. */
    fun storeOf(type: AggregateType): String? = store.invoke(type)

    @InternalWowApi
    companion object {
        /** No backend: the node's events are not queried. */
        @JvmField
        val NONE: EventQueryBackend = EventQueryBackend { null }
    }
}

/**
 * The stores the `events` queries of the aggregate types run on (decision D235 ④: one events backend per aggregate
 * type, chosen by configuration as its other stores are, D110, D177).
 */
internal object HostEventStores {
    /**
     * The aggregate types whose `wow.aggregates.[<context>.<aggregate>].query.events.store` names a store, each with
     * that store, one of [stores]; any other type's events are queried on its log store.
     */
    fun configured(properties: WowProperties?, stores: Set<String>): Map<AggregateType, String> =
        properties?.aggregates.orEmpty().mapNotNull { (key, aggregate) ->
            val store = aggregate.query.events.store ?: return@mapNotNull null
            val context = key.substringBefore('.', "")
            val name = key.substringAfter('.', "")
            require(context.isNotBlank() && name.isNotBlank()) {
                "wow.aggregates.[$key] names no aggregate type: <context>.<aggregate>."
            }
            require(store in stores) {
                "wow.aggregates.[$key].query.events.store is $store, which is none of the stores $stores (wow.stores)."
            }
            AggregateType(context, name) to store
        }.toMap()
}

/**
 * The query bulkheads of the node's [stores] (review R3.4-2), by store name: each store's bound is
 * `wow.stores.<name>.query.max-concurrent` ([settings]), else `wow.query.max-concurrent` ([global]), else its query
 * provider's ([StoreQueryProvider.maxConcurrent], from its pool); `0` sets none. A store whose type has no query
 * provider has no queries to bound. A query beyond the bound waits (decision D247) at most
 * `wow.stores.<name>.query.max-wait`, else `wow.query.max-wait` (200ms), in a queue of at most
 * `wow.stores.<name>.query.max-queued`, else `wow.query.max-queued` (4 per permit).
 */
internal object HostQueryBulkheads {
    fun of(
        stores: Map<String, Store>,
        providers: StoreProviders,
        global: QueryProperties,
        settings: Map<String, WowProperties.Store>,
    ): Map<String, QueryBulkhead> = stores.mapNotNull { (name, store) ->
        val provider = providers.queriesOf(store.type) ?: return@mapNotNull null
        val own = settings[name]?.query
        val max = own?.maxConcurrent ?: global.maxConcurrent ?: provider.maxConcurrent(store)
        check(max >= 0) {
            "wow.stores.$name.query.max-concurrent (or wow.query.max-concurrent) is $max: 0 for no bound, or more."
        }
        if (max == 0) return@mapNotNull null
        val wait = own?.maxWait ?: global.maxWait
        val queued = own?.maxQueued ?: global.maxQueued ?: (max * QueryBulkhead.DEFAULT_QUEUE_FACTOR)
        name to QueryBulkhead(name, max, wait.toKotlinDuration(), queued)
    }.toMap()

    /**
     * The state reads' replays (decision D246 ⑥), each under the bulkhead of its aggregate type's log store
     * ([logStoreOf]) among [bulkheads]; one of a store without a bulkhead runs at once.
     */
    fun replays(bulkheads: Map<String, QueryBulkhead>, logStoreOf: (AggregateType) -> String?): ReplayGate =
        if (bulkheads.isEmpty()) {
            ReplayGate.OPEN
        } else {
            object : ReplayGate {
                override suspend fun <T> replay(ref: AggregateRef, read: suspend () -> T): T {
                    val bulkhead = logStoreOf(ref.type)?.let(bulkheads::get) ?: return read()
                    return bulkhead.run(read)
                }

                override fun toString(): String = "ReplayGate(${bulkheads.values})"
            }
        }

    /**
     * Whether [type]'s events queries that name no aggregate run (review R3.4-2): its
     * `wow.aggregates.[<context>.<aggregate>].query.events.unpinned`, else that of its events [store], else [global].
     */
    fun unpinned(
        type: AggregateType,
        store: String?,
        global: UnpinnedEvents,
        properties: WowProperties?,
    ): UnpinnedEvents = properties?.aggregates?.get("${type.context}.${type.name}")?.query?.events?.unpinned
        ?: store?.let { properties?.stores?.get(it)?.query?.events?.unpinned }
        ?: global
}

/**
 * Manages the indexes of every aggregate type in [types] at startup (decision D151), after the backends' schemas
 * exist ([BackendsLifecycle]) and before the node starts: each aggregate's storage is prepared, its query model
 * compiled from [catalog], and its Manifest's `x-wow-index` planned and managed by [backend] as [ddl] says. A key
 * the model cannot index fails the start.
 */
@InternalWowApi
class QueryIndexesLifecycle(
    private val manifest: Manifest,
    private val types: List<AggregateType>,
    private val backend: SnapshotQueryBackend,
    private val catalog: QuerySchemaCatalog,
    private val ddl: QueryIndexDdl,
) : SmartLifecycle {
    @Volatile
    private var running = false

    /** What the last start did, by aggregate type. */
    @Volatile
    var reports: Map<AggregateType, QueryIndexReport> = emptyMap()
        private set

    override fun start() {
        if (ddl != QueryIndexDdl.NONE && backend.managesIndexes) {
            reports = types.filter(backend::serves).associateWith { type ->
                backend.prepare(type)
                val schema = runBlocking { catalog.schema(type, QueryModel.SNAPSHOT) }
                val declarations = QueryIndexDeclaration.of(manifest, type.context, type.name)
                backend.manageIndexes(type, schema, declarations).also { report ->
                    if (report.created.isNotEmpty()) {
                        logger.info { "Created the query indexes ${report.created} of $type." }
                    }
                }
            }
        }
        running = true
    }

    override fun stop() {
        running = false
    }

    override fun isRunning(): Boolean = running

    override fun getPhase(): Int = WowLifecycles.QUERY_INDEXES_PHASE

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}
