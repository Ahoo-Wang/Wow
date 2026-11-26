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
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test

/**
 * Round two of the deep review, security (suspicion 3 of #7021): an ignore-case `contains` or `startsWith` is matched
 * with RE2's `match` and the core's folding pattern of its operand, four characters (`[aA]`) a letter, with no bound
 * on its size here, where MongoDB and Elasticsearch refuse a pattern past theirs as storage-unsupported. A
 * 200,000-letter operand fits the 256 KB body the codecs take. The dataset answers no row, or refuses the operand as
 * a query the caller can mend; RE2's refusal of the pattern, answered as a storage failure, is the defect.
 */
class R2IgnoreCasePatternTest {
    private val target: DatasetTarget by lazy {
        val database = ClickHouseContainers.DATABASE
        val table = "r2_ignore_case"
        ClickHouseContainers.execute("DROP TABLE IF EXISTS $database.$table")
        ClickHouseContainers.execute(
            "CREATE TABLE $database.$table (id String, tenant_id String, name String) ENGINE = MergeTree ORDER BY id",
        )
        ClickHouseContainers.execute("INSERT INTO $database.$table VALUES ('r1', 't', 'Ann'), ('r2', 't', 'Bob')")
        val dataset = QueryDataset.of(AggregateType("r2", table)) {
            tenant("tenant_id")
            identity("id")
            storage(WowJson.mapper.createObjectNode().put("table", "$database.$table"))
            listOf("id", "tenant_id", "name").forEach { name ->
                field(name) {
                    types(QueryValueType.STRING)
                    nullable(false)
                }
            }
        }
        DatasetTarget(dataset, ClickHouseDatasetBindings(ClickHouseContainers.client))
    }

    private fun ids(filter: FilterExpression): List<String> = runBlocking {
        val query = ListQuery(filter, limit = 10)
        target.backend.list(QueryAdmission.Trusted.list(query, target.schema())).toList().map { it.path("id").stringValue() }
    }

    private fun answersNoneOrRefuses(filter: FilterExpression) {
        val answer = runCatching { ids(filter) }
        answer.exceptionOrNull()?.let { it.assert().isInstanceOf(QueryRequestException::class.java) }
            ?: answer.getOrThrow().assert().isEmpty()
    }

    @Test
    fun `an ignore-case match of a long operand answers no row or is refused as the caller's to mend`() {
        val name = QueryField("name")
        answersNoneOrRefuses(ContainsFilter(name, "a".repeat(LETTERS), StringComparison.CASE_INSENSITIVE))
        answersNoneOrRefuses(StartsWithFilter(name, "a".repeat(LETTERS), StringComparison.CASE_INSENSITIVE))
    }

    private companion object {
        const val LETTERS = 200_000
    }
}
