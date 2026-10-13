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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.TotalMode
import me.ahoo.wow.postgres.PostgresFixture
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotTables
import me.ahoo.wow.query.GroupWindow
import me.ahoo.wow.query.PageWindow
import me.ahoo.wow.query.QueryAdmission
import me.ahoo.wow.query.QueryTimeoutException
import me.ahoo.wow.query.list
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryViolation
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

class PostgresSnapshotQueryStorageTest {
    private fun newType(): AggregateType =
        AggregateType("it_" + UUID.randomUUID().toString().replace("-", "").take(12), "order")

    private fun storage(type: AggregateType, options: PostgresQueryOptions = PostgresQueryOptions()) =
        PostgresSnapshotQueryStorage(PostgresFixture.dataSource, type.context, options).also {
            PostgresFixture.snapshotStore(type.context)
        }

    private suspend fun schema(type: AggregateType): QueryModelSchema =
        QueryFixtures.schema(type, PostgresSnapshotStorageAdapter)

    @Test
    fun `admission rejects what PostgreSQL declines`(): Unit = runBlocking {
        val type = newType()
        val schema = schema(type)
        fun rejects(filter: FilterExpression, sort: List<Sort> = emptyList()) {
            val error = runCatching { QueryAdmission.Trusted.list(ListQuery(filter, sort = sort), schema) }.exceptionOrNull()
            val violation = (error as QuerySchemaValidationException).violation
            (violation is QueryViolation.UnsupportedCapability).assert().describedAs("$violation").isTrue()
        }
        rejects(GreaterThanFilter(QueryField("state.issuedAt"), "2026-01-01T00:00:00Z"))
        rejects(EqualFilter(QueryField("state.issuedAt"), "2026-01-01T00:00:00Z"))
        rejects(EqualFilter(QueryField("state.mixed"), "one"))
        rejects(MatchAllFilter, listOf(Sort(QueryField("state.keywords"), Sort.Direction.ASC)))
        rejects(MatchAllFilter, listOf(Sort(QueryField("state.issuedAt"), Sort.Direction.ASC)))
        assertThrownBy<QuerySchemaValidationException> {
            QueryAdmission.Trusted.list(ListQuery(SearchFilter("wow")), schema)
        }
        // A date string and a value of mixed types still exist.
        QueryAdmission.Trusted.list(ListQuery(AndFilter(listOf(ExistsFilter(QueryField("state.issuedAt")), ExistsFilter(QueryField("state.mixed"))))), schema)
        Unit
    }

    @Test
    fun `a storage serves only its own context`() {
        val type = newType()
        assertThrownBy<IllegalArgumentException> { storage(type).create(AggregateType("elsewhere", "order")) }
        storage(type).table.assert().isEqualTo(PostgresSnapshotTables(type.context).snapshot)
    }

    @Test
    fun `a statement PostgreSQL cancels at the timeout is the query timeout`(): Unit = runBlocking {
        val type = newType()
        val storage = storage(type)
        seed(storage.table, ROWS)
        val backend = storage.create(type).backend
        val schema = schema(type)
        val timeout = Duration.ofMillis(1)
        // A filter no index serves, so every statement reads the whole table.
        val slow = GreaterThanFilter(QueryField("state.amount"), -1)
        suspend fun times(block: suspend () -> Unit) {
            val error = runCatching { block() }.exceptionOrNull()
            (error is QueryTimeoutException).assert().describedAs("$error").isTrue()
            (error as QueryTimeoutException).timeout.assert().isEqualTo(timeout)
        }
        times { backend.count(QueryAdmission.Trusted.count(CountQuery(slow), schema).withTimeout(timeout)) }
        times { backend.stream(QueryAdmission.Trusted.list(ListQuery(slow, sort = listOf(Sort(QueryField("state.amount"), Sort.Direction.DESC))), schema).withTimeout(timeout)).toList() }
        times {
            backend.page(
                QueryAdmission.Trusted.paged(PagedQuery(slow, total = TotalMode.EXACT), schema).withTimeout(timeout),
                PageWindow.Offset(0, 1, withTotal = true),
            )
        }
        times {
            val query = AggregationQuery(slow, groupBy = listOf(AggregationGroup.Terms(QueryField("state.status"), "status")), metrics = listOf(AggregationMetric.Count("count")))
            backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema).withTimeout(timeout), GroupWindow.All).toList()
        }
        // Without a timeout the same statement runs to the end.
        backend.count(QueryAdmission.Trusted.count(CountQuery(slow, limit = 10), schema)).assert().isEqualTo(11L)
    }

    @Test
    fun `auto creates the declared indexes, validate and none only look`(): Unit = runBlocking {
        val type = newType()
        val schema = schema(type)
        val declared = listOf(QueryIndexDeclaration("ix_order_state_status", listOf("state.status", "state.amount")))
        val indexes = PostgresQueryIndexes.plan(type.context, schema, declared)
        storage(type, PostgresQueryOptions(QueryIndexDdl.NONE)).manageIndexes(indexes).assert().isEqualTo(PostgresQueryIndexReport())
        val name = indexes.single().name
        storage(type, PostgresQueryOptions(QueryIndexDdl.VALIDATE)).manageIndexes(indexes)
            .assert().isEqualTo(PostgresQueryIndexReport(missing = listOf(name)))
        storage(type).manageIndexes(indexes).assert().isEqualTo(PostgresQueryIndexReport(created = listOf(name), missing = listOf(name)))
        storage(type).manageIndexes(indexes).assert().isEqualTo(PostgresQueryIndexReport())
        storage(type, PostgresQueryOptions(QueryIndexDdl.VALIDATE)).manageIndexes(indexes).assert().isEqualTo(PostgresQueryIndexReport())
        storage(type).manageIndexes(emptyList()).assert().isEqualTo(PostgresQueryIndexReport())

        // An index a failed concurrent build left invalid is reported, and rebuilt by auto.
        invalidate(name)
        storage(type, PostgresQueryOptions(QueryIndexDdl.VALIDATE)).manageIndexes(indexes)
            .assert().isEqualTo(PostgresQueryIndexReport(invalid = listOf(name)))
        storage(type).manageIndexes(indexes).assert().isEqualTo(PostgresQueryIndexReport(created = listOf(name), invalid = listOf(name)))
        storage(type, PostgresQueryOptions(QueryIndexDdl.VALIDATE)).manageIndexes(indexes).assert().isEqualTo(PostgresQueryIndexReport())
    }

    @Test
    fun `queries use the scope indexes and the declared expression index`(): Unit = runBlocking {
        val type = newType()
        val storage = storage(type)
        val schema = schema(type)
        val indexes = PostgresQueryIndexes.plan(
            type.context,
            schema,
            listOf(QueryIndexDeclaration("ix_order_state_status", listOf("state.status", "state.amount"))),
        )
        storage.manageIndexes(indexes)
        seed(storage.table, ROWS)
        val tables = PostgresSnapshotTables(type.context)
        val compiler = PostgresSnapshotSqlCompiler(storage.table, type.name)
        fun scoped(vararg filters: FilterExpression): FilterExpression =
            AndFilter(listOf(TenantIdFilter("t3"), DeletionFilter(DeletionState.ACTIVE)) + filters)
        fun plan(filter: FilterExpression): String =
            explain(compiler.list(QueryAdmission.Trusted.list(ListQuery(filter, limit = 10), schema)))

        plan(scoped(OwnerIdFilter("owner-7"))).assert().contains(tables.ownerIndex)
        plan(scoped(SpaceIdFilter("space-7"))).assert().contains(tables.spaceIndex)
        plan(scoped(EqualFilter(QueryField("state.status"), "S7"), GreaterThanFilter(QueryField("state.amount"), 100)))
            .assert().contains(indexes.single().name)
        val counted = QueryAdmission.Trusted.count(CountQuery(scoped(EqualFilter(QueryField("state.status"), "S7")), 10), schema)
        explain(compiler.count(counted, counted.query.filter, 11)).assert().contains(indexes.single().name)
    }

    private fun explain(statement: SqlStatement): String = PostgresFixture.dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            connection.createStatement().use { it.execute("SET LOCAL enable_seqscan = off") }
            SqlStatement("EXPLAIN " + statement.sql, statement.parameters).prepare(connection).use { explain ->
                explain.executeQuery().use { rows ->
                    buildString { while (rows.next()) appendLine(rows.getString(1)) }
                }
            }
        } finally {
            connection.rollback()
            connection.autoCommit = true
        }
    }

    private fun invalidate(index: String) {
        PostgresFixture.dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE pg_index SET indisvalid = false WHERE indexrelid = ?::regclass").use {
                it.setString(1, "\"$index\"")
                it.executeUpdate()
            }
        }
    }

    /** [rows] snapshots of `order` over ten tenants, 500 owners, 300 spaces and 50 statuses; every 20th deleted. */
    private fun seed(table: String, rows: Int) {
        PostgresFixture.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO $table (tenant_id, aggregate_name, aggregate_id, version, schema_fingerprint, state,
                        owner_id, space_id, deleted, first_event_time, last_event_time, tags)
                    SELECT 't' || (i % 10), 'order', 'id-' || i, 1, 'it',
                        jsonb_build_object('status', 'S' || (i % 50), 'amount', i, 'name', 'n' || i),
                        'owner-' || (i % 500), 'space-' || (i % 300), i % 20 = 0, 0, i, '{}'::jsonb
                    FROM generate_series(1, $rows) AS i
                    """.trimIndent(),
                )
                statement.execute("ANALYZE $table")
            }
        }
    }

    private companion object {
        const val ROWS = 200_000
    }
}
