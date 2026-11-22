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
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.postgres.PostgresFixture
import me.ahoo.wow.postgres.PostgresIdentifiers
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotTables
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.index.QueryIndexDeclaration
import me.ahoo.wow.query.index.QueryIndexPlan
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * PG-12: a declared index gives a sort by its keys, ascending and descending, whether a key is a NOT NULL system
 * column (`last_event_time`), which the compiler orders plainly, or a field that may be null, which it orders
 * `NULLS FIRST` ascending and `NULLS LAST` descending. The evidence is the plan of the compiler's own statement, under
 * `enable_sort` and `enable_seqscan` off: a sort the index cannot give is still a `Sort` node (the planner has no
 * other way to the order), and one it can give is an index scan, whatever the row counts make cheapest.
 */
class PostgresDeclaredIndexOrderTest {
    @Test
    fun `a declared index gives a sort by its keys in both directions, a NOT NULL column's too`(): Unit = runBlocking {
        val type = AggregateType("it_" + UUID.randomUUID().toString().replace("-", "").take(12), "order")
        val storage = PostgresSnapshotQueryStorage(PostgresFixture.dataSource, type.context).also {
            PostgresFixture.snapshotStore(type.context).createTable(type.name)
        }
        val schema = QueryFixtures.schema(type, PostgresSnapshotStorageAdapter)
        val declared = listOf(
            // A "latest first" list: the time a snapshot last changed, alone.
            QueryIndexDeclaration("latest", listOf("lastEventTime")),
            // The same within one status: equal on the leading key, ordered by the next.
            QueryIndexDeclaration("status_latest", listOf("state.status", "lastEventTime")),
            // Both keys may be null.
            QueryIndexDeclaration("status_created", listOf("state.status", "state.createdAt")),
        )
        storage.manageIndexes(PostgresQueryIndexes.plan(type.context, type.name, QueryIndexPlan.of(schema, declared)))
        val table = storage.table(type.name)
        seed(table)
        val compiler = PostgresSnapshotSqlCompiler(table)
        val active = listOf(TenantIdFilter("t1"), DeletionFilter(DeletionState.ACTIVE))
        fun plan(field: String, direction: Sort.Direction, vararg filters: FilterExpression): String {
            val query = ListQuery(
                AndFilter(active + filters),
                sort = listOf(Sort(QueryField(field), direction)),
                limit = PAGE,
            )
            return explain(compiler.list(QueryAdmission.Trusted.list(query, schema)))
        }
        val paid = EqualFilter(QueryField("state.status"), "S1")

        plan("lastEventTime", Sort.Direction.ASC).assert()
            .contains("Index Scan using ${index(type, "latest")}").doesNotContain("Sort")
        plan("lastEventTime", Sort.Direction.DESC).assert()
            .contains("Index Scan Backward using ${index(type, "latest")}").doesNotContain("Sort")
        plan("lastEventTime", Sort.Direction.ASC, paid).assert()
            .contains("Index Scan using ${index(type, "status_latest")}").doesNotContain("Sort")
        plan("lastEventTime", Sort.Direction.DESC, paid).assert()
            .contains("Index Scan Backward using ${index(type, "status_latest")}").doesNotContain("Sort")
        // A key that may be null, NULLS FIRST in the index, agrees with the compiler's order both ways.
        plan("state.createdAt", Sort.Direction.ASC, paid).assert()
            .contains("Index Scan using ${index(type, "status_created")}").doesNotContain("Sort")
        plan("state.createdAt", Sort.Direction.DESC, paid).assert()
            .contains("Index Scan Backward using ${index(type, "status_created")}").doesNotContain("Sort")
    }

    /** The physical name of [type]'s declared index [name]. */
    private fun index(type: AggregateType, name: String): String =
        PostgresIdentifiers.limited(PostgresSnapshotTables(type.context, type.name).base + "_" + name)

    /** The plan of [statement] with no sort and no sequential scan the planner may choose while another way exists. */
    private fun explain(statement: SqlStatement): String = PostgresFixture.dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            connection.createStatement().use {
                it.execute("SET LOCAL enable_sort = off")
                it.execute("SET LOCAL enable_seqscan = off")
            }
            SqlStatement("EXPLAIN (COSTS OFF) " + statement.sql, statement.parameters).prepare(connection)
                .use { explain ->
                    explain.executeQuery().use { rows ->
                        buildString { while (rows.next()) appendLine(rows.getString(1)) }
                    }
                }
        } finally {
            connection.rollback()
            connection.autoCommit = true
        }
    }

    /** [ROWS] snapshots of each of three tenants, five statuses, every tenth without a `createdAt`. */
    private fun seed(table: String) {
        PostgresFixture.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO $table (tenant_id, aggregate_id, version, schema_fingerprint, state, deleted,
                        first_event_time, last_event_time)
                    SELECT 't' || (i % 3), 'id-' || lpad(i::text, 6, '0'), 1, 'it',
                        CASE WHEN i % 10 = 0 THEN jsonb_build_object('status', 'S' || (i % 5))
                            ELSE jsonb_build_object('status', 'S' || (i % 5), 'createdAt', i) END,
                        false, 0, i
                    FROM generate_series(1, ${ROWS * 3}) AS i
                    """.trimIndent(),
                )
                statement.execute("ANALYZE $table")
            }
        }
    }

    private companion object {
        const val ROWS = 10_000
        const val PAGE = 20
    }
}
