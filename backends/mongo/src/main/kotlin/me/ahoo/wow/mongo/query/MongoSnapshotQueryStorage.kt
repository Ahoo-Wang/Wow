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
import com.mongodb.client.MongoClient
import kotlinx.coroutines.CoroutineDispatcher
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore
import me.ahoo.wow.query.AbstractQueryBackendFactory
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.spi.VirtualThreads

/**
 * The snapshot query storage of one bounded context on MongoDB: the [MongoSnapshotQueryBackend] of each of its
 * aggregate types, over that aggregate's own snapshot collection, which `MongoSnapshotStore` writes (decision D161),
 * with the facts [MongoQuerySchemaAdapter] reads from the collection; and the management of its query indexes
 * ([manageIndexes], decision D151). [readPreference] moves the queries off the primary (`secondaryPreferred`, say;
 * review R3.4-2), at the cost of reading snapshots a little behind the ones a command's default wait waited for; the
 * primary's by default. The indexes are managed on the primary.
 */
@InternalWowApi
class MongoSnapshotQueryStorage @JvmOverloads constructor(
    client: MongoClient,
    databaseName: String,
    val context: String,
    val options: MongoQueryOptions = MongoQueryOptions(),
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
    private val readPreference: ReadPreference? = null,
) : AbstractQueryBackendFactory<MongoSnapshotQueryBackend>() {
    private val database = client.getDatabase(databaseName)

    /** The snapshot collection of [aggregateName]. */
    fun collection(aggregateName: String): String = MongoSnapshotStore.collectionName(context, aggregateName)

    override fun createBinding(aggregateType: AggregateType): QueryBackendBinding<MongoSnapshotQueryBackend> {
        require(aggregateType.context == context) { "$aggregateType is not in context $context." }
        val collection = collection(aggregateType.name)
        return QueryBackendBinding(
            MongoSnapshotQueryBackend(
                aggregateType,
                database.getCollection(collection).let { read -> readPreference?.let(read::withReadPreference) ?: read },
                options,
                dispatcher,
            ),
            MongoQuerySchemaAdapter(database, collection, dispatcher),
        )
    }

    /**
     * Manages [indexes] (planned by [MongoQueryIndexes.plan] for aggregates of this context) as
     * [MongoQueryOptions.indexDdl] says. Blocking: a host calls it once at startup. `auto` makes sure of a missing
     * collection as the snapshot store does (its format record and key) before it indexes it.
     */
    fun manageIndexes(indexes: List<MongoQueryIndex>): MongoQueryIndexReport {
        indexes.forEach {
            require(
                it.context == context
            ) { "${it.collection} is not a snapshot collection of context $context." }
        }
        return MongoQueryIndexes.manage(database, options.indexDdl, indexes) { index ->
            MongoSnapshotStore.ensure(database, context, index.aggregate)
        }
    }
}
