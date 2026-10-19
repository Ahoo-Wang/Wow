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

package me.ahoo.wow.mongo.query

import com.mongodb.ReadPreference
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.mongo.MongoStore
import me.ahoo.wow.mongo.MongoStoreProvider
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
 * The queries of a MongoDB store (review R3.3-1): its snapshot collections (6.2b), with the `x-wow-index` indexes
 * (decision D151), and its log collections in place for the `events` model (decision D235), read from the replica
 * [StoreQueryOptions.eventsReadPreference] names, and the snapshot queries from the one
 * [StoreQueryOptions.snapshotsReadPreference] names (review R3.4-2), each the primary when not set; one storage per
 * context, opened once. Found by `ServiceLoader`.
 */
internal class MongoQueryProvider : StoreQueryProvider {
    override val type: String = MongoStoreProvider.TYPE

    override fun snapshots(store: Store, options: StoreQueryOptions): SnapshotQueries =
        MongoSnapshotQueries(store as MongoStore, options)

    override fun events(store: Store, options: StoreQueryOptions): EventQueries {
        val mongo = store as MongoStore
        val preference = options.eventsReadPreference?.let(ReadPreference::valueOf)
        val storages = ConcurrentHashMap<String, MongoEventQueryStorage>()
        return EventQueries { type ->
            storages.computeIfAbsent(type.context) {
                MongoEventQueryStorage(mongo.client, mongo.database, it, readPreference = preference)
            }.create(type)
        }
    }
}

private class MongoSnapshotQueries(private val store: MongoStore, options: StoreQueryOptions) : SnapshotQueries {
    private val queryOptions = MongoQueryOptions(indexDdl = options.indexDdl)
    private val readPreference = options.snapshotsReadPreference?.let(ReadPreference::valueOf)
    private val storages = ConcurrentHashMap<String, MongoSnapshotQueryStorage>()

    private fun storage(context: String) = storages.computeIfAbsent(context) {
        MongoSnapshotQueryStorage(store.client, store.database, it, queryOptions, readPreference = readPreference)
    }

    override fun binding(type: AggregateType): QueryBackendBinding<QueryBackend> = storage(type.context).create(type)

    override val managesIndexes: Boolean get() = true

    override fun manageIndexes(
        type: AggregateType,
        schema: QueryModelSchema,
        declarations: List<QueryIndexDeclaration>,
    ): QueryIndexReport {
        // The scope indexes come first, then the declared ones; auto makes sure of the collection.
        val indexes = MongoQueryIndexes.plan(type.context, type.name, schema, declarations)
        val report = storage(type.context).manageIndexes(indexes)
        return QueryIndexReport(report.created, report.missing, report.invalid)
    }
}
