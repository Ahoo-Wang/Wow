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

package me.ahoo.wow.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotTables
import me.ahoo.wow.postgres.subscription.PostgresLeaseStore
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * The format checks name the cause of a refusal: a log whose schema was applied without its partition counters is
 * refused as that by `validate`, not as a repartition, and has them written by `auto`; the snapshot tables' registry has a format record, so a registry missing
 * under `validate` is refused at start, naming it, not at the first read.
 */
class PostgresFormatChecksTest {
    private val dataSource = PostgresFixture.dataSource

    private fun execute(sql: String) = dataSource.connection.use { it.createStatement().use { s -> s.execute(sql) } }

    @Test
    fun `a log without its partition counters should be refused as that, naming the statement that writes them`() {
        val context = PostgresFixture.newContext("formats")
        PostgresEventLog(dataSource, context, 2).createSchema()
        val tables = PostgresEventLogTables(context)
        execute("DELETE FROM ${tables.partitions}")

        assertThrows<IllegalStateException> {
            PostgresEventLog(dataSource, context, 2, options = PostgresEventLogOptions(ddl = Ddl.VALIDATE))
                .createSchema()
        }.message.assert().contains("no partition counters", tables.initializePartitions(2))
            .doesNotContain("repartition")
        // auto writes them, as it does when it creates the log; validate then finds them.
        PostgresEventLog(dataSource, context, 2).createSchema()
        PostgresEventLog(dataSource, context, 2, options = PostgresEventLogOptions(ddl = Ddl.VALIDATE)).createSchema()
    }

    /** A pool of a schema of its own, whose registries no other test shares: one this test may drop. */
    private fun isolated(): HikariDataSource {
        val schema = "formats_" + UUID.randomUUID().toString().replace("-", "").take(SCHEMA_SUFFIX)
        execute("CREATE SCHEMA $schema")
        val container = WowContainers.postgres
        val url = container.jdbcUrl
        return HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = url + (if ('?' in url) "&" else "?") + "currentSchema=$schema"
                username = container.username
                password = container.password
                maximumPoolSize = 2
                minimumIdle = 0
            },
        )
    }

    @Test
    fun `the snapshot tables' registry should have a format record, its absence refused under validate`() {
        isolated().use { dataSource -> registryChecked(dataSource) }
    }

    private fun registryChecked(dataSource: HikariDataSource) {
        val context = PostgresFixture.newContext("formats")
        PostgresSnapshotStore(dataSource, context).createTable("order")
        val recorded = "SELECT version FROM ${PostgresFormat.TABLE} WHERE structure = ?"
        dataSource.connection.use { connection ->
            connection.prepareStatement(recorded).use { query ->
                query.setString(1, PostgresSnapshotTables.TABLES)
                query.executeQuery().use { rows ->
                    rows.next().assert().isTrue()
                    rows.getInt(1).assert().isEqualTo(PostgresSnapshotTables.FORMAT)
                }
            }
        }
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("DROP TABLE ${PostgresSnapshotTables.TABLES}") }
        }
        assertThrows<IllegalStateException> {
            PostgresSnapshotStore(dataSource, context, ddl = Ddl.VALIDATE).createTable("order")
        }.message.assert().contains(PostgresSnapshotTables.TABLES)
    }

    @Test
    fun `a member record should carry the node's version of Wow`() {
        val service = PostgresFixture.newContext("formats-service")
        PostgresLeaseStore(dataSource, wowVersion = VERSION).use { store ->
            store.createSchema()
            runBlocking {
                store.heartbeat(service, "node-a", "127.0.0.1:9090", 10.seconds).wowVersion.assert().isEqualTo(VERSION)
                store.members(service).single().wowVersion.assert().isEqualTo(VERSION)
            }
        }
    }

    @Test
    fun `a column added within the format should be refused by validate when missing, and added by auto`() {
        isolated().use { dataSource ->
            PostgresLeaseStore(dataSource).use { it.createSchema() }
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("ALTER TABLE wow_lease_member DROP COLUMN wow_version") }
            }
            assertThrows<IllegalStateException> {
                PostgresLeaseStore(dataSource, ddl = Ddl.VALIDATE).use { it.createSchema() }
            }.message.assert().contains("wow_lease_member", "ADD COLUMN IF NOT EXISTS wow_version")
            PostgresLeaseStore(dataSource).use { it.createSchema() }
            PostgresLeaseStore(dataSource, ddl = Ddl.VALIDATE).use { it.createSchema() }
        }
    }

    private companion object {
        const val SCHEMA_SUFFIX = 12
        const val VERSION = "10.0.0-test"
    }
}
