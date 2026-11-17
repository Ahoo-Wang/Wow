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

package me.ahoo.wow.clickhouse.query

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.cursor
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * A filter compares a field as the type its dataset declares, whatever its column's native type: an operand keeps every
 * digit it was given, a date's below the millisecond (#6304) and a number's past the column's width or scale (#6321),
 * and a STRING field over an Enum (#6330) or a UUID (#6337) is matched, compared and ordered as its text.
 */
class ClickHouseFilterBindingTest {
    private val id = QueryField("id")
    private val ascending = listOf(Sort(id, Sort.Direction.ASC))

    /** A dataset over [table] (`id`, `tenant_id`, then [columns]), the rows [values], each field in [fields]. */
    private fun target(
        table: String,
        columns: String,
        values: String,
        fields: Map<String, QueryValueType>,
        dates: Set<String> = emptySet(),
    ): DatasetTarget {
        val database = ClickHouseContainers.DATABASE
        ClickHouseContainers.execute("DROP TABLE IF EXISTS $database.$table")
        ClickHouseContainers.execute(
            "CREATE TABLE $database.$table (id String, tenant_id String, $columns) ENGINE = MergeTree ORDER BY id",
        )
        ClickHouseContainers.execute("INSERT INTO $database.$table VALUES $values")
        val dataset = QueryDataset.of(AggregateType("binding", table)) {
            tenant("tenant_id")
            identity("id")
            storage(WowJson.mapper.createObjectNode().put("table", "$database.$table"))
            (mapOf("id" to QueryValueType.STRING, "tenant_id" to QueryValueType.STRING) + fields).forEach { (name, type) ->
                field(name) {
                    types(type)
                    nullable(false)
                    if (name in dates) semantic(Temporal.Date)
                }
            }
        }
        return DatasetTarget(dataset, ClickHouseDatasetBindings(ClickHouseContainers.client))
    }

    private suspend fun DatasetTarget.ids(filter: FilterExpression, sort: List<Sort> = ascending): List<String> {
        val query = ListQuery(filter, sort = sort, limit = 10)
        return backend.list(QueryAdmission.Trusted.list(query, schema())).toList().map { it.path("id").stringValue() }
    }

    private val a = "2026-01-01T00:00:00.000100Z"
    private val b = "2026-01-01T00:00:00.000200Z"

    private fun dates(table: String) = target(
        table,
        "created_at DateTime64(6, 'UTC')",
        "('a', 't1', '2026-01-01 00:00:00.000100'), ('b', 't1', '2026-01-01 00:00:00.000200'), " +
            "('c', 't1', '2026-01-01 00:00:00.001000')",
        mapOf("created_at" to QueryValueType.STRING),
        dates = setOf("created_at"),
    )

    @Test
    fun `a date operand keeps its digits below the millisecond`(): Unit = runBlocking {
        val target = dates("binding_dates")
        val time = QueryField("created_at")
        val returned = target.backend.list(
            QueryAdmission.Trusted.list(ListQuery(EqualFilter(id, "b")), target.schema())
        )
            .toList().single().path("created_at").stringValue()
        target.ids(EqualFilter(time, returned)).assert().containsExactly("b")
        target.ids(LessThanFilter(time, b)).assert().containsExactly("a")
        target.ids(GreaterThanFilter(time, a)).assert().containsExactly("b", "c")
        target.ids(BetweenFilter(time, a, b)).assert().containsExactly("a", "b")
        target.ids(EqualFilter(time, "2026-01-01T00:00:00.001Z")).assert().containsExactly("c")
    }

    @Test
    fun `a numeric operand is compared as given, past its column's width and scale`(): Unit = runBlocking {
        val target = target(
            "binding_numbers",
            "u UInt8, i Int8, d Decimal(18, 2)",
            "('a', 't1', 1, -1, 1.25), ('b', 't1', 255, 127, 1.26), ('c', 't1', 0, -128, 1.50)",
            mapOf("u" to QueryValueType.INTEGER, "i" to QueryValueType.INTEGER, "d" to QueryValueType.DECIMAL),
        )
        val u = QueryField("u")
        val d = QueryField("d")
        target.ids(EqualFilter(u, 256)).assert().isEmpty()
        target.ids(LessThanFilter(u, 256)).assert().containsExactly("a", "b", "c")
        target.ids(GreaterThanFilter(u, -1)).assert().containsExactly("a", "b", "c")
        target.ids(LessThanFilter(QueryField("i"), 128)).assert().containsExactly("a", "b", "c")
        target.ids(EqualFilter(d, BigDecimal("1.255"))).assert().isEmpty()
        target.ids(LessThanFilter(d, BigDecimal("1.255"))).assert().containsExactly("a")
        target.ids(EqualFilter(u, 255)).assert().containsExactly("b")
        target.ids(EqualFilter(d, BigDecimal("1.26"))).assert().containsExactly("b")
    }

    @Test
    fun `a STRING field over an Enum is matched and ordered by its label`(): Unit = runBlocking {
        val target = target(
            "binding_enum",
            "status Enum8('Zebra' = 1, 'Alpha' = 2)",
            "('a', 't1', 'Zebra'), ('b', 't1', 'Alpha')",
            mapOf("status" to QueryValueType.STRING),
        )
        val status = QueryField("status")
        val folded = StringComparison.CASE_INSENSITIVE
        target.ids(StartsWithFilter(status, "Ze")).assert().containsExactly("a")
        target.ids(EndsWithFilter(status, "bra")).assert().containsExactly("a")
        target.ids(StartsWithFilter(status, "zE", folded)).assert().containsExactly("a")
        target.ids(EndsWithFilter(status, "bRa", folded)).assert().containsExactly("a")
        target.ids(EqualFilter(status, "Zebra")).assert().containsExactly("a")
        target.ids(EqualFilter(status, "Unknown")).assert().isEmpty()
        target.ids(LessThanFilter(status, "M")).assert().containsExactly("b")
        target.ids(MatchAllFilter, listOf(Sort(status, Sort.Direction.ASC))).assert().containsExactly("b", "a")
    }

    @Test
    fun `a STRING field over a UUID is compared, ordered and paged by its text`(): Unit = runBlocking {
        val first = "00000000-0000-4000-8000-000000000002"
        val last = "ffffffff-ffff-4fff-8000-000000000001"
        val target = target(
            "binding_uuid",
            "uuid UUID",
            "('a', 't1', '$first'), ('b', 't1', '$last')",
            mapOf("uuid" to QueryValueType.STRING),
        )
        val uuid = QueryField("uuid")
        val byUuid = listOf(Sort(uuid, Sort.Direction.ASC))
        target.ids(MatchAllFilter, byUuid).assert().containsExactly("a", "b")
        target.ids(LessThanFilter(uuid, last)).assert().containsExactly("a")
        target.ids(GreaterThanFilter(uuid, first)).assert().containsExactly("b")
        target.ids(EqualFilter(uuid, first)).assert().containsExactly("a")
        target.ids(EqualFilter(uuid, "not a uuid")).assert().isEmpty()
        val schema = target.schema()
        val pages = mutableListOf<String>()
        var cursor: String? = null
        repeat(2) {
            val page = target.backend.cursor(
                QueryAdmission.Trusted.cursor(CursorQuery(sort = byUuid, size = 1, cursor = cursor), schema),
            )
            pages += page.list.map { it.path("id").stringValue() }
            cursor = page.nextCursor
        }
        pages.assert().containsExactly("a", "b")
    }
}
