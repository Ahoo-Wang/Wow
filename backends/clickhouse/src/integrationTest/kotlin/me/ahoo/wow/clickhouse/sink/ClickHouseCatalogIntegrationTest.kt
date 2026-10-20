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

package me.ahoo.wow.clickhouse.sink

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.clickhouse.sink.ClickHouseSinkIntegrationTest.Companion.client
import me.ahoo.wow.clickhouse.sink.ClickHouseSinkIntegrationTest.Companion.execute
import me.ahoo.wow.clickhouse.sink.SinkFixtures.recordOf
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Sink's `wow_catalog` rows on a ClickHouse server (decision D241 ②): one per table, with the table's uuid, the
 * account that wrote it, and a column hash that the table's own columns reproduce.
 */
class ClickHouseCatalogIntegrationTest {
    private val database = "wow_catalog_${databases.incrementAndGet()}"

    init {
        execute("CREATE DATABASE IF NOT EXISTS $database")
    }

    private fun sinks(ddl: DdlMode = DdlMode.AUTO) =
        ClickHouseSinks(client, CatalogFixtures.MANIFEST, ClickHouseSinkOptions(database = database, ddl = ddl))

    @Test
    fun `each table should get a row that its own metadata confirms`(): Unit = runTest {
        sinks().sinkOf("crm", "member").write(listOf(recordOf(type = CatalogFixtures.MEMBER)))
        val rows = client.queryAll(
            "SELECT `table`, toString(table_uuid) AS uuid, role, context, aggregate, writer, columns_hash, " +
                "currentUser() AS me FROM $database.wow_catalog FINAL ORDER BY `table`",
        )
        rows.map { it.getString("table") }.assert().containsExactly(
            "crm_member_events",
            "crm_member_state",
            "crm_member_state_events",
            "crm_member_state_flat",
        )
        val statements = ClientStatements(client)
        rows.forEach { row ->
            val table = row.getString("table")
            row.getString("uuid").assert().isEqualTo(statements.uuid(database, table))
            row.getString("writer").assert().isEqualTo(row.getString("me"))
            row.getString("context").assert().isEqualTo("crm")
            row.getString("aggregate").assert().isEqualTo("member")
            row.getString("columns_hash").assert().describedAs(table)
                .isEqualTo(ClickHouseCatalog.columnsHash(statements.columns(database, table)))
        }
        val sensitive = client.queryAll(
            "SELECT s.path AS path, s.level AS level, s.mask AS mask FROM $database.wow_catalog FINAL " +
                "ARRAY JOIN sensitive AS s WHERE `table` = 'crm_member_state_flat'",
        ).single()
        sensitive.getString("path").assert().isEqualTo("phone")
        sensitive.getString("level").assert().isEqualTo("DISPLAY")
        sensitive.getString("mask").assert().isEqualTo("""{"keepPrefix":3,"keepSuffix":4}""")
    }

    @Test
    fun `another node writing the same content should leave one row per table`(): Unit = runTest {
        sinks().sinkOf("crm", "member").write(listOf(recordOf(type = CatalogFixtures.MEMBER)))
        sinks(DdlMode.VALIDATE).sinkOf("crm", "member").write(listOf(recordOf(type = CatalogFixtures.MEMBER, version = 2)))
        sinks(DdlMode.NONE).sinkOf("crm", "member").write(listOf(recordOf(type = CatalogFixtures.MEMBER, version = 3)))
        client.queryAll("SELECT count() AS n FROM $database.wow_catalog FINAL").single().getLong("n")
            .assert().isEqualTo(4)
    }

    @Test
    fun `without wow_catalog the sink should write its records all the same`(): Unit = runTest {
        sinks().sinkOf("crm", "member").prepare()
        execute("DROP TABLE $database.wow_catalog")
        sinks(DdlMode.VALIDATE).sinkOf("crm", "member").write(listOf(recordOf(type = CatalogFixtures.MEMBER)))
        client.queryAll("SELECT count() AS n FROM $database.crm_member_state_events").single().getLong("n")
            .assert().isEqualTo(1)
    }

    private companion object {
        val databases = AtomicInteger()
    }
}
