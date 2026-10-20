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

import com.zaxxer.hikari.HikariDataSource
import me.ahoo.test.asserts.assert
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import org.junit.jupiter.api.Test

/**
 * The connections a configuration names, built without connecting: a PostgreSQL target behind a pool of its
 * `poolSize`, opened on first use and closed with the connections, and Elasticsearch Sinks only when configured.
 */
class ConfiguredConnectionsTest {
    private fun config(elasticsearch: ElasticsearchConfig? = null) = MigrationConfig(
        map = "migration-map.json",
        manifests = emptyList(),
        source = SourceConfig("mongodb://localhost:27017", "example_db"),
        target = TargetConfig(
            "postgres",
            jdbcUrl = "jdbc:postgresql://localhost:5432/wow",
            username = "wow",
            poolSize = 3
        ),
        elasticsearch = elasticsearch,
    )

    @Test
    fun `a PostgreSQL target is pooled as configured, and the pool closes with the connections`() {
        val connections = ConfiguredConnections(config(ElasticsearchConfig("http://localhost:9200", "app")))
        val store = connections.snapshotStoreOf("example") as PostgresSnapshotStore
        val pool = store.javaClass.declaredFields.single { it.type == javax.sql.DataSource::class.java }
            .apply { isAccessible = true }.get(store) as HikariDataSource
        pool.maximumPoolSize.assert().isEqualTo(3)
        pool.username.assert().isEqualTo("wow")
        connections.eventSinks.assert().isNotNull()
        connections.eventSinks!!.sinkOf("example", "order").index.assert().isEqualTo("app_wow_events__example__order")
        connections.close()
        pool.isClosed.assert().isTrue()
    }

    @Test
    fun `without Elasticsearch there are no Sinks, and an unused pool is never opened`() {
        val connections = ConfiguredConnections(config())
        connections.eventSinks.assert().isNull()
        connections.close()
    }
}
