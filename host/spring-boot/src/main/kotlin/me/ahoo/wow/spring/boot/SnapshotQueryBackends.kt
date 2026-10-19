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

import com.mongodb.ReadPreference
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.mongo.query.MongoEventQueryStorage
import me.ahoo.wow.mongo.query.MongoQueryIndexes
import me.ahoo.wow.mongo.query.MongoQueryOptions
import me.ahoo.wow.mongo.query.MongoSnapshotQueryStorage
import me.ahoo.wow.postgres.query.PostgresEventQueryStorage
import me.ahoo.wow.postgres.query.PostgresQueryIndexes
import me.ahoo.wow.postgres.query.PostgresQueryOptions
import me.ahoo.wow.postgres.query.PostgresSnapshotQueryStorage
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.QueryBackendFactory
import me.ahoo.wow.query.index.QueryIndexDdl
import java.util.concurrent.ConcurrentHashMap

/**
 * The PostgreSQL snapshot query backend (6.2a) of one [store], one storage per context over its snapshot tables; loaded
 * only when wow-postgres is on the classpath.
 */
internal object PostgresQueries {
    fun open(store: HostStore, ddl: QueryIndexDdl): SnapshotQueryBackend {
        val postgres = store as PostgresStore
        val storages = ConcurrentHashMap<String, PostgresSnapshotQueryStorage>()
        fun storage(context: String) = storages.computeIfAbsent(context) {
            PostgresSnapshotQueryStorage(postgres.dataSource, it, PostgresQueryOptions(indexDdl = ddl))
        }
        return SnapshotQueryBackend(
            factory = QueryBackendFactory { type -> storage(type.context).create(type) },
            // The table is created on an aggregate type's first write; an index needs it now.
            prepare = { type -> (postgres.snapshots(type.context) as? PostgresSnapshotStore)?.createTable(type.name) },
            indexes = { type, schema, declarations ->
                val indexes = PostgresQueryIndexes.plan(type.context, type.name, schema, declarations)
                val report = storage(type.context).manageIndexes(indexes)
                QueryIndexReport(report.created, report.missing, report.invalid)
            },
        )
    }
}

/**
 * The MongoDB snapshot query backend (6.2b) of one [store], one storage per context over the snapshot collections of
 * its database; loaded only when wow-mongo is on the classpath.
 */
internal object MongoQueries {
    fun open(store: HostStore, ddl: QueryIndexDdl): SnapshotQueryBackend {
        val mongo = store as MongoStore
        val storages = ConcurrentHashMap<String, MongoSnapshotQueryStorage>()
        fun storage(context: String) = storages.computeIfAbsent(context) {
            MongoSnapshotQueryStorage(mongo.client, mongo.database, it, MongoQueryOptions(indexDdl = ddl))
        }
        return SnapshotQueryBackend(
            factory = QueryBackendFactory { type -> storage(type.context).create(type) },
            indexes = { type, schema, declarations ->
                // The scope indexes come first, then the declared ones; auto makes sure of the collection.
                val indexes = MongoQueryIndexes.plan(type.context, type.name, schema, declarations)
                val report = storage(type.context).manageIndexes(indexes)
                QueryIndexReport(report.created, report.missing, report.invalid)
            },
        )
    }
}

/**
 * The in-memory reference query backend of a store in memory (decision D209), when `wow.query.memory.enabled` gave it
 * one ([MemoryStore.queries]): its snapshot queries read what its snapshot stores wrote. Null without it.
 */
internal object MemoryQueries {
    fun open(store: HostStore): SnapshotQueryBackend? =
        (store as MemoryStore).queries?.let { SnapshotQueryBackend(factory = it) }
}

/**
 * The `events` query backends of the log stores (decision D235), one per aggregate type, by the store its log is on
 * (decision D177): PostgreSQL's and MongoDB's read the log tables and collections in place; any other log (in memory,
 * or the application's own) is scanned in memory ([me.ahoo.wow.query.memory.InMemoryEventQueryStorage.of]). Each
 * storage is opened once per store and context, and loaded only when its module is on the classpath.
 */
internal object EventQueryBackends {
    fun postgres(store: HostStore): (AggregateType) -> QueryBackendBinding<QueryBackend> {
        val postgres = store as PostgresStore
        val storages = ConcurrentHashMap<String, PostgresEventQueryStorage>()
        return { type ->
            storages.computeIfAbsent(type.context) {
                PostgresEventQueryStorage(postgres.dataSource, it)
            }.create(type)
        }
    }

    fun mongo(store: HostStore, readPreference: String?): (AggregateType) -> QueryBackendBinding<QueryBackend> {
        val mongo = store as MongoStore
        val preference = readPreference?.let(ReadPreference::valueOf)
        val storages = ConcurrentHashMap<String, MongoEventQueryStorage>()
        return { type ->
            storages.computeIfAbsent(type.context) {
                MongoEventQueryStorage(mongo.client, mongo.database, it, readPreference = preference)
            }.create(type)
        }
    }
}
