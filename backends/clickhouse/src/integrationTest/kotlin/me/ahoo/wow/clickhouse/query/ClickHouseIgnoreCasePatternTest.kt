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
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.error.QueryError
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test

/**
 * A text is bound as a query parameter, which ClickHouse reads from an HTTP form field of at most
 * `http_max_field_value_size` (128 KiB by default), so a long operand, or the folding pattern of an ignore-case match
 * (four characters a letter, `[aA]`), was refused by the server and answered as a storage failure (#7211). A parameter
 * past [ClickHouseFilterCompiler.MAX_PARAMETER_BYTES] is refused as the storage's limit, a query the caller can mend;
 * one at it is answered. A text with an unpaired surrogate, which the client writes as `?`, is refused by admission:
 * as an ignore-case pattern `a?` matched every row.
 */
class ClickHouseIgnoreCasePatternTest {
    private val target: DatasetTarget by lazy {
        val database = ClickHouseContainers.DATABASE
        val table = "ignore_case_bound"
        ClickHouseContainers.execute("DROP TABLE IF EXISTS $database.$table")
        ClickHouseContainers.execute(
            "CREATE TABLE $database.$table (id String, tenant_id String, name String) ENGINE = MergeTree ORDER BY id",
        )
        ClickHouseContainers.execute("INSERT INTO $database.$table VALUES ('r1', 't', 'Ann'), ('r2', 't', 'Bob')")
        val dataset = QueryDataset.of(AggregateType("bound", table)) {
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
        val rows = target.backend.list(QueryAdmission.Trusted.list(query, target.schema())).toList()
        rows.map { it.path("id").stringValue() }
    }

    private fun refusal(filter: FilterExpression): String? =
        (runCatching { ids(filter) }.exceptionOrNull() as QueryError?)?.violations?.single()?.code

    @Test
    fun `a parameter past the bound should be refused as the storage's limit, one at it answered`() {
        val name = QueryField("name")
        val max = ClickHouseFilterCompiler.MAX_PARAMETER_BYTES
        refusal(ContainsFilter(name, "a".repeat(LETTERS), StringComparison.CASE_INSENSITIVE)).assert()
            .isEqualTo(QueryErrorCodes.STORAGE_UNSUPPORTED)
        refusal(StartsWithFilter(name, "a".repeat(LETTERS), StringComparison.CASE_INSENSITIVE)).assert()
            .isEqualTo(QueryErrorCodes.STORAGE_UNSUPPORTED)
        refusal(ContainsFilter(name, "a".repeat(max + 1))).assert().isEqualTo(QueryErrorCodes.STORAGE_UNSUPPORTED)
        refusal(EqualFilter(name, "a".repeat(max + 1))).assert().isEqualTo(QueryErrorCodes.STORAGE_UNSUPPORTED)
        // At the bound the server takes it, as the field holds it: a letter as it is, every other byte as `%XX`, so a
        // two-byte letter takes six and an ignore-case letter's class, `[aA]`, eight. The bound is ClickHouse's own.
        ids(ContainsFilter(name, "a".repeat(max))).assert().isEmpty()
        ids(ContainsFilter(name, "é".repeat(max / 6))).assert().isEmpty()
        refusal(ContainsFilter(name, "é".repeat(max / 6 + 1))).assert().isEqualTo(QueryErrorCodes.STORAGE_UNSUPPORTED)
        ids(ContainsFilter(name, "a".repeat(max / 8), StringComparison.CASE_INSENSITIVE)).assert().isEmpty()
        refusal(ContainsFilter(name, "a".repeat(max / 8 + 1), StringComparison.CASE_INSENSITIVE)).assert()
            .isEqualTo(QueryErrorCodes.STORAGE_UNSUPPORTED)
        ids(ContainsFilter(name, "aN", StringComparison.CASE_INSENSITIVE)).assert().containsExactly("r1")
    }

    @Test
    fun `a text with an unpaired surrogate should be refused, never matched as a question mark`() {
        val name = QueryField("name")
        listOf(
            ContainsFilter(name, "a\uD800", StringComparison.CASE_INSENSITIVE),
            ContainsFilter(name, "\uD800b", StringComparison.CASE_INSENSITIVE),
            EqualFilter(name, "a\uD800"),
        ).forEach { filter -> refusal(filter).assert().isEqualTo(QueryErrorCodes.VALUE_MISMATCH) }
    }

    private companion object {
        const val LETTERS = 200_000
    }
}
