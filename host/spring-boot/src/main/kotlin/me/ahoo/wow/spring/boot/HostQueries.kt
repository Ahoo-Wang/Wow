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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.QueryBackendFactory
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryTenantPolicy
import me.ahoo.wow.query.index.QueryIndexDdl
import me.ahoo.wow.query.index.QueryIndexDeclaration
import me.ahoo.wow.query.memory.InMemoryEventQueryStorage
import me.ahoo.wow.query.schema.ManifestQuerySchemaSource
import me.ahoo.wow.query.schema.MaskStrategyResolver
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.store.EventQueries
import me.ahoo.wow.query.store.QueryIndexReport
import me.ahoo.wow.query.store.SnapshotQueries
import me.ahoo.wow.query.store.StoreQueryOptions
import me.ahoo.wow.query.store.StoreQueryProvider
import me.ahoo.wow.runtime.admission.TenantPolicy
import me.ahoo.wow.runtime.store.StoreTypes
import me.ahoo.wow.spi.store.Store
import org.springframework.context.SmartLifecycle
import java.util.concurrent.ConcurrentHashMap

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
 * The Manifest's state schemas as the query schema source ([ManifestQuerySchemaSource], decision D37), for each
 * aggregate whose state schema the Manifest carries in `$defs`; the Gradle plugin's manifest-tools writes them
 * (decision D73). An aggregate without one, from a module built with the compiler alone, is queried by its system
 * fields only, and a warning says so once.
 */
internal class ManifestStateSchemas(
    private val manifest: Manifest,
    strategies: MaskStrategyResolver = MaskStrategyResolver.NONE,
) : QuerySchemaSource {
    private val source = ManifestQuerySchemaSource(manifest, strategies)
    private val warned = ConcurrentHashMap.newKeySet<AggregateType>()

    override val priority: Int = source.priority

    override suspend fun load(context: QuerySchemaContext): List<QuerySchemaDeclaration> {
        if (declares(context.aggregateType)) return source.load(context)
        if (warned.add(context.aggregateType)) {
            logger.warn {
                "The Manifest carries no state schema of ${context.aggregateType}: only its system fields are " +
                    "queried. Build its module with the Wow Gradle plugin, which writes the schemas."
            }
        }
        return emptyList()
    }

    /** Whether the Manifest carries the state schema of [type]. */
    fun declares(type: AggregateType): Boolean {
        val aggregate = manifest.contexts.firstOrNull { it.name == type.context }
            ?.aggregates?.firstOrNull { it.name == type.name } ?: return false
        return Manifest.schemaKey(aggregate.state.schema) in manifest.defs
    }

    private companion object {
        val logger = KotlinLogging.logger {}
    }
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
) {
    /** Whether the host manages this backend's indexes; an application's own backend manages its own. */
    val managesIndexes: Boolean get() = factory != null && indexes != null

    /** Whether the backend queries [type]'s snapshots: their store has a query backend (decision D177). */
    fun serves(type: AggregateType): Boolean = serves.invoke(type)

    /** Makes sure [type]'s storage exists, so its facts are the storage's own. */
    fun prepare(type: AggregateType) = prepare.invoke(type)

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
         * The backend of each aggregate type's snapshot store (decision D177): [backendOf] gives it, null when its
         * store has none, whose snapshots are then not queried. [NONE] when no type's store has one.
         */
        fun routing(
            types: Collection<AggregateType>,
            backendOf: (AggregateType) -> SnapshotQueryBackend?
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
 * storage facts, null for a type no backend serves (its events are then not queried).
 */
@InternalWowApi
class EventQueryBackend(private val binding: (AggregateType) -> QueryBackendBinding<QueryBackend>?) {
    /** The binding of [type]'s events backend, or null when none serves them. */
    fun binding(type: AggregateType): QueryBackendBinding<QueryBackend>? = binding.invoke(type)

    @InternalWowApi
    companion object {
        /** No backend: the node's events are not queried. */
        @JvmField
        val NONE: EventQueryBackend = EventQueryBackend { null }
    }
}

/**
 * The aggregate types of [manifest] in [contexts] whose snapshots are queried: the Manifest lists their
 * [snapshot model][QueryRoutes.SNAPSHOT] (decision D128).
 */
internal fun snapshotTypes(manifest: Manifest, contexts: Set<String>): List<AggregateType> =
    queryTypes(manifest, contexts, QueryRoutes.SNAPSHOT)

/** Every aggregate type of [manifest] in [contexts]. */
internal fun hostedTypes(manifest: Manifest, contexts: Set<String>): List<AggregateType> =
    manifest.contexts.filter { it.name in contexts }.flatMap { context ->
        context.aggregates.map { AggregateType(context.name, it.name) }
    }

/** The aggregate types of [manifest] in [contexts] whose [model] the Manifest lists. */
internal fun queryTypes(manifest: Manifest, contexts: Set<String>, model: String): List<AggregateType> =
    manifest.contexts.filter { it.name in contexts }.flatMap { context ->
        context.aggregates.filter { aggregate -> aggregate.queries.any { it.model == model } }
            .map { AggregateType(context.name, it.name) }
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
