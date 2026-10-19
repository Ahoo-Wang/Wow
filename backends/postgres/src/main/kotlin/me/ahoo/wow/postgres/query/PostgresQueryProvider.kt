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
import java.util.concurrent.ConcurrentHashMap

/**
 * The queries of a PostgreSQL store (review R3.3-1): its snapshot tables (6.2a), with the `x-wow-index` indexes
 * (decision D151), and its log tables in place for the `events` model (decision D235); one storage per context, opened
 * once. Found by `ServiceLoader`.
 */
internal class PostgresQueryProvider : StoreQueryProvider {
    override val type: String = PostgresStoreProvider.TYPE

    override fun snapshots(store: Store, options: StoreQueryOptions): SnapshotQueries =
        PostgresSnapshotQueries(store as PostgresStore, options)

    override fun events(store: Store, options: StoreQueryOptions): EventQueries {
        val postgres = store as PostgresStore
        val storages = ConcurrentHashMap<String, PostgresEventQueryStorage>()
        return EventQueries { type ->
            storages.computeIfAbsent(type.context) { PostgresEventQueryStorage(postgres.dataSource, it) }.create(type)
        }
    }
}

private class PostgresSnapshotQueries(private val store: PostgresStore, options: StoreQueryOptions) : SnapshotQueries {
    private val queryOptions = PostgresQueryOptions(indexDdl = options.indexDdl)
    private val storages = ConcurrentHashMap<String, PostgresSnapshotQueryStorage>()

    private fun storage(context: String) = storages.computeIfAbsent(context) {
        PostgresSnapshotQueryStorage(store.dataSource, it, queryOptions)
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
