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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The exact binding (ClickHouseFilterBindingTest) keeps the sorting key's index for an ordinary operand: a lookup by a
 * UUID identity, an equality with an Enum label, an Int256 against an integer key, a Decimal in its column's own type
 * and a DateTime64 of more digits than its key all read fewer granules than the table has (`EXPLAIN indexes = 1`).
 */
class ClickHouseFilterPruningTest {
    private val executor = ClickHouseExecutor(ClickHouseContainers.client)

    /**
     * A table of 10,000 rows sorted by `k` ([type], [values] its SQL over `number`), 8 rows a granule: 1,250 granules.
     * Its identity is `k` when [keyed], else a string `id`.
     */
    private fun target(
        table: String,
        type: String,
        values: String,
        valueType: QueryValueType,
        date: Boolean = false,
        keyed: Boolean = false,
    ) = run {
        val database = ClickHouseContainers.DATABASE
        ClickHouseContainers.execute("DROP TABLE IF EXISTS $database.$table")
        ClickHouseContainers.execute(
            "CREATE TABLE $database.$table (id String, k $type, tenant_id String) ENGINE = MergeTree ORDER BY k " +
                "SETTINGS index_granularity = 8",
        )
        ClickHouseContainers.execute(
            "INSERT INTO $database.$table SELECT toString(number), $values, 't1' FROM numbers(10000)",
        )
        val dataset = QueryDataset.of(AggregateType("pruning", table)) {
            tenant("tenant_id")
            identity(if (keyed) "k" else "id")
            storage(WowJson.mapper.createObjectNode().put("table", "$database.$table"))
            listOf("id", "tenant_id").forEach {
                field(it) {
                    types(QueryValueType.STRING)
                    nullable(false)
                }
            }
            field("k") {
                types(valueType)
                nullable(false)
                if (date) semantic(Temporal.Date)
            }
        }
        table to DatasetTarget(dataset, ClickHouseDatasetBindings(ClickHouseContainers.client))
    }

    /** The granules [filter] reads of [target]'s 1,250, as the primary key's index selects them. */
    private suspend fun Pair<String, DatasetTarget>.granules(filter: FilterExpression): Int {
        val (table, target) = this
        val admitted = QueryAdmission.Trusted.count(CountQuery(filter), target.schema())
        val typed = executor.table(ClickHouseContainers.DATABASE, table)
        val parameters = SqlParameters()
        val where = ClickHouseFilterCompiler(admitted, typed, parameters).compile(admitted.query.filter)
        val plan = executor.all(
            parameters.statement("EXPLAIN indexes = 1 SELECT count() FROM ${typed.qualifiedName} WHERE $where"),
        ).map { it.path("explain").stringValue().trim() }
        val selected = plan.dropWhile { it != "PrimaryKey" }.first { it.startsWith("Granules:") }
        return selected.removePrefix("Granules:").trim().substringBefore('/').toInt()
    }

    private val k = QueryField("k")

    @Test
    fun `a lookup by a UUID identity, one or several, reads its granules alone`(): Unit = runBlocking {
        val target = target("pruning_uuid", "UUID", "generateUUIDv4(number)", QueryValueType.STRING, keyed = true)
        val ids = executor.all(
            ClickHouseStatement("SELECT toString(k) AS k FROM ${ClickHouseContainers.DATABASE}.pruning_uuid LIMIT 2"),
        ).map { it.path("k").stringValue() }
        target.granules(IdFilter(ids[0])).assert().isLessThanOrEqualTo(2)
        target.granules(EqualFilter(k, ids[0])).assert().isLessThanOrEqualTo(2)
        target.granules(InFilter(k, ids)).assert().isLessThanOrEqualTo(4)
    }

    @Test
    fun `an equality with an Enum label reads its label's granules alone`(): Unit = runBlocking {
        val target = target(
            "pruning_enum",
            "Enum8('Zebra' = 1, 'Alpha' = 2)",
            "if(number < 1000, 'Zebra', 'Alpha')",
            QueryValueType.STRING,
        )
        target.granules(EqualFilter(k, "Zebra")).assert().isLessThan(200)
    }

    @Test
    fun `an Int256 against an integer key, a Decimal in its own type and a finer DateTime64 keep the index`(): Unit =
        runBlocking {
            val integers = target("pruning_int", "Int64", "number", QueryValueType.INTEGER)
            integers.granules(EqualFilter(k, 5000)).assert().isLessThanOrEqualTo(2)
            integers.granules(LessThanFilter(k, 800)).assert().isLessThanOrEqualTo(101)
            val decimals = target("pruning_decimal", "Decimal(18, 2)", "number / 100", QueryValueType.DECIMAL)
            decimals.granules(EqualFilter(k, BigDecimal("50.25"))).assert().isLessThanOrEqualTo(2)
            val instants = target(
                "pruning_instant",
                "DateTime64(3, 'UTC')",
                "toDateTime64('2026-01-01 00:00:00', 3, 'UTC') + number",
                QueryValueType.STRING,
                date = true,
            )
            instants.granules(LessThanFilter(k, "2026-01-01T00:13:20.000000100Z")).assert().isLessThanOrEqualTo(101)
        }
}
