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

package me.ahoo.wow.postgres.query

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.postgres.PostgresEventLogTables
import me.ahoo.wow.postgres.PostgresFixture
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.CursorPosition
import me.ahoo.wow.query.backend.PageWindow
import me.ahoo.wow.query.index.QueryIndexDeclaration
import me.ahoo.wow.query.index.QueryIndexPlan
import me.ahoo.wow.query.schema.EventsQuerySchemas
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.spi.log.Append
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * X-105: under one pinned tenant the tenant key leaves the keyset condition, so a deep page seeks to its position in
 * an index led by the tenant instead of filtering its tie group up to it. The evidence is the plan of the compiler's
 * own statement, executed: the index condition and the rows the scan filtered out after reading them.
 */
class PostgresPinnedTenantKeysetTest {
    private fun type() = AggregateType("it_" + UUID.randomUUID().toString().replace("-", "").take(12), "order")

    @Test
    fun `a deep page on a low-cardinality key seeks to its position under a pinned tenant`(): Unit = runBlocking {
        val type = type()
        val storage = PostgresSnapshotQueryStorage(PostgresFixture.dataSource, type.context).also {
            PostgresFixture.snapshotStore(type.context).createTable(type.name)
        }
        val schema = QueryFixtures.schema(type, PostgresSnapshotStorageAdapter)
        val declared = QueryIndexDeclaration("status", listOf("state.status"))
        storage.manageIndexes(
            PostgresQueryIndexes.plan(type.context, type.name, QueryIndexPlan.of(schema, listOf(declared)))
        )
        val table = storage.table(type.name)
        // Five statuses, every tenth snapshot without one: 6 000 of each status in tenant t1 (i % 3 == 1).
        statement(
            """
            INSERT INTO $table (tenant_id, aggregate_id, version, schema_fingerprint, state, deleted,
                first_event_time, last_event_time)
            SELECT 't' || (i % 3), 'id-' || lpad(i::text, 6, '0'), 1, 'it',
                CASE WHEN i % 10 = 0 THEN '{}'::jsonb ELSE jsonb_build_object('status', 'S' || (i % 5)) END,
                false, 0, i
            FROM generate_series(1, $ROWS) AS i
            """.trimIndent(),
            "ANALYZE $table",
        )
        val compiler = PostgresSnapshotSqlCompiler(table)
        val sort = listOf(Sort(QueryField("state.status"), Sort.Direction.DESC))
        fun keyset(filter: FilterExpression, status: String?, id: Int): SqlStatement {
            val admitted = QueryAdmission.Trusted.cursor(CursorQuery(filter, sort = sort, size = PAGE), schema)
            val position = CursorPosition(listOf(status, "t1", "id-%06d".format(id)))
            return compiler.keyset(admitted, PageWindow.Keyset(position, PAGE)).first
        }
        val pinned = AndFilter(listOf(TenantIdFilter("t1"), DeletionFilter(DeletionState.ACTIVE)))

        // A position at the end of S2's 6 000, read newest first: the row (status, aggregate_id), the index's own
        // order, sought to whole. With the tenant in the row the scan reads S2's index entries down to the position
        // and drops them in the index (no rows "removed by filter", but every one read): the buffers tell.
        val values = analyze(keyset(pinned, "S2", 7))
        values.assert().contains("Index Scan Backward using").contains("_status")
            .contains("aggregate_id) < ROW(").doesNotContain("tenant_id, aggregate_id) < ROW(").doesNotContain("Sort")
        removed(values).assert().isLessThanOrEqualTo(PAGE.toLong())
        buffers(values).assert().isLessThanOrEqualTo(PAGE.toLong())

        // A position deep among the nulls: their aggregates before it, both conditions of the index.
        val nulls = analyze(keyset(pinned, null, 60_010))
        nulls.assert().contains("Index Scan Backward using").contains("IS NULL").contains("aggregate_id <")
            .doesNotContain("Sort")
        removed(nulls).assert().isLessThanOrEqualTo(PAGE.toLong())
        buffers(nulls).assert().isLessThanOrEqualTo(PAGE.toLong())

        // Across tenants nothing is pinned: the tenant stays in the bound.
        keyset(DeletionFilter(DeletionState.ACTIVE), "S2", 60_007).sql.assert()
            .contains("tenant_id, aggregate_id) < (?, ?, ?)")
    }

    @Test
    fun `an events page seeks to its position in the version key under a pinned tenant`(): Unit = runBlocking {
        val type = type()
        val log = PostgresFixture.log(PARTITIONS, type.context, listOf(type.name))
        for (chunk in (0 until STREAMS).chunked(BATCH)) {
            log.appendAll(
                chunk.map { n ->
                    Append.of(
                        EventStreamRecord(
                            ref = type.ref(if (n % 2 == 0) "t3" else "t4", "o-%06d".format(n)),
                            version = 1,
                            commandId = "c-$n",
                            requestId = "r-$n",
                            commandType = "${type.context}.order.Create",
                            commitTime = n.toLong(),
                            events = listOf(EventRecord("${type.context}.order.Created", 1, "{}")),
                            scope = Scope.NONE,
                        )
                    )
                },
            )
        }
        val table = PostgresEventLogTables(type.context).aggregate(type.name).log
        statement("ANALYZE $table")
        val manifest = Manifest(
            contexts = listOf(
                ContextManifest(
                    type.context,
                    aggregates = listOf(
                        AggregateManifest(type.name, state = StateManifest(Manifest.schemaRef("it.S")))
                    ),
                ),
            ),
            defs = mapOf("it.S" to mapOf("type" to "object")),
        )
        val binding = checkNotNull(PostgresEventQueryStorage(PostgresFixture.dataSource, type.context).binding(type))
        val schema = QueryModelCompiler.of(events = EventsQuerySchemas(manifest))
            .compile(QuerySchemaContext(type, QueryModel.EVENTS), binding.storage)
            .schema()
        val compiler = PostgresSnapshotSqlCompiler(table, PostgresEventQueryLayout.EVENTS)
        fun keyset(filter: FilterExpression): SqlStatement {
            val admitted = QueryAdmission.Trusted.cursor(CursorQuery(filter, size = PAGE), schema)
            // The default order, the stream key: tenant, aggregate, version; a position deep in tenant t3.
            val position = CursorPosition(listOf("t3", "o-%06d".format(DEEP_STREAM), BigDecimal.ONE))
            return compiler.keyset(admitted, PageWindow.Keyset(position, PAGE)).first
        }

        val deep = analyze(keyset(TenantIdFilter("t3")))
        deep.assert().contains("Index Scan").contains("ROW(aggregate_id, version) > ROW(").doesNotContain("Sort")
        removed(deep).assert().isLessThanOrEqualTo(PAGE.toLong())
        buffers(deep).assert().isLessThanOrEqualTo(PAGE.toLong())
        // Across tenants, the whole stream key.
        keyset(OrFilter(listOf(TenantIdFilter("t3"), TenantIdFilter("t4")))).sql.assert()
            .contains("(tenant_id, aggregate_id, version) > (?, ?, ?)")
    }

    private fun statement(vararg sql: String) = PostgresFixture.dataSource.connection.use { connection ->
        connection.createStatement().use { statement -> sql.forEach(statement::execute) }
    }

    /** The rows every node of [plan] read and then filtered out. */
    private fun removed(plan: String): Long = Regex("Rows Removed by Filter: (\\d+)").findAll(plan)
        .sumOf { it.groupValues[1].toLong() }

    /** The shared buffers the whole of [plan] touched: its top node's. */
    private fun buffers(plan: String): Long =
        Regex("Buffers: shared hit=(\\d+)(?: read=(\\d+))?").find(plan)!!.groupValues.drop(1)
            .sumOf { it.toLongOrNull() ?: 0L }

    private fun analyze(statement: SqlStatement): String = PostgresFixture.dataSource.connection.use { connection ->
        SqlStatement("EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) " + statement.sql, statement.parameters).prepare(connection)
            .use { explain ->
                explain.executeQuery().use { rows -> buildString { while (rows.next()) appendLine(rows.getString(1)) } }
            }
    }

    private companion object {
        const val ROWS = 90_000
        const val PAGE = 20
        const val PARTITIONS = 4
        const val STREAMS = 20_000
        const val BATCH = 500
        const val DEEP_STREAM = 13_000
    }
}
