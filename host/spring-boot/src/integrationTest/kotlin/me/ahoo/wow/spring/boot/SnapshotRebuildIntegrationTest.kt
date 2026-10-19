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
import me.ahoo.wow.runtime.store.BackendSelection
import me.ahoo.wow.runtime.store.StoreLayout
import me.ahoo.wow.runtime.store.StoreTypes
import me.ahoo.wow.spi.store.Store
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StoreResources
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

    /** The store providers on the classpath: the stores open as the host opens them (review R3.3-1). */
    private val providers = StoreProviders.load(javaClass.classLoader)

    private val resources = StoreResources.of(mapOf("dataSource" to dataSource, "client" to client))

    fun postgres(name: String): Store = open(name, StoreTypes.POSTGRES)

    fun mongo(name: String): Store = open(name, StoreTypes.MONGO, mapOf("database" to DATABASE))

    private fun open(name: String, type: String, options: Map<String, String> = emptyMap()): Store =
        providers.of(type, "test").open(StoreConfig.of(name, type, options = options, resources = resources))

    private const val POOL_SIZE = 16
    private const val DATABASE = "wow_rebuild"
}

/**
 * [SnapshotRebuildSpec] with the log and the first snapshot target on PostgreSQL, and the target added beside it on
 * MongoDB: a snapshot store of another type backfilled from a PostgreSQL log.
 */
class PostgresSnapshotRebuildTest : SnapshotRebuildSpec() {
    override fun store(name: String): Store =
        if (name == A) RebuildDatabases.postgres(name) else RebuildDatabases.mongo(name)
}

/**
 * [SnapshotRebuildSpec] with the log and the first snapshot target on MongoDB, and the target added beside it on
 * PostgreSQL.
 */
class MongoSnapshotRebuildTest : SnapshotRebuildSpec() {
    override fun store(name: String): Store =
        if (name == A) RebuildDatabases.mongo(name) else RebuildDatabases.postgres(name)
}
