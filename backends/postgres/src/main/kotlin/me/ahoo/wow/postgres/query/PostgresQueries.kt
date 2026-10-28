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
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendBinding
import me.ahoo.wow.query.backend.QueryBindings
import me.ahoo.wow.query.index.QueryIndexPlan
import me.ahoo.wow.query.store.QueryIndexReport
import me.ahoo.wow.query.store.SnapshotQueries
import me.ahoo.wow.query.store.StoreQueries
import me.ahoo.wow.query.store.StoreQueryOptions
import me.ahoo.wow.spi.store.StorePool
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/**
 * The queries of a PostgreSQL store, its [StoreQueries] facet: its snapshot tables, with the `x-wow-index` indexes,
 * and its log tables in place for the `events` model; one storage per context, opened once. Both read through the
 * store's [query DataSource][PostgresStore.queryDataSource]; the indexes are created through the store's own.
 */
internal class PostgresQueries(private val store: PostgresStore) : StoreQueries {
    override fun snapshots(options: StoreQueryOptions): SnapshotQueries = PostgresSnapshotQueries(store, options)

    override fun events(options: StoreQueryOptions): QueryBindings<AggregateType> {
        val storages = ConcurrentHashMap<String, PostgresEventQueryStorage>()
        return QueryBindings { type ->
            storages.computeIfAbsent(type.context) { PostgresEventQueryStorage(store.queryDataSource, it) }
                .binding(type)
        }
    }
}

/**
 * The pools of a PostgreSQL store, its [StorePool] facet, as HikariCP says them: the size of the pool the queries draw
 * from (`maximumPoolSize`), whether it is the appends' [dataSource], and the threads awaiting a connection of the
 * appends' pool; null where the pool is not HikariCP's.
 */
internal class PostgresPool(private val dataSource: DataSource, private val queryDataSource: DataSource) : StorePool {
    override val queryConnections: Int? get() = queryDataSource.maximumPoolSize()

    override val sharedWithAppends: Boolean get() = queryDataSource === dataSource

    override fun waitingForConnection(): Int? = dataSource.threadsAwaitingConnection()
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
private fun publicMethod(target: Any, name: String): Method =
    (listOf(target.javaClass) + target.javaClass.interfaces).firstNotNullOfOrNull { type ->
        type.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.takeIf { Modifier.isPublic(type.modifiers) }
    } ?: throw NoSuchMethodException(name)

private class PostgresSnapshotQueries(private val store: PostgresStore, options: StoreQueryOptions) : SnapshotQueries {
    private val queryOptions = PostgresQueryOptions(indexDdl = options.indexDdl)
    private val storages = ConcurrentHashMap<String, PostgresSnapshotQueryStorage>()

    private fun storage(context: String) = storages.computeIfAbsent(context) {
        PostgresSnapshotQueryStorage(store.queryDataSource, it, queryOptions, ddlDataSource = store.dataSource)
    }

    override fun binding(key: AggregateType): QueryBackendBinding<QueryBackend>? = storage(key.context).binding(key)

    override val managesIndexes: Boolean get() = true

    // The table is created on an aggregate type's first write; an index needs it now.
    override fun prepare(type: AggregateType) {
        (store.snapshots(type.context) as? PostgresSnapshotStore)?.createTable(type.name)
    }

    override fun manageIndexes(type: AggregateType, plan: QueryIndexPlan): QueryIndexReport {
        val indexes = PostgresQueryIndexes.plan(type.context, type.name, plan)
        val report = storage(type.context).manageIndexes(indexes)
        return QueryIndexReport(report.created, report.missing, report.invalid)
    }
}
