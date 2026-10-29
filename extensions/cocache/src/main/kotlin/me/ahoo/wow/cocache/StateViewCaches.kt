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

package me.ahoo.wow.cocache

import kotlinx.coroutines.flow.toList
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.SourceLoadRejectedException
import me.ahoo.cocache.core.SourceLoadTimeoutException
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.coroutines.SourceLoadCancelledException
import me.ahoo.cocache.coroutines.SuspendingCache
import me.ahoo.cocache.coroutines.SuspendingCacheLoader
import me.ahoo.cocache.coroutines.suspending
import me.ahoo.cocache.coroutines.suspendingVersionedLoader
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.dsl.listQuery
import me.ahoo.wow.api.read.Queries
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.api.read.SnapshotQueries
import me.ahoo.wow.api.read.StateView
import me.ahoo.wow.api.read.TenantQueryApi
import me.ahoo.wow.cocache.api.StateKey
import me.ahoo.wow.cocache.api.StateViewCache
import me.ahoo.wow.cocache.api.StateViewUnavailableException
import java.io.IOException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * The state view caches of a service, one CoCache cache per aggregate type, each holding that type's states as the
 * view [StateView] marks, each at the aggregate's version: what a [StateViewCache] reads, and what the owner's
 * [StateViewWriter] writes after each commit.
 *
 * Everything about a cache follows from its view, the same on the owner and on every reader ([specOf]):
 * - its name is `wow.<context>.<aggregate>` ([nameOf]), the aggregate the view's `@StateView` names;
 * - its key is the aggregate's [StateKey], stored as `<tenantId>/<id>`;
 * - its value is the view, stored as its JSON ([StateViewCodec]), at the aggregate's version: a versioned cache, so
 *   a state is written only over an older one, and a deleted aggregate is a tombstone at its version that no older
 *   state undoes;
 * - a miss is read from the owner through the node's typed queries ([queries]): in process when the node serves the
 *   aggregate, else over HTTP as this service, the queries pinned to the key's tenant; a batch read is one query per
 *   tenant, its ids in one `IN`. The fill holds CoCache's lease, which the owner's write takes over: a fill never puts
 *   back a state older than the owner's. An aggregate never created is cached as absent (CoCache's negative cache).
 * - no early refresh (`refreshWindow`): a versioned cache has none, and the owner's writes keep it fresh.
 *
 * Each cache is created once, by [factory], which applies the application's CoCache settings to it when it has
 * CoCache's starter (`cocache.caches.[wow.<context>.<aggregate>].*`).
 */
class StateViewCaches(private val factory: CacheFactory, private val queries: () -> QueryApi) {
    private val caches = ConcurrentHashMap<AggregateType, Typed<*>>()

    /** The cache of the states [viewType] views, created on first use. */
    fun <V : Any> of(viewType: Class<V>): StateViewCache<V> = typed(viewType).reads

    /** The cache of the states [V] views, created on first use. */
    inline fun <reified V : Any> of(): StateViewCache<V> = of(V::class.java)

    /** The writer of the states [viewType] views, each written for [ttl], else for the cache's lifetime. */
    fun <V : Any> writerOf(viewType: Class<V>, ttl: Duration? = null): StateViewWriter<V> =
        StateViewWriter(typed(viewType).cache, ttl)

    /** Creates the caches of [viewTypes] now: CoCache's starter checks its settings against the caches it knows. */
    fun declare(viewTypes: Collection<Class<*>>) {
        viewTypes.forEach { typed(it) }
    }

    private fun <V : Any> typed(viewType: Class<V>): Typed<V> {
        val type = typeOf(viewType)
        val typed = caches.computeIfAbsent(
            type
        ) { Typed(viewType, factory.create(specOf(viewType, queries)).suspending()) }
        check(typed.viewType == viewType) {
            "The state views ${typed.viewType.name} and ${viewType.name} both view $type: a view is of one aggregate."
        }
        @Suppress("UNCHECKED_CAST")
        return typed as Typed<V>
    }

    private class Typed<V : Any>(val viewType: Class<V>, val cache: SuspendingCache<StateKey, V>) {
        val reads: StateViewCache<V> = Reads(cache)
    }

    private class Reads<V : Any>(private val cache: SuspendingCache<StateKey, V>) : StateViewCache<V> {
        override val name: String get() = cache.name

        override suspend fun get(key: StateKey): V? = served(listOf(key)) { cache.get(key) }

        override suspend fun getAll(keys: Collection<StateKey>): Map<StateKey, V> =
            served(keys) { cache.getAll(keys) }

        /**
         * [read], whose failure to reach the states' source is a [StateViewUnavailableException]: the owner's read
         * the loader could not make, CoCache's load that timed out, was refused or cancelled, or its backend's read
         * that failed. A state that does not decode, or any other error, is thrown as it is.
         */
        private inline fun <T> served(keys: Collection<StateKey>, read: () -> T): T = try {
            read()
        } catch (unavailable: StateViewUnavailableException) {
            throw unavailable
        } catch (source: SourceLoadTimeoutException) {
            throw unavailable(keys, "its owner did not answer in time", source)
        } catch (source: SourceLoadRejectedException) {
            throw unavailable(keys, "too many reads of its owner are in flight", source)
        } catch (source: SourceLoadCancelledException) {
            throw unavailable(keys, "the read of its owner was cancelled", source)
        } catch (backend: CacheReadException) {
            if (backend.cause is ForeignFormatException) throw backend
            throw unavailable(keys, "the cache's backend failed", backend)
        }

        private fun unavailable(keys: Collection<StateKey>, reason: String, cause: Throwable) =
            StateViewUnavailableException(keys, "The states $keys in $name cannot be served: $reason.", cause)

        override fun toString(): String = "StateViewCache($name)"
    }

    /** What creates a CoCache cache of a spec: the runtime itself, or CoCache's starter, which applies its settings. */
    interface CacheFactory {
        fun <K : Any, V : Any> create(spec: CacheSpec<K, V>): Cache<K, V>

        companion object {
            /** The caches of [runtime], as the spec declares them. */
            fun of(runtime: CacheRuntime): CacheFactory = object : CacheFactory {
                override fun <K : Any, V : Any> create(spec: CacheSpec<K, V>): Cache<K, V> = runtime.create(spec)
            }
        }
    }

    companion object {
        /** The cache of the states of [type]: `wow.<context>.<aggregate>`. */
        fun nameOf(type: AggregateType): String = "wow.$type"

        /** The aggregate type [viewType] views, by its `@StateView`. */
        fun typeOf(viewType: Class<*>): AggregateType = requireNotNull(StateView.typeOf(viewType)) {
            "${viewType.name} is no state view: a StateViewCache reads a generated <State>View, marked @StateView."
        }

        /** The spec of the cache of the states [viewType] views, misses read through [queries]. */
        fun <V : Any> specOf(viewType: Class<V>, queries: () -> QueryApi): CacheSpec<StateKey, V> =
            cacheSpec(nameOf(typeOf(viewType))) {
                keyEncoder = KeyEncoder { it.toString() }
                codec = StateViewCodec(viewType)
                suspendingVersionedLoader(StateViewLoader(viewType, queries))
            }
    }
}

/**
 * Reads the states of a cache's misses from the aggregate's owner, each at its version: [load] its state read,
 * [loadAll] one list query per tenant and [MAX_BATCH] ids, its ids in one `IN`, deleted aggregates included (a snapshot
 * query reads active ones alone unless asked). A deleted aggregate is no view at its version (a tombstone, which no
 * older version a writer replays resurrects), one never created `null` (a negative cache).
 *
 * An owner it cannot read is a [StateViewUnavailableException]: the node has no route to it (it neither serves the
 * aggregate nor reaches it as a peer), the owner is unreachable (an I/O failure), or it answers a problem a retry may
 * get past (a 5xx, a 429). Any other failure, a refusal or an error, is thrown as it is.
 */
internal class StateViewLoader<V : Any>(
    private val viewType: Class<V>,
    private val queries: () -> QueryApi,
) : SuspendingCacheLoader<StateKey, Versioned<V?>> {
    override suspend fun load(key: StateKey): Versioned<V?>? = owned(listOf(key)) {
        snapshots(key.tenantId, listOf(key)).state(key.id)?.let { versionedOf(it.state, it.version, it.deleted) }
    }

    override suspend fun loadAll(keys: Collection<StateKey>): Map<StateKey, Versioned<V?>> = owned(keys) {
        keys.groupBy { it.tenantId }.flatMap { (tenantId, ofTenant) ->
            ofTenant.map { it.id }.distinct().chunked(MAX_BATCH).flatMap { ids ->
                val query = listQuery {
                    filter {
                        ids(ids)
                        deletion(DeletionState.ALL)
                    }
                    limit(ids.size)
                }
                snapshots(tenantId, ofTenant).list(query).toList().map { row ->
                    StateKey(tenantId, row.ref.id) to versionedOf(row.state, row.version, row.deleted)
                }
            }
        }.toMap()
    }

    /** [read] of the owner, a failure to reach it a [StateViewUnavailableException] naming [keys]. */
    private inline fun <T> owned(keys: Collection<StateKey>, read: () -> T): T = try {
        read()
    } catch (unreachable: IOException) {
        throw unavailable(keys, "its owner is unreachable", unreachable)
    } catch (problem: WowProblemException) {
        if (!problem.retryable && problem.status < SERVER_ERROR) throw problem
        throw unavailable(keys, "its owner answered ${problem.status} ${problem.code}", problem)
    }

    private fun versionedOf(view: V, version: Int, deleted: Boolean): Versioned<V?> =
        Versioned(view.takeUnless { deleted }, version.toLong())

    /**
     * The snapshots of the views, as this service reads them for a commit of [tenantId]: a
     * [StateViewUnavailableException] naming [keys] when the node has no route to their owner, whose type no model of
     * the node nor any peer it reaches has.
     */
    private fun snapshots(tenantId: String, keys: Collection<StateKey>): SnapshotQueries<V> = try {
        queriesOf(tenantId).snapshots(viewType)
    } catch (noRoute: IllegalArgumentException) {
        throw unavailable(keys, "the node has no route to their owner", noRoute)
    }

    private fun unavailable(keys: Collection<StateKey>, reason: String, cause: Throwable) =
        StateViewUnavailableException(keys, "The states $keys of ${viewType.name} cannot be read: $reason.", cause)

    private fun queriesOf(tenantId: String): Queries = when (val api = queries()) {
        is TenantQueryApi -> api.tenant(tenantId)
        else -> api.of(Caller.of(tenantId = tenantId))
    }

    override fun toString(): String = "StateViewLoader(${viewType.name})"

    companion object {
        /**
         * The most ids of one list query: 1000, the default query budget's bound on the values of an `IN` and on the
         * rows of a list. An owner whose budget is configured lower refuses a larger batch as `wow.query.invalid`.
         */
        const val MAX_BATCH: Int = 1000

        /** The first status of a server's error, which a retry may get past. */
        private const val SERVER_ERROR = 500
    }
}
