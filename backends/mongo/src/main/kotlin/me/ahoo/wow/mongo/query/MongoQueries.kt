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
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendBinding
import me.ahoo.wow.query.index.QueryIndexPlan
import me.ahoo.wow.query.store.EventQueries
import me.ahoo.wow.query.store.QueryIndexReport
import me.ahoo.wow.query.store.SnapshotQueries
import me.ahoo.wow.query.store.StoreQueries
import me.ahoo.wow.query.store.StoreQueryOptions
import java.util.concurrent.ConcurrentHashMap

/**
 * The queries of a MongoDB store, its [StoreQueries] facet: its snapshot collections, with the `x-wow-index` indexes,
 * and its log collections in place for the `events` model, each read from the replica the store's
 * `query-read-preference` option names ([MongoStore.queryReadPreference]), the primary when not set; one storage per
 * context, opened once.
 */
internal class MongoQueries(private val store: MongoStore) : StoreQueries {
    override fun snapshots(options: StoreQueryOptions): SnapshotQueries = MongoSnapshotQueries(store, options)

    override fun events(options: StoreQueryOptions): EventQueries = MongoEventQueries(store, options)
}

/**
 * The `events` queries of a MongoDB store: each context's log collections in place, read from the store's query
 * replica, and the indexes of their access attributes ([MongoQueryIndexes.planLog]).
 */
private class MongoEventQueries(private val store: MongoStore, options: StoreQueryOptions) : EventQueries {
    private val indexDdl = options.indexDdl
    private val preference = store.queryReadPreference?.let(ReadPreference::valueOf)
    private val storages = ConcurrentHashMap<String, MongoEventQueryStorage>()

    override fun binding(key: AggregateType): QueryBackendBinding<QueryBackend>? =
        storages.computeIfAbsent(key.context) {
            MongoEventQueryStorage(
                store.client,
                store.database,
                it,
                readPreference = preference,
                partitions = { store.openedLog(key.context)?.partitions },
            )
        }.binding(key)

    override val managesIndexes: Boolean get() = true

    // The collection is made sure of on an aggregate type's first append; an index needs it now.
    override fun prepare(type: AggregateType) {
        logOf(type)?.createCollection(type.name)
    }

    override fun manageIndexes(type: AggregateType, plan: QueryIndexPlan): QueryIndexReport {
        val database = store.client.getDatabase(store.database)
        val indexes = MongoQueryIndexes.planLog(type.context, type.name, plan)
        val report = MongoQueryIndexes.manage(database, indexDdl, indexes) { logOf(type)?.createCollection(type.name) }
        return QueryIndexReport(report.created, report.missing, report.invalid)
    }

    private fun logOf(type: AggregateType): MongoEventLog? = store.openedLog(type.context) as? MongoEventLog
}

private class MongoSnapshotQueries(private val store: MongoStore, options: StoreQueryOptions) : SnapshotQueries {
    private val queryOptions = MongoQueryOptions(indexDdl = options.indexDdl)
    private val readPreference = store.queryReadPreference?.let(ReadPreference::valueOf)
    private val storages = ConcurrentHashMap<String, MongoSnapshotQueryStorage>()

    private fun storage(context: String) = storages.computeIfAbsent(context) {
        MongoSnapshotQueryStorage(store.client, store.database, it, queryOptions, readPreference = readPreference)
    }

    override fun binding(key: AggregateType): QueryBackendBinding<QueryBackend>? = storage(key.context).binding(key)

    override val managesIndexes: Boolean get() = true

    override fun manageIndexes(type: AggregateType, plan: QueryIndexPlan): QueryIndexReport {
        // The scope indexes come first, then the declared ones; auto makes sure of the collection.
        val indexes = MongoQueryIndexes.plan(type.context, type.name, plan)
        val report = storage(type.context).manageIndexes(indexes)
        return QueryIndexReport(report.created, report.missing, report.invalid)
    }
}
