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

import com.mongodb.client.MongoClient
import me.ahoo.wow.mongo.query.MongoQueryIndexes
import me.ahoo.wow.mongo.query.MongoQueryOptions
import me.ahoo.wow.mongo.query.MongoSnapshotQueryStorage
import me.ahoo.wow.postgres.query.PostgresQueryIndexes
import me.ahoo.wow.postgres.query.PostgresQueryOptions
import me.ahoo.wow.postgres.query.PostgresSnapshotQueryStorage
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.query.QueryBackendFactory
import me.ahoo.wow.query.index.QueryIndexDdl
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/**
 * The PostgreSQL snapshot query backend (6.2a), one storage per context over the snapshot tables of
 * [backends]' stores; loaded only when wow-postgres is on the classpath.
 */
internal object PostgresQueries {
    fun open(dataSource: Any, backends: HostBackends, ddl: QueryIndexDdl): SnapshotQueryBackend {
        val source = dataSource as DataSource
        val storages = ConcurrentHashMap<String, PostgresSnapshotQueryStorage>()
        fun storage(context: String) = storages.computeIfAbsent(context) {
            PostgresSnapshotQueryStorage(source, it, PostgresQueryOptions(indexDdl = ddl))
        }
        return SnapshotQueryBackend(
            factory = QueryBackendFactory { type -> storage(type.context).create(type) },
            // The table is created on an aggregate type's first write; an index needs it now.
            prepare = { type ->
                val store = backends.contexts[type.context]?.snapshots as? PostgresSnapshotStore
                store?.createTable(type.name)
            },
            indexes = { type, schema, declarations ->
                val indexes = PostgresQueryIndexes.plan(type.context, type.name, schema, declarations)
                val report = storage(type.context).manageIndexes(indexes)
                QueryIndexReport(report.created, report.missing, report.invalid)
            },
        )
    }
}

/**
 * The MongoDB snapshot query backend (6.2b), one storage per context over the snapshot collections of [database];
 * loaded only when wow-mongo is on the classpath.
 */
internal object MongoQueries {
    fun open(client: Any, database: String, ddl: QueryIndexDdl): SnapshotQueryBackend {
        val mongo = client as MongoClient
        val storages = ConcurrentHashMap<String, MongoSnapshotQueryStorage>()
        fun storage(context: String) = storages.computeIfAbsent(context) {
            MongoSnapshotQueryStorage(mongo, database, it, MongoQueryOptions(indexDdl = ddl))
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
