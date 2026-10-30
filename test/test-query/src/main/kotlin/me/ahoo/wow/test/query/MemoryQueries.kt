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

package me.ahoo.wow.test.query

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.read.Queries
import me.ahoo.wow.api.read.Snapshot
import me.ahoo.wow.api.read.SubscriptionQueries
import me.ahoo.wow.api.read.TenantQueryApi
import me.ahoo.wow.api.read.TrustedQueries
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.MissingDependencyException
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.ManifestMerger
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.query.engine.CommittedStates
import me.ahoo.wow.query.engine.QueryEngine
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.read.InProcessQueryApi
import me.ahoo.wow.query.read.ReadModels
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.test.ProcessorSpec
import tools.jackson.databind.JsonNode
import java.util.concurrent.ConcurrentHashMap

/**
 * A real read side for a processor spec ([ProcessorSpec.givenQueries]): the query engine of the Manifest the modules on
 * the class path declare, over snapshots held in memory. A handler's reads run as on a node: the filters, the scope and
 * tenant policies and the budgets apply, and the snapshots decode as the aggregates' state classes.
 *
 * The Manifest and the state classes come from the modules' binding index ([ModelProvider]): each module's fragment,
 * merged, and each state class the Manifest names by its binary name (`#/$defs/<binary name>`, as the compiler writes
 * it), so a state is read by its class whatever the model takes, the node's `QueryApi` or any other service. A state a
 * hand-written fragment names otherwise comes from the module's models, built with the [dependencies] given, else from
 * the states given to it ([snapshot]).
 *
 * ```kotlin
 * sagaSpec(handlers) {
 *     givenQueries(memoryQueries { snapshots(Stock.TYPE, "mug" to Stock(7), "cup" to Stock(0)) })
 *     whenEvent(...) { ... }
 * }
 * ```
 *
 * Only the snapshot queries are served, with the state reads by id of the snapshots given; the events and the
 * datasets are not. A spec builds one with [memoryQueries] or [of].
 */
class MemoryQueries @InternalWowApi constructor(
    private val manifest: Manifest,
    stateTypes: Map<AggregateType, Class<*>> = emptyMap(),
    private val policies: List<QueryPolicy> = emptyList(),
    private val filters: List<QueryFilter> = emptyList(),
) : TenantQueryApi {
    private val storage = InMemoryQueryStorage()
    private val committed = ConcurrentHashMap<AggregateRef, Snapshot<JsonNode>>()
    private val stateTypes = ConcurrentHashMap(stateTypes)
    private val states = CommittedStates { ref -> committed[ref] }

    private val engine: QueryEngine by lazy {
        QueryEngine.Builder(manifest)
            .snapshots(storage) { aggregateOf(it) != null }
            .policies(policies)
            .filters(filters)
            .committedStates(states)
            .build()
    }

    /** The typed API over [engine], rebuilt when a state gives a type its first state class. */
    @Volatile
    private var api: InProcessQueryApi? = null

    private fun api(): InProcessQueryApi = api ?: InProcessQueryApi(
        engine,
        ReadModels(stateTypes.map { (type, stateType) -> ReadModels.Model(type, stateType) }),
        latest = states,
    ).also { api = it }

    private fun aggregateOf(type: AggregateType): AggregateManifest? =
        manifest.contexts.firstOrNull { it.name == type.context }?.aggregates?.firstOrNull { it.name == type.name }

    /**
     * Holds [state] as the latest snapshot of the aggregate [id] of [type] in [tenantId]: what the snapshot queries
     * and a state read by id of it return. [owner] is its scope, the owner and space the scope policies check.
     */
    @Suppress("LongParameterList") // One parameter per field of the snapshot a spec may set.
    fun snapshot(
        type: AggregateType,
        id: String,
        state: Any,
        tenantId: String = Caller.DEFAULT_TENANT,
        owner: Scope = Scope.NONE,
        version: Int = 1,
        tags: Map<String, List<String>> = emptyMap(),
        deleted: Boolean = false,
    ): MemoryQueries = apply {
        val aggregate = requireNotNull(aggregateOf(type)) {
            "No module on the class path declares the aggregate $type: its Manifest fragment is not found " +
                "(META-INF/wow/manifest)."
        }
        if (stateTypes.putIfAbsent(type, state.javaClass) == null) {
            api = null
        }
        val ref = type.ref(tenantId, id)
        val tree: JsonNode = WowJson.mapper.valueToTree(state)
        storage.save(
            SnapshotRecord(
                ref = ref,
                version = version,
                schemaFingerprint = aggregate.state.fingerprint ?: NO_FINGERPRINT,
                state = WowJson.mapper.writeValueAsString(tree),
                scope = owner,
                deleted = deleted,
                firstEventTime = 0,
                lastEventTime = 0,
                tags = tags,
            ),
        )
        committed[ref] = Snapshot.of(ref, version, owner, tags, deleted, null, 0, 0, tree)
    }

    /** Holds each of [states], an aggregate id to its state, as a snapshot of [type] in [tenantId]; see [snapshot]. */
    fun snapshots(
        type: AggregateType,
        vararg states: Pair<String, Any>,
        tenantId: String = Caller.DEFAULT_TENANT,
    ): MemoryQueries = apply {
        states.forEach { (id, state) -> snapshot(type, id, state, tenantId) }
    }

    override fun of(caller: Caller): Queries = api().of(caller)

    override fun trusted(): TrustedQueries = api().trusted()

    /** A handler's queries of [tenantId], pinned to it as on a node ([InProcessQueryApi.tenant]). */
    override fun tenant(tenantId: String): SubscriptionQueries = api().tenant(tenantId)

    override fun toString(): String = "MemoryQueries(${stateTypes.keys})"

    companion object {
        private const val NO_FINGERPRINT = "memory"

        /**
         * The read side of the modules on [classLoader]'s class path: their merged Manifest, the state classes it names
         * by their binary names, and those of the models [dependencies] build.
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
            dependencies: Dependencies = Dependencies.NONE,
            policies: List<QueryPolicy> = emptyList(),
            filters: List<QueryFilter> = emptyList(),
        ): MemoryQueries {
            val providers = ModelProvider.load(classLoader)
            val fragments = providers.map { it.fragment }.distinct().map { fragment(classLoader, it) }
            val manifest = ManifestMerger.merge(fragments)
            val built = providers.flatMap { provider ->
                try {
                    provider.models(dependencies)
                } catch (_: MissingDependencyException) {
                    // Its types take their state classes from the Manifest, else from the states a spec gives them.
                    emptyList()
                }
            }.associate { it.type to it.stateType }
            return MemoryQueries(manifest, declared(manifest, classLoader) + built, policies, filters)
        }

        /** The state classes [manifest] names by their binary names that [classLoader] has, by aggregate type. */
        private fun declared(manifest: Manifest, classLoader: ClassLoader): Map<AggregateType, Class<*>> =
            manifest.contexts.flatMap { context ->
                context.aggregates.mapNotNull { aggregate ->
                    val name = Manifest.schemaKey(aggregate.state.schema)
                    runCatching { Class.forName(name, false, classLoader) }.getOrNull()
                        ?.let { AggregateType(context.name, aggregate.name) to it }
                }
            }.toMap()

        private fun fragment(classLoader: ClassLoader, source: String): ManifestFragment {
            val bytes = checkNotNull(classLoader.getResourceAsStream(source)?.use { it.readBytes() }) {
                "The Manifest fragment $source is not on the class path."
            }
            return ManifestFragment(source, ManifestTree.read(WowJson.mapper.readValue(bytes, Map::class.java)))
        }
    }
}

/**
 * The read side of the modules on the class path ([MemoryQueries.of]), with the snapshots [block] gives:
 * `memoryQueries { snapshots(Stock.TYPE, "mug" to Stock(7)) }`.
 */
fun memoryQueries(
    dependencies: Dependencies = Dependencies.NONE,
    policies: List<QueryPolicy> = emptyList(),
    filters: List<QueryFilter> = emptyList(),
    block: MemoryQueries.() -> Unit = {},
): MemoryQueries = MemoryQueries.of(dependencies = dependencies, policies = policies, filters = filters).apply(block)

/**
 * Serves the handlers' reads from a [memoryQueries] holding [states], each an aggregate id to its state, as snapshots of
 * [type] in [tenantId]. It replaces the queries given before: a spec that reads several types gives one
 * [memoryQueries].
 */
fun ProcessorSpec.givenSnapshots(
    type: AggregateType,
    vararg states: Pair<String, Any>,
    tenantId: String = Caller.DEFAULT_TENANT,
): MemoryQueries = memoryQueries { snapshots(type, *states, tenantId = tenantId) }.also(::givenQueries)
