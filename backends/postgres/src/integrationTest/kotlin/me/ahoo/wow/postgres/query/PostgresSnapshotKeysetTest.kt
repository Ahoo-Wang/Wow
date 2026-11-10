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
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.postgres.PostgresFixture
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.CursorPosition
import me.ahoo.wow.query.backend.PageWindow
import me.ahoo.wow.query.index.QueryIndexDeclaration
import me.ahoo.wow.query.index.QueryIndexPlan
import me.ahoo.wow.spi.Ddl
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * PG-3: a keyset page deep in a cursor reads from its position, not from the first row of the tenant. The keyset
 * condition is an `OR` the B-tree cannot seek with; the leading bound beside it can. The evidence is the plan of the
 * compiler's own statement, executed: the rows the index scan filtered out after reading them.
 */
class PostgresSnapshotKeysetTest {
    @Test
    fun `a deep keyset page seeks to its position in the key and in a declared index`(): Unit = runBlocking {
        val type = AggregateType("it_" + UUID.randomUUID().toString().replace("-", "").take(12), "order")
        val storage = PostgresSnapshotQueryStorage(PostgresFixture.dataSource, type.context).also {
            PostgresFixture.snapshotStore(type.context).createTable(type.name)
        }
        val schema = QueryFixtures.schema(type, PostgresSnapshotStorageAdapter)
        val indexes = PostgresQueryIndexes.plan(
            type.context,
            type.name,
            QueryIndexPlan.of(schema, listOf(QueryIndexDeclaration("amount", listOf("state.amount")))),
        )
        storage.manageIndexes(indexes)
        val table = storage.table(type.name)
        seed(table)
        val compiler = PostgresSnapshotSqlCompiler(table)
        // The default scope, which a declared index, partial on the active snapshots, serves.
        val active = AndFilter(listOf(TenantIdFilter("t1"), DeletionFilter(DeletionState.ACTIVE)))
        fun deepPage(sort: List<Sort>, position: List<Any?>): String {
            val admitted = QueryAdmission.Trusted.cursor(CursorQuery(active, sort = sort, size = PAGE), schema)
            return analyze(compiler.keyset(admitted, PageWindow.Keyset(CursorPosition(position), PAGE)))
        }

        // The default order, the table's key: (tenant_id, aggregate_id) from the position on.
        val byKey = deepPage(emptyList(), listOf("t1", "id-%06d".format(DEEP)))
        // An ordered scan of the primary key from the position, which reads the page and stops.
        byKey.assert().contains("Index Scan using").contains("_pkey").contains("ROW(tenant_id, aggregate_id) > ROW(")
            .doesNotContain("Sort")
        removed(byKey).assert().isLessThanOrEqualTo(PAGE.toLong())

        // A declared field, ascending: its index, from the field's position on.
        val amount = Sort(QueryField("state.amount"), Sort.Direction.ASC)
        val byAmount = deepPage(listOf(amount), listOf(BigDecimal(DEEP), "t1", "id-%06d".format(DEEP)))
        byAmount.assert().contains("Index Scan using").contains("_amount").contains("ROW(")
        removed(byAmount).assert().isLessThanOrEqualTo(PAGE.toLong())
        // The declared index ends with the aggregate ID, so it gives the whole order: no sort of any kind (MG-2/PG-11).
        byAmount.assert().doesNotContain("Sort")

        // Descending, the key descends with it: the same index read backward, still no sort. A state field may hold
        // a NULL, which sorts last descending, so no bound seeks to the position; the scan filters up to it.
        val descending = deepPage(
            listOf(amount.copy(direction = Sort.Direction.DESC)),
            listOf(BigDecimal(DEEP), "t1", "id-%06d".format(DEEP)),
        )
        descending.assert().contains("Index Scan Backward using").contains("_amount").doesNotContain("Sort")
    }

    @Test
    fun `a declared index of another definition is drift, reported under validate and rebuilt under auto`() {
        val type = AggregateType("it_" + UUID.randomUUID().toString().replace("-", "").take(12), "order")
        PostgresFixture.snapshotStore(type.context).createTable(type.name)
        val schema = runBlocking { QueryFixtures.schema(type, PostgresSnapshotStorageAdapter) }
        val planned = PostgresQueryIndexes.plan(
            type.context,
            type.name,
            QueryIndexPlan.of(schema, listOf(QueryIndexDeclaration("amount", listOf("state.amount")))),
        ).single()
        // The index of the name as a build before the tie-breaker made it: its keys without the aggregate ID.
        PostgresFixture.dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.execute(planned.ddl.replace(", aggregate_id)", ")").replace(" CONCURRENTLY", ""))
            }
        }
        PostgresQueryIndexes.manage(PostgresFixture.dataSource, Ddl.VALIDATE, listOf(planned)).invalid.assert()
            .containsExactly(planned.name)
        PostgresQueryIndexes.manage(PostgresFixture.dataSource, Ddl.AUTO, listOf(planned)).created.assert()
            .containsExactly(planned.name)
        PostgresQueryIndexes.manage(PostgresFixture.dataSource, Ddl.VALIDATE, listOf(planned)).assert()
            .isEqualTo(PostgresQueryIndexReport())
        PostgresFixture.dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT pg_get_indexdef(to_regclass(?))").use { query ->
                query.setString(1, "\"${planned.name}\"")
                query.executeQuery().use { rows ->
                    rows.next()
                    rows.getString(1).assert().endsWith("aggregate_id) WHERE (NOT deleted)")
                }
            }
        }
    }

    @Test
    fun `a descending page on a field that may be null seeks to its position, then reads the nulls`(): Unit =
        runBlocking {
            val type = AggregateType("it_" + UUID.randomUUID().toString().replace("-", "").take(12), "order")
            val statements = mutableListOf<String>()
            val recording = recording(statements)
            val storage = PostgresSnapshotQueryStorage(recording, type.context).also {
                PostgresFixture.snapshotStore(type.context).createTable(type.name)
            }
            val schema = QueryFixtures.schema(type, PostgresSnapshotStorageAdapter)
            val declared = QueryIndexDeclaration("amount", listOf("state.amount"))
            storage.manageIndexes(PostgresQueryIndexes.plan(type.context, type.name, QueryIndexPlan.of(schema, listOf(declared))))
            val table = storage.table(type.name)
            // Every tenth snapshot has no amount: tenant t1's are i = 10, 40, ..., 89 980 (i % 30 == 10).
            seed(table, nulls = true)
            val backend = checkNotNull(storage.binding(type)).backend
            val compiler = PostgresSnapshotSqlCompiler(table)
            val active = AndFilter(listOf(TenantIdFilter("t1"), DeletionFilter(DeletionState.ACTIVE)))
            val sort = listOf(Sort(QueryField("state.amount"), Sort.Direction.DESC))
            fun admitted() = QueryAdmission.Trusted.cursor(CursorQuery(active, sort = sort, size = PAGE), schema)
            fun window(amount: Long?, id: Int) =
                PageWindow.Keyset(CursorPosition(listOf(amount?.let(::BigDecimal), "t1", "id-%06d".format(id))), PAGE)
            suspend fun page(window: PageWindow.Keyset): List<String> {
                statements.clear()
                return backend.page(admitted(), window).rows.map { it.path("aggregateId").stringValue() }
            }

            // A position among the values: one statement, which seeks to it and fills the page.
            val deep = window(DEEP.toLong(), DEEP)
            page(deep).assert().hasSize(PAGE).first().isEqualTo("id-%06d".format(DEEP - 3))
            statements.count { it.contains(" LIMIT ") }.assert().isEqualTo(1)
            val values = analyze(compiler.keyset(admitted(), deep))
            values.assert().contains("Index Scan Backward using").contains("_amount").contains("Index Cond")
                .contains("ROW(").doesNotContain("Sort")
            removed(values).assert().isLessThanOrEqualTo(PAGE.toLong())

            // A page that straddles them: the last two values (4 and 1), then the nulls, newest key first.
            page(window(7, 7)).assert().containsExactlyElementsOf(
                listOf("id-000004", "id-000001") + (0 until PAGE - 2).map { "id-%06d".format(89_980 - 30 * it) },
            )
            statements.count { it.contains(" LIMIT ") }.assert().isEqualTo(2)
            val nulls = analyze(checkNotNull(compiler.keysetNulls(admitted(), window(7, 7), PAGE - 2)))
            nulls.assert().contains("Index Scan Backward using").contains("IS NULL").doesNotContain("Sort")
            removed(nulls).assert().isLessThanOrEqualTo(PAGE.toLong())

            // A position among the nulls: one statement, the nulls after it. The index seeks to the nulls; within them
            // the tie-breakers (tenant_id, aggregate_id) are not consecutive columns of the index, so it filters the
            // 501 nulls before the position rather than seeking to it.
            val among = window(null, 89_980 - 30 * 500)
            page(among).assert().first().isEqualTo("id-%06d".format(89_980 - 30 * 501))
            statements.count { it.contains(" LIMIT ") }.assert().isEqualTo(1)
            compiler.keysetNulls(admitted(), among, PAGE).assert().isNull()
            val amongNulls = analyze(compiler.keyset(admitted(), among))
            amongNulls.assert().contains("Index Scan Backward using").contains("IS NULL").doesNotContain("Sort")
            removed(amongNulls).assert().isEqualTo(501L)
        }

    /** [PostgresFixture.dataSource], recording the SQL of every statement it prepares into [statements]. */
    private fun recording(statements: MutableList<String>): DataSource {
        val source = PostgresFixture.dataSource
        fun connection(target: Connection): Connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            if (method.name == "prepareStatement") statements += args[0] as String
            try {
                method.invoke(target, *(args ?: emptyArray()))
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        } as Connection
        return Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java)) { _, method, args ->
            try {
                val result = method.invoke(source, *(args ?: emptyArray()))
                if (result is Connection) connection(result) else result
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        } as DataSource
    }

    /** The rows every node of [plan] read and then filtered out. */
    private fun removed(plan: String): Long = Regex("Rows Removed by Filter: (\\d+)").findAll(plan)
        .sumOf { it.groupValues[1].toLong() }

    private fun analyze(statement: SqlStatement): String = PostgresFixture.dataSource.connection.use { connection ->
        SqlStatement("EXPLAIN (ANALYZE, COSTS OFF) " + statement.sql, statement.parameters).prepare(connection)
            .use { explain ->
                explain.executeQuery().use { rows -> buildString { while (rows.next()) appendLine(rows.getString(1)) } }
            }
    }

    /** [ROWS] snapshots of one tenant among three, their keys and amounts in one order; with [nulls], every tenth without one. */
    private fun seed(table: String, nulls: Boolean = false) {
        val state = if (nulls) "CASE WHEN i % 10 = 0 THEN '{}'::jsonb ELSE jsonb_build_object('amount', i) END" else "jsonb_build_object('amount', i)"
        PostgresFixture.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO $table (tenant_id, aggregate_id, version, schema_fingerprint, state, deleted,
                        first_event_time, last_event_time)
                    SELECT 't' || (i % 3), 'id-' || lpad(i::text, 6, '0'), 1, 'it',
                        $state, false, 0, i
                    FROM generate_series(1, ${ROWS * 3}) AS i
                    """.trimIndent(),
                )
                statement.execute("ANALYZE $table")
            }
        }
    }

    private companion object {
        const val ROWS = 30_000
        const val PAGE = 20

        /** A position about two thirds into tenant t1's rows (i % 3 == 1). */
        const val DEEP = 60_001
    }
}
