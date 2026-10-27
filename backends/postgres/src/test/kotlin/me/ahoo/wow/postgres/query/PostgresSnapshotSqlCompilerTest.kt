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

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.CursorPosition
import me.ahoo.wow.query.backend.GroupWindow
import me.ahoo.wow.query.backend.PageWindow
import me.ahoo.wow.query.error.QueryExecutionException
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.schema.QueryViolation
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PostgresSnapshotSqlCompilerTest {
    private val schema = QueryTestSchemas.SCHEMA
    private val compiler = PostgresSnapshotSqlCompiler(QueryTestSchemas.TABLE)
    private val aggregations = PostgresAggregationCompiler(QueryTestSchemas.TABLE)

    private fun list(filter: FilterExpression, sort: List<Sort> = emptyList(), limit: Int? = 10, projection: Projection = Projection.ALL) =
        compiler.list(
            QueryAdmission.Trusted.list(ListQuery(filter, projection = projection, sort = sort, limit = limit), schema)
        )

    @Test
    fun `a list restricts to the aggregate, binds every value and orders totally`() {
        val statement = list(
            AndFilter(listOf(TenantIdFilter("t"), EqualFilter(NAME, "Wow"))),
            listOf(Sort(AMOUNT, Sort.Direction.DESC)),
        )
        statement.sql.assert().isEqualTo(
            "SELECT tenant_id, aggregate_id, version, owner_id, space_id, deleted, tags, first_event_time, last_event_time, operator, " +
                "first_operator, schedules, state " +
                "FROM \"wow_snapshot__shop__order\" WHERE (tenant_id = ? AND " +
                "(((CASE WHEN jsonb_typeof((state #> '{name}')) = 'string' THEN state #>> '{name}' END) COLLATE \"C\") = ?)) " +
                "ORDER BY (CASE WHEN jsonb_typeof((state #> '{amount}')) = 'number' THEN (state #>> '{amount}')::numeric END) " +
                "DESC NULLS LAST, aggregate_id ASC, tenant_id ASC LIMIT ?",
        )
        statement.parameters.assert().containsExactly(
            SqlParameter.Text("t"),
            SqlParameter.Text("Wow"),
            SqlParameter.Int8(10),
        )
    }

    @Test
    fun `values never reach the SQL text`() {
        val injection = "x'); DROP TABLE \"wow_snapshot__shop__order\"; --"
        val statements = listOf(
            list(EqualFilter(NAME, injection)),
            list(ContainsFilter(NAME, injection, StringComparison.CASE_INSENSITIVE)),
            list(InFilter(KEYWORDS, listOf(injection))),
            list(ContainsAllFilter(KEYWORDS, listOf(injection))),
            list(StartsWithFilter(KEYWORDS, injection)),
            list(OwnerIdFilter(injection)),
            list(IdsFilter(listOf(injection))),
            list(AggregateIdFilter(injection)),
        )
        statements.forEach { statement ->
            statement.sql.assert().doesNotContain("DROP")
            statement.sql.count { it == '?' }.assert().isEqualTo(statement.parameters.size)
            statement.parameters.toString().assert().contains("DROP")
        }
        val aggregation = aggregations.compile(
            QueryAdmission.Trusted.aggregate(
                AggregationQuery(
                    filter = EqualFilter(NAME, injection),
                    groupBy = listOf(AggregationGroup.Terms(NAME, "drop_table", missingKey = injection)),
                    metrics = listOf(AggregationMetric.Count("count")),
                ),
                schema,
            ),
            GroupWindow.First(10),
        )
        aggregation.sql.assert().doesNotContain("DROP").doesNotContain("drop_table")
        aggregation.sql.count { it == '?' }.assert().isEqualTo(aggregation.parameters.size)
    }

    @Test
    fun `a literal match escapes the LIKE wildcards`() {
        val statement = list(ContainsFilter(NAME, "50%_off\\", StringComparison.CASE_INSENSITIVE))
        statement.sql.assert().contains("lower(((CASE").contains("COLLATE \"default\") LIKE lower(?) ESCAPE '\\'")
        statement.parameters.assert().contains(SqlParameter.Text("%50\\%\\_off\\\\%"))
    }

    @Test
    fun `a multi-valued field compares its jsonb node`() {
        list(InFilter(KEYWORDS, listOf("a", "b"))).let { statement ->
            statement.sql.assert().contains("((state #> '{keywords}') @> ANY(?::jsonb[]))")
            statement.parameters.assert().contains(SqlParameter.Array("text", listOf("\"a\"", "\"b\"")))
        }
        list(NotInFilter(KEYWORDS, listOf("a"))).sql.assert().contains(") IS NOT TRUE")
        list(StartsWithFilter(KEYWORDS, "a")).sql.assert()
            .contains(
                "EXISTS (SELECT 1 FROM jsonb_array_elements((CASE WHEN jsonb_typeof((state #> '{keywords}')) = 'array'"
            )
    }

    @Test
    fun `an operand of another type binds as a typed null`() {
        val nodes = PostgresFilterCompiler.nodes
        PostgresFilterCompiler.scalarParameter(
            PostgresQueryType.NUMERIC,
            nodes.stringNode("2")
        ).assert().isEqualTo(SqlParameter.Numeric(null))
        PostgresFilterCompiler.scalarParameter(
            PostgresQueryType.BOOLEAN,
            nodes.numberNode(1)
        ).assert().isEqualTo(SqlParameter.Bool(null))
        PostgresFilterCompiler.scalarParameter(
            PostgresQueryType.TEXT,
            nodes.numberNode(1)
        ).assert().isEqualTo(SqlParameter.Text(null))
        PostgresFilterCompiler.scalarParameter(PostgresQueryType.BIGINT, nodes.numberNode(BigDecimal("2.5"))).assert()
            .isEqualTo(SqlParameter.Numeric(BigDecimal("2.5")))
        PostgresFilterCompiler.scalarParameter(
            PostgresQueryType.INTEGER,
            nodes.numberNode(7)
        ).assert().isEqualTo(SqlParameter.Int8(7))
        assertThrownBy<QueryExecutionException> {
            PostgresFilterCompiler.scalarParameter(PostgresQueryType.JSONB, nodes.nullNode())
        }
        list(EqualFilter(QUANTITY, 2)).parameters.assert().contains(SqlParameter.Numeric(BigDecimal.valueOf(2)))
        list(EqualFilter(FLAG, true)).parameters.assert().contains(SqlParameter.Bool(true))
    }

    @Test
    fun `system columns bind integral values as int8`() {
        list(EqualFilter(QueryField("version"), 3)).let { statement ->
            statement.sql.assert().contains("(version = ?)")
            statement.parameters.assert().contains(SqlParameter.Int8(3))
        }
        list(InFilter(QueryField("lastEventTime"), listOf(1, 2))).parameters.assert()
            .contains(SqlParameter.Array("numeric", listOf(BigDecimal.ONE, BigDecimal.valueOf(2))))
        list(DeletionFilter(DeletionState.DELETED)).sql.assert().contains("WHERE deleted ORDER BY")
        list(DeletionFilter(DeletionState.ALL)).sql.assert().contains("WHERE TRUE ORDER BY")
    }

    @Test
    fun `a projection without the state does not read it`() {
        list(
            MatchAllFilter,
            projection = Projection(include = listOf(QueryField("aggregateId")))
        ).sql.assert().doesNotContain(", state ")
        list(
            MatchAllFilter,
            projection = Projection(exclude = listOf(QueryField("state")))
        ).sql.assert().doesNotContain(", state ")
        list(MatchAllFilter, projection = Projection(include = listOf(NAME))).sql.assert().contains(", state ")
        list(MatchAllFilter, limit = null).sql.assert().doesNotContain("LIMIT")
    }

    @Test
    fun `a count is bounded by a limit subquery`() {
        val admitted = QueryAdmission.Trusted.count(CountQuery(MatchAllFilter, 5), schema)
        compiler.count(admitted, admitted.query.filter, 6).let { statement ->
            statement.sql.assert().isEqualTo(
                "SELECT count(*) FROM (SELECT 1 FROM \"wow_snapshot__shop__order\" WHERE TRUE LIMIT ?) AS bounded",
            )
            statement.parameters.assert().containsExactly(SqlParameter.Int8(6))
        }
        compiler.count(admitted, admitted.query.filter, null).sql.assert()
            .isEqualTo("SELECT count(*) FROM \"wow_snapshot__shop__order\" WHERE TRUE")
    }

    @Test
    fun `an offset page binds its window`() {
        val admitted = QueryAdmission.Trusted.paged(PagedQuery(MatchAllFilter, pagination = Pagination(3, 20)), schema)
        val statement = compiler.page(admitted, PageWindow.Offset(40, 20, withTotal = false))
        statement.sql.assert().endsWith("LIMIT ? OFFSET ?")
        statement.parameters.takeLast(2).assert().containsExactly(SqlParameter.Int8(20), SqlParameter.Int8(40))
    }

    @Test
    fun `a keyset page reads the rows strictly after the position`() {
        val sort = listOf(Sort(NAME, Sort.Direction.ASC), Sort(AMOUNT, Sort.Direction.DESC))
        val admitted = QueryAdmission.Trusted.cursor(CursorQuery(MatchAllFilter, sort = sort, size = 2), schema)
        val name = PostgresSnapshotQueryExpressions.value(NAME, PostgresQueryType.TEXT)
        val amount = PostgresSnapshotQueryExpressions.value(AMOUNT, PostgresQueryType.NUMERIC)

        compiler.keyset(
            admitted,
            // The snapshot's unique key ends the sort: the tenant, then the aggregate id.
            PageWindow.Keyset(CursorPosition(listOf("n", BigDecimal.TEN, "t", "id")), 3)
        ).let { statement ->
            statement.sql.assert().contains(
                "AND (($name > ?) OR ($name = ? AND ($amount < ? OR $amount IS NULL)) OR " +
                    "($name = ? AND $amount = ? AND tenant_id > ?) OR " +
                    "($name = ? AND $amount = ? AND tenant_id = ? AND aggregate_id > ?)) ORDER BY",
            )
            statement.sql.assert().contains("$name AS k0, $amount AS k1, tenant_id AS k2, aggregate_id AS k3")
                .endsWith("tenant_id ASC NULLS FIRST, aggregate_id ASC NULLS FIRST LIMIT ?")
            statement.parameters.assert().containsExactly(
                SqlParameter.Text("n"),
                SqlParameter.Text("n"),
                SqlParameter.Numeric(BigDecimal.TEN),
                SqlParameter.Text("n"),
                SqlParameter.Numeric(BigDecimal.TEN),
                SqlParameter.Text("t"),
                SqlParameter.Text("n"),
                SqlParameter.Numeric(BigDecimal.TEN),
                SqlParameter.Text("t"),
                SqlParameter.Text("id"),
                SqlParameter.Int8(3),
            )
        }
        // Nothing sorts after a NULL descending; after a NULL ascending, every value does.
        compiler.keyset(admitted, PageWindow.Keyset(CursorPosition(listOf(null, null, "t", "id")), 3)).sql.assert()
            .contains(
                "AND (($name IS NOT NULL) OR ($name IS NULL AND $amount IS NULL AND tenant_id > ?) OR " +
                    "($name IS NULL AND $amount IS NULL AND tenant_id = ? AND aggregate_id > ?)) ORDER BY",
            )
        compiler.keyset(admitted, PageWindow.Keyset(null, 3)).sql.assert().doesNotContain(" OR ")
    }

    @Test
    fun `a position of another type is the invalid cursor`() {
        val sort = listOf(Sort(AMOUNT, Sort.Direction.ASC))
        val admitted = QueryAdmission.Trusted.cursor(CursorQuery(MatchAllFilter, sort = sort, size = 2), schema)
        runCatching { compiler.keyset(admitted, PageWindow.Keyset(CursorPosition(listOf("ten", "id")), 3)) }
            .exceptionOrNull().let { (it as QueryRequestException).violation }.assert().isEqualTo(
                QueryViolation.InvalidCursor
            )
        PostgresSnapshotSqlCompiler.position(PostgresQueryType.BIGINT, BigDecimal("2.5")).assert()
            .isEqualTo(SqlParameter.Numeric(BigDecimal("2.5")))
        PostgresSnapshotSqlCompiler.position(
            PostgresQueryType.INTEGER,
            BigDecimal("2")
        ).assert().isEqualTo(SqlParameter.Int8(2))
        PostgresSnapshotSqlCompiler.position(
            PostgresQueryType.BOOLEAN,
            true
        ).assert().isEqualTo(SqlParameter.Bool(true))
    }

    @Test
    fun `an aggregation layers groups, derived metrics and HAVING`() {
        val query = AggregationQuery(
            filter = MatchAllFilter,
            groupBy = listOf(
                AggregationGroup.DateHistogram(CREATED, "day", me.ahoo.wow.api.query.AggregationDateUnit.DAY, "+08:00")
            ),
            metrics = listOf(
                AggregationMetric.Count("count"),
                AggregationMetric.Derived(
                    "double",
                    me.ahoo.wow.api.query.DerivedExpression.Binary(
                        me.ahoo.wow.api.query.AggregationExpressionOperator.MULTIPLY,
                        me.ahoo.wow.api.query.DerivedExpression.MetricRef("count"),
                        me.ahoo.wow.api.query.DerivedExpression.Constant(2.0),
                    ),
                ),
            ),
            having = HavingExpression.Condition("double", ComparisonOperator.GT, 1.0),
            limit = 7,
        )
        val statement = aggregations.compile(QueryAdmission.Trusted.aggregate(query, schema), GroupWindow.First(7))
        val created = PostgresSnapshotQueryExpressions.value(CREATED, PostgresQueryType.NUMERIC)
        statement.sql.assert().isEqualTo(
            "SELECT * FROM (SELECT a.*, (a.m0 * ?::numeric) AS m1 FROM (SELECT " +
                "(extract(epoch from (date_trunc('day', to_timestamp($created / 1000.0) AT TIME ZONE ?::interval) " +
                "AT TIME ZONE ?::interval)) * 1000)::bigint AS g0, count(*) AS m0 FROM \"wow_snapshot__shop__order\" AS t " +
                "WHERE TRUE AND $created IS NOT NULL GROUP BY 1) AS a) AS h " +
                "WHERE h.m1 > ? ORDER BY h.g0 ASC NULLS FIRST LIMIT ?",
        )
        statement.parameters.assert().containsExactly(
            SqlParameter.Numeric(BigDecimal.valueOf(2.0)),
            SqlParameter.Text("28800 seconds"),
            SqlParameter.Text("28800 seconds"),
            SqlParameter.Numeric(BigDecimal.valueOf(1.0)),
            SqlParameter.Int8(7),
        )
    }

    @Test
    fun `a time zone binds an offset as an interval and a region as its ID`() {
        SqlParameters().let { parameters ->
            PostgresAggregationCompiler.zone(parameters, "UTC").assert().isEqualTo("?::interval")
            PostgresAggregationCompiler.zone(parameters, "Asia/Shanghai").assert().isEqualTo("?::text")
            parameters.values.assert().containsExactly(
                SqlParameter.Text("0 seconds"),
                SqlParameter.Text("Asia/Shanghai")
            )
        }
        assertThrownBy<QueryExecutionException> { PostgresAggregationCompiler.zone(SqlParameters(), "Mars/Olympus") }
    }

    private companion object {
        val NAME = QueryField("state.name")
        val AMOUNT = QueryField("state.amount")
        val QUANTITY = QueryField("state.quantity")
        val FLAG = QueryField("state.flag")
        val CREATED = QueryField("state.createdAt")
        val KEYWORDS = QueryField("state.keywords")
    }
}
