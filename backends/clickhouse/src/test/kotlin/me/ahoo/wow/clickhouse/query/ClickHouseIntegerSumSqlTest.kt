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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.clickhouse.sink.ClickHouseColumn
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.GroupWindow
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test

/** Which integer columns' SUM and AVG accumulate wide, and which stay native under a bound on a group's rows. */
class ClickHouseIntegerSumSqlTest {
    @Test
    fun `a column of 32 bits or fewer should sum natively under the bound on a group's rows`() {
        compile("i32", AggregationFunction.SUM).assert().isEqualTo(
            "SELECT __g0 AS `id`, __m0 AS `total` FROM (SELECT `id` AS __g0, sumOrNull(`i32`) AS __m0, " +
                "count() AS __rows FROM `bi`.`numbers` WHERE 1 GROUP BY __g0) WHERE throwIf(__rows > 4294967296, " +
                "'A group holds more rows than its integer sum is exact for.') = 0 ORDER BY `id` ASC NULLS FIRST",
        )
        compile("u8", AggregationFunction.AVG).assert().contains("avgOrNull(`u8`) AS __m0", "count() AS __rows")
    }

    @Test
    fun `a wider column should accumulate in twice its bits and a 256-bit one in its own`() {
        compile("i64", AggregationFunction.SUM).assert().contains("sumOrNull(toInt128(`i64`)) AS __m0")
            .doesNotContain("__rows")
        compile("u128", AggregationFunction.AVG).assert().contains("avgOrNull(toInt256(`u128`)) AS __m0")
        compile("i256", AggregationFunction.SUM).assert().contains("sumOrNull(`i256`) AS __m0")
    }

    @Test
    fun `MIN of a narrow column should need no bound`() {
        compile("i32", AggregationFunction.MIN).assert().contains("minOrNull(`i32`) AS __m0").doesNotContain("__rows")
    }

    private fun compile(field: String, function: AggregationFunction): String {
        val query = AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(QueryField("id"), "id")),
            metrics = listOf(
                AggregationMetric.Numeric(function, AggregationExpression.Field(QueryField(field)), "total"),
            ),
        )
        val admitted = QueryAdmission.Trusted.aggregate(query, SCHEMA)
        return ClickHouseAggregationCompiler(admitted, TABLE, TABLE.qualifiedName).compile(GroupWindow.All).sql
    }

    private companion object {
        val INTEGERS = mapOf(
            "u8" to "UInt8",
            "i32" to "Int32",
            "i64" to "Int64",
            "u128" to "UInt128",
            "i256" to "Int256",
        )

        val TABLE = ClickHouseTable(
            "bi",
            "numbers",
            "MergeTree",
            listOf(ClickHouseColumn("id", "String"), ClickHouseColumn("tenant_id", "String")) +
                INTEGERS.map { (name, type) -> ClickHouseColumn(name, type) },
        )

        val DATASET: QueryDataset = QueryDataset.of(AggregateType("bi", "numbers")) {
            tenant("tenant_id")
            identity("id")
            storage(WowJson.mapper.createObjectNode().put("table", "bi.numbers"))
            listOf("id", "tenant_id").forEach {
                field(it) {
                    types(QueryValueType.STRING)
                    nullable(false)
                }
            }
            INTEGERS.keys.forEach {
                field(it) {
                    types(QueryValueType.INTEGER)
                    nullable(false)
                }
            }
        }

        val SCHEMA by lazy {
            ClickHouseQueryFixtures.schema(
                ClickHouseQueryFixtures.adapter(DATASET, ClickHouseDatasetStorage("bi", "numbers")) { TABLE },
                DATASET,
            )
        }
    }
}
