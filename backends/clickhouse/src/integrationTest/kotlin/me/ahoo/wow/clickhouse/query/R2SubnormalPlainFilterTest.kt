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
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test

/**
 * #7013 made a subnormal double refuse a ClickHouse query (`storage-unsupported`) where it was a constant of an
 * expression, since ClickHouse reads a subnormal double in no form: a literal is a syntax error, and its text `0`.
 * A plain filter's operand over a `Float64` column (the sink's flat columns are `Float64` for every number that is not
 * an integer) does not go through that refusal: it is bound as the double's text, `4.9E-324`, a `Float64` parameter.
 * Every other backend compares it as the double it is: `0.0 < 4.9E-324`, and no value is equal to it here.
 */
class R2SubnormalPlainFilterTest {
    private val id = QueryField("id")
    private val amount = QueryField("amount")

    private fun target(): DatasetTarget {
        val database = ClickHouseContainers.DATABASE
        val table = "r2_subnormal_plain"
        ClickHouseContainers.execute("DROP TABLE IF EXISTS $database.$table")
        ClickHouseContainers.execute(
            "CREATE TABLE $database.$table (id String, tenant_id String, amount Float64) ENGINE = MergeTree ORDER BY id",
        )
        ClickHouseContainers.execute("INSERT INTO $database.$table VALUES ('zero', 't1', 0), ('one', 't1', 1)")
        val dataset = QueryDataset.of(AggregateType("r2", table)) {
            tenant("tenant_id")
            identity("id")
            storage(WowJson.mapper.createObjectNode().put("table", "$database.$table"))
            mapOf(
                "id" to QueryValueType.STRING,
                "tenant_id" to QueryValueType.STRING,
                "amount" to QueryValueType.DECIMAL,
            ).forEach { (name, type) ->
                field(name) {
                    types(type)
                    nullable(false)
                }
            }
        }
        return DatasetTarget(dataset, ClickHouseDatasetBindings(ClickHouseContainers.client))
    }

    /** The ids [filter] keeps, or the refusal's code when the query is refused. */
    private suspend fun DatasetTarget.answer(filter: FilterExpression): Any {
        val query = ListQuery(filter, sort = listOf(Sort(id, Sort.Direction.ASC)), limit = 10)
        return runCatching {
            backend.list(QueryAdmission.Trusted.list(query, schema())).toList().map { it.path("id").stringValue() }
        }.getOrElse { failure ->
            (failure as? QuerySchemaValidationException)?.violation?.code ?: throw failure
        }
    }

    @Test
    fun `a subnormal operand of a plain filter over a Float64 column is compared as itself, or the query refused`(): Unit =
        runBlocking {
            val target = target()
            // Computed or refused, never answered as if the operand were 0.
            val answers = mapOf(
                "lt" to target.answer(LessThanFilter(amount, Double.MIN_VALUE)),
                "eq" to target.answer(EqualFilter(amount, Double.MIN_VALUE)),
            )
            val computed = mapOf("lt" to listOf("zero"), "eq" to emptyList<String>())
            val expected = answers.mapValues { (filter, answer) ->
                if (answer == QueryErrorCodes.STORAGE_UNSUPPORTED) answer else computed.getValue(filter)
            }
            answers.assert().isEqualTo(expected)
        }
}
