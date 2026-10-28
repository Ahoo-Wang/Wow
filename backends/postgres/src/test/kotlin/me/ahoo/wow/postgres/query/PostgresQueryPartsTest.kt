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

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.query.AggregationDatePart
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.postgres.query.PostgresFilterCompiler.Companion.json
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.CursorPosition
import me.ahoo.wow.query.backend.PageWindow
import me.ahoo.wow.query.error.QueryExecutionException
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QueryPathTemplate
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.math.BigInteger
import java.sql.Array
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types

class PostgresQueryPartsTest {
    private val nodes = PostgresFilterCompiler.nodes

    @Test
    fun `every parameter binds its value or a typed null`() {
        val array = mockk<Array>()
        val connection = mockk<Connection> { every { createArrayOf("text", any()) } returns array }
        val statement = mockk<PreparedStatement>(relaxed = true) { every { this@mockk.connection } returns connection }
        val parameters = listOf(
            SqlParameter.Text("a"),
            SqlParameter.Text(null),
            SqlParameter.Numeric(BigDecimal.ONE),
            SqlParameter.Numeric(null),
            SqlParameter.Int8(2),
            SqlParameter.Int8(null),
            SqlParameter.Bool(true),
            SqlParameter.Bool(null),
            SqlParameter.Json("{}"),
            SqlParameter.Array("text", listOf("x")),
        )
        val sql = parameters.joinToString(", ") { "?" }
        every { connection.prepareStatement(sql) } returns statement
        SqlStatement(sql, parameters).also { it.toString().assert().isEqualTo(sql) }.prepare(connection)
        verify {
            statement.setString(1, "a")
            statement.setNull(2, Types.VARCHAR)
            statement.setBigDecimal(3, BigDecimal.ONE)
            statement.setNull(4, Types.NUMERIC)
            statement.setLong(5, 2)
            statement.setNull(6, Types.BIGINT)
            statement.setBoolean(7, true)
            statement.setNull(8, Types.BOOLEAN)
            statement.setString(9, "{}")
            statement.setArray(10, array)
        }
    }

    @Test
    fun `types say whether they are numbers and are required of a binding`() {
        PostgresQueryType.entries.filter { it.numeric }.assert()
            .containsExactly(PostgresQueryType.NUMERIC, PostgresQueryType.INTEGER, PostgresQueryType.BIGINT)
        PostgresQueryType.require(setOf(PostgresQueryType.TEXT.storageType), QueryField("state.a")).assert()
            .isEqualTo(PostgresQueryType.TEXT)
        assertThrownBy<QueryExecutionException> { PostgresQueryType.require(null, QueryField("state.a")) }
        val nullable = QueryValueSchema(
            QueryValueKind.UNION,
            alternatives = listOf(
                QueryValueSchema(QueryValueKind.NULL),
                QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.DECIMAL)),
            ),
        )
        PostgresQueryType.of(nullable).assert().isEqualTo(PostgresQueryType.NUMERIC)
        PostgresQueryType.of(QueryValueSchema(QueryValueKind.NULL)).assert().isEqualTo(PostgresQueryType.JSONB)
        PostgresQueryType.of(
            QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.BOOLEAN, QueryValueType.STRING)),
        ).assert().isEqualTo(PostgresQueryType.JSONB)
        PostgresSnapshotStorageAdapter.capabilitiesOf(nullable, PostgresQueryType.NUMERIC).assert()
            .contains(QueryCapability.RANGE, QueryCapability.AGGREGATE_NUMERIC)
    }

    @Test
    fun `date units and parts name their SQL fields`() {
        AggregationDateUnit.entries.map(PostgresAggregationCompiler::unit).assert()
            .containsExactly("year", "quarter", "month", "week", "day", "hour", "minute", "second")
        AggregationDatePart.entries.associateWith(PostgresAggregationCompiler::part).assert().isEqualTo(
            mapOf(
                AggregationDatePart.DAY_OF_WEEK to "isodow",
                AggregationDatePart.DAY_OF_MONTH to "day",
                AggregationDatePart.HOUR_OF_DAY to "hour",
                AggregationDatePart.MONTH_OF_YEAR to "month",
            ),
        )
        AggregationFunction.entries.map(PostgresAggregationCompiler::function).assert()
            .containsExactlyInAnyOrder("sum", "avg", "min", "max", "stddev_pop", "var_pop")
    }

    @Test
    fun `operands become the JSON a record stores`() {
        null.json().isNull.assert().isTrue()
        nodes.numberNode(1).json().assert().isEqualTo(nodes.numberNode(1))
        "a".json().stringValue().assert().isEqualTo("a")
        PostgresFilterCompiler.scalarParameter(
            PostgresQueryType.INTEGER,
            nodes.numberNode(BigInteger.TEN.pow(30))
        ).assert()
            .isEqualTo(SqlParameter.Numeric(BigDecimal(BigInteger.TEN.pow(30))))
    }

    @Test
    fun `positions of another type than the sort field are refused`() {
        listOf(
            PostgresQueryType.TEXT to BigDecimal.ONE,
            PostgresQueryType.BOOLEAN to "true",
            PostgresQueryType.NUMERIC to "1",
        ).forEach { (type, value) ->
            assertThrownBy<QueryRequestException> { PostgresSnapshotSqlCompiler.position(type, value) }
        }
    }

    @Test
    fun `nothing sorts after a null descending`() {
        // Every field of the effective sort, the snapshot's unique key (tenant, aggregate id) included, descends.
        val sort = listOf(
            Sort(QueryField("aggregateId"), Sort.Direction.DESC),
            Sort(QueryField("tenantId"), Sort.Direction.DESC),
        )
        val admitted = QueryAdmission.Trusted.cursor(
            CursorQuery(MatchAllFilter, sort = sort, size = 1),
            QueryTestSchemas.SCHEMA
        )
        PostgresSnapshotSqlCompiler(QueryTestSchemas.TABLE)
            .keyset(
                admitted,
                PageWindow.Keyset(CursorPosition(listOf(null, null)), 2)
            ).sql.assert().contains("WHERE TRUE AND FALSE ORDER BY")
    }

    @Test
    fun `a projection walks into arrays of objects only`() {
        val record = WowJson.mapper.readTree("""{"lines":[{"sku":"A","n":1},2,{"n":3}],"name":"x"}""") as ObjectNode
        nodes.objectNode().also { PostgresQueryBackend.include(record, it, listOf("lines", "sku")) }.toString()
            .assert().isEqualTo("""{"lines":[{"sku":"A"},{},{}]}""")
        nodes.objectNode().also {
            PostgresQueryBackend.include(
                record,
                it,
                listOf("missing", "sku")
            )
        }.toString()
            .assert().isEqualTo("{}")
        nodes.objectNode().also { PostgresQueryBackend.include(record, it, listOf("name", "sku")) }.toString()
            .assert().isEqualTo("{}")
        record.deepCopy().also { PostgresQueryBackend.exclude(it, listOf("lines", "n")) }.toString()
            .assert().isEqualTo("""{"lines":[{"sku":"A"},2,{}],"name":"x"}""")
        record.deepCopy().also { PostgresQueryBackend.exclude(it, listOf("missing", "n")) }.toString()
            .assert().isEqualTo(record.toString())
    }

    @Test
    fun `a column of another type than this module creates proves only presence`() {
        val logical = QueryTestSchemas.SCHEMA.definition
        val columns = PostgresSnapshotStorageAdapter.COLUMNS + ("version" to "text") + ("state" to "json")
        val facts = PostgresSnapshotStorageAdapter.facts(logical, columns, provisional = true)
        fun capabilities(vararg path: String) =
            facts.bindings.getValue(QueryPathTemplate(path.map(QueryPathSegment::Property))).bindings.keys
        facts.provisional.assert().isTrue()
        capabilities("version").assert().containsExactly(QueryCapability.PRESENCE)
        capabilities("state", "name").assert().containsExactly(QueryCapability.PRESENCE)
        capabilities("ownerId").assert().contains(QueryCapability.EXACT_MATCH, QueryCapability.CURSOR_SORT)
        PostgresSnapshotStorageAdapter.COLUMNS.assert().containsEntry("version", "int4").containsEntry("state", "jsonb")
    }
}
