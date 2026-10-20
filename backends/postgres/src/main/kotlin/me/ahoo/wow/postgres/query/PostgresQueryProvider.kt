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

package me.ahoo.wow.postgres.query

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.postgres.PostgresStore
import me.ahoo.wow.postgres.PostgresStoreProvider
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.index.QueryIndexDeclaration
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.store.EventQueries
import me.ahoo.wow.query.store.QueryIndexReport
import me.ahoo.wow.query.store.SnapshotQueries
import me.ahoo.wow.query.store.StoreQueryOptions
import me.ahoo.wow.query.store.StoreQueryProvider
import me.ahoo.wow.spi.store.Store
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/**
 * The queries of a PostgreSQL store (review R3.3-1): its snapshot tables (6.2a), with the `x-wow-index` indexes
 * (decision D151), and its log tables in place for the `events` model (decision D235); one storage per context, opened
 * once. Both read through the store's [query DataSource][PostgresStore.queryDataSource] (review R3.4-2); the indexes
 * are created through the store's own. Found by `ServiceLoader`.
 */
internal class PostgresQueryProvider : StoreQueryProvider {
    override val type: String = PostgresStoreProvider.TYPE

    override fun snapshots(store: Store, options: StoreQueryOptions): SnapshotQueries =
        PostgresSnapshotQueries(store as PostgresStore, options)

    override fun events(store: Store, options: StoreQueryOptions): EventQueries {
        val postgres = store as PostgresStore
        val storages = ConcurrentHashMap<String, PostgresEventQueryStorage>()
        return EventQueries { type ->
            storages.computeIfAbsent(type.context) { PostgresEventQueryStorage(postgres.queryDataSource, it) }
                .create(type)
        }
    }

    /**
     * A bound from the query pool's size when the pool says it (HikariCP's `maximumPoolSize`): all of a pool of the
     * queries' own; a quarter of one the appends share (at least [MIN_SHARED]), so the queries never hold most of the
     * connections the commands wait for. [StoreQueryProvider.DEFAULT_MAX_CONCURRENT] for a pool that does not say.
     */
    override fun maxConcurrent(store: Store): Int {
        val postgres = store as PostgresStore
        val size = postgres.queryDataSource.maximumPoolSize() ?: return StoreQueryProvider.DEFAULT_MAX_CONCURRENT
        return if (postgres.queriesShareAppends) maxOf(MIN_SHARED, size / SHARED_FRACTION) else size
    }

    /**
     * The adaptive limit's ceiling (decision D252): half of a pool the appends share (never below [maxConcurrent]), all
     * of one of the queries' own; [maxConcurrent] for a pool that does not say its size.
     */
    override fun maxConcurrentCeiling(store: Store): Int {
        val postgres = store as PostgresStore
        val size = postgres.queryDataSource.maximumPoolSize() ?: return maxConcurrent(store)
        return if (postgres.queriesShareAppends) maxOf(maxConcurrent(store), size / SHARED_CEILING_FRACTION) else size
    }

    companion object {
        /** The share of the appends' pool the adaptive limit rises to at most: one connection in two. */
        const val SHARED_CEILING_FRACTION: Int = 2

        /** The fewest queries a store whose queries share the appends' pool runs at once. */
        const val MIN_SHARED: Int = 2

        /** The share of the appends' pool the queries take: one connection in four. */
        const val SHARED_FRACTION: Int = 4
    }
}

/**
 * The most connections this pool opens, when it says (HikariCP's `getMaximumPoolSize`, read without depending on
 * HikariCP); null for a pool that does not.
 */
internal fun DataSource.maximumPoolSize(): Int? = try {
    (javaClass.getMethod("getMaximumPoolSize").invoke(this) as? Int)?.takeIf { it > 0 }
} catch (ignored: ReflectiveOperationException) {
    null
}

/**
 * How many threads wait now for a connection of this pool, when it says (HikariCP's
 * `getHikariPoolMXBean().getThreadsAwaitingConnection()`, read without depending on HikariCP); null for a pool that
 * does not, or one not started yet.
 */
internal fun DataSource.threadsAwaitingConnection(): Int? = try {
    val pool = javaClass.getMethod("getHikariPoolMXBean").invoke(this)
    pool?.let { publicMethod(it, "getThreadsAwaitingConnection").invoke(it) as? Int }
} catch (ignored: ReflectiveOperationException) {
    null
}

/** [name] of [target], looked up on the public interface that declares it when its class is not public itself. */
private fun publicMethod(target: Any, name: String) =
    (listOf(target.javaClass) + target.javaClass.interfaces).firstNotNullOf { type ->
        runCatching { type.getMethod(name) }.getOrNull()?.takeIf { Modifier.isPublic(type.modifiers) }
    }

private class PostgresSnapshotQueries(private val store: PostgresStore, options: StoreQueryOptions) : SnapshotQueries {
    private val queryOptions = PostgresQueryOptions(indexDdl = options.indexDdl)
    private val storages = ConcurrentHashMap<String, PostgresSnapshotQueryStorage>()

    private fun storage(context: String) = storages.computeIfAbsent(context) {
        PostgresSnapshotQueryStorage(store.queryDataSource, it, queryOptions, ddlDataSource = store.dataSource)
    }

    override fun binding(type: AggregateType): QueryBackendBinding<QueryBackend> = storage(type.context).create(type)

    override val managesIndexes: Boolean get() = true

    // The table is created on an aggregate type's first write; an index needs it now.
    override fun prepare(type: AggregateType) {
        (store.snapshots(type.context) as? PostgresSnapshotStore)?.createTable(type.name)
    }

    override fun manageIndexes(
        type: AggregateType,
        schema: QueryModelSchema,
        declarations: List<QueryIndexDeclaration>,
    ): QueryIndexReport {
        val indexes = PostgresQueryIndexes.plan(type.context, type.name, schema, declarations)
        val report = storage(type.context).manageIndexes(indexes)
        return QueryIndexReport(report.created, report.missing, report.invalid)
    }
}
