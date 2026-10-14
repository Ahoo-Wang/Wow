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
import com.mongodb.client.MongoClients
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import me.ahoo.wow.tck.containers.WowContainers
import javax.sql.DataSource

/** The databases the snapshot rebuild scenarios run on: the shared PostgreSQL and MongoDB containers. */
private object RebuildDatabases {
    val dataSource: DataSource by lazy {
        val postgres = WowContainers.postgres
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = POOL_SIZE
            },
        )
    }

    val client: MongoClient by lazy { MongoClients.create(WowContainers.mongo.replicaSetUrl) }

    fun postgres(name: String): HostStore = PostgresStore(name, dataSource)

    fun mongo(name: String): HostStore = MongoStore(name, client, DATABASE)

    private const val POOL_SIZE = 16
    private const val DATABASE = "wow_rebuild"
}

/**
 * [SnapshotRebuildSpec] with the log and the first snapshot target on PostgreSQL, and the target added beside it on
 * MongoDB: a snapshot store of another type backfilled from a PostgreSQL log.
 */
class PostgresSnapshotRebuildTest : SnapshotRebuildSpec() {
    override fun store(name: String): HostStore =
        if (name == A) RebuildDatabases.postgres(name) else RebuildDatabases.mongo(name)
}

/**
 * [SnapshotRebuildSpec] with the log and the first snapshot target on MongoDB, and the target added beside it on
 * PostgreSQL.
 */
class MongoSnapshotRebuildTest : SnapshotRebuildSpec() {
    override fun store(name: String): HostStore =
        if (name == A) RebuildDatabases.mongo(name) else RebuildDatabases.postgres(name)
}
