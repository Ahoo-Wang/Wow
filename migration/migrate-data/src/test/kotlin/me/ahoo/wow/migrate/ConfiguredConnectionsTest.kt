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

package me.ahoo.wow.migrate

import com.mongodb.client.MongoDatabase
import com.zaxxer.hikari.HikariDataSource
import me.ahoo.test.asserts.assert
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import org.junit.jupiter.api.Test

/**
 * The connections a configuration names, built without connecting: a PostgreSQL target behind a pool of its
 * `poolSize`, opened on first use and closed with the connections.
 */
class ConfiguredConnectionsTest {
    private fun config() = MigrationConfig(
        map = "migration-map.json",
        manifests = emptyList(),
        source = SourceConfig("mongodb://localhost:27017", "example_db"),
        target = TargetConfig(
            "postgres",
            jdbcUrl = "jdbc:postgresql://localhost:5432/wow",
            username = "wow",
            poolSize = 3
        ),
    )

    @Test
    fun `a PostgreSQL target is pooled as configured, and the pool closes with the connections`() {
        val connections = ConfiguredConnections(config())
        val store = connections.snapshotStoreOf("example") as PostgresSnapshotStore
        val pool = store.javaClass.declaredFields.single { it.type == javax.sql.DataSource::class.java }
            .apply { isAccessible = true }.get(store) as HikariDataSource
        pool.maximumPoolSize.assert().isEqualTo(3)
        pool.username.assert().isEqualTo("wow")
        connections.close()
        pool.isClosed.assert().isTrue()
    }

    /** The name of the database [store] reads, held as its one `MongoDatabase`. */
    private fun databaseOf(store: Any): String = store.javaClass.declaredFields
        .single { MongoDatabase::class.java.isAssignableFrom(it.type) }
        .apply { isAccessible = true }.get(store).let { (it as MongoDatabase).name }

    @Test
    fun `every value written env NAME should be read from the environment, and only the value read used`() {
        val environment = mapOf(
            "SOURCE_URI" to "mongodb://localhost:27017",
            "SOURCE_DB" to "example_db",
            "PG_URL" to "jdbc:postgresql://localhost:5432/wow",
            "PG_USER" to "wow",
            "PG_PASSWORD" to "secret",
        )
        val written = config().copy(
            source = SourceConfig("env:SOURCE_URI", "env:SOURCE_DB"),
            target = TargetConfig(
                TargetConfig.POSTGRES,
                jdbcUrl = "env:PG_URL",
                username = "env:PG_USER",
                password = "env:PG_PASSWORD",
            ),
        )
        val connections = ConfiguredConnections(written) { environment[it] }
        connections.source.store.assert().isEqualTo("the MongoDB database example_db")
        listOf(connections.snapshots, connections.reservations, connections.nineXTarget).forEach {
            databaseOf(it).assert().isEqualTo("example_db")
        }
        val store = connections.snapshotStoreOf("example") as PostgresSnapshotStore
        val pool = store.javaClass.declaredFields.single { it.type == javax.sql.DataSource::class.java }
            .apply { isAccessible = true }.get(store) as HikariDataSource
        pool.jdbcUrl.assert().isEqualTo("jdbc:postgresql://localhost:5432/wow")
        pool.username.assert().isEqualTo("wow")
        pool.password.assert().isEqualTo("secret")
        connections.close()

        // A MongoDB target's client is made from its URI as read: `env:TARGET_URI` itself is no connection string.
        val mongo = mapOf("TARGET_URI" to "mongodb://localhost:27017", "TARGET_DB" to "wow") + environment
        val target = TargetConfig(TargetConfig.MONGO, uri = "env:TARGET_URI", database = "env:TARGET_DB")
        ConfiguredConnections(written.copy(target = target)) { mongo[it] }.close()
    }

    @Test
    fun `an unused pool is never opened`() {
        val connections = ConfiguredConnections(config())
        connections.close()
    }
}
