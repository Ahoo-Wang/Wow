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
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.policy.AbacQueryOptions
import me.ahoo.wow.query.policy.AbacQueryPolicy
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.dataset.DefaultDatasetQueryGateway
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryValueKind
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** ABAC (decision D129) over a table: a single-valued tag in a string column and a multi-valued one in an `Array(String)`. */
class ClickHouseDatasetAbacTest {
    private val database = ClickHouseContainers.DATABASE
    private val depts = QueryField("depts")

    @BeforeEach
    fun setup() {
        ClickHouseContainers.execute("DROP TABLE IF EXISTS $database.tagged")
        ClickHouseContainers.execute(
            "CREATE TABLE $database.tagged (id String, tenant_id String, region LowCardinality(String), " +
                "depts Array(String)) ENGINE = MergeTree ORDER BY id",
        )
        ClickHouseContainers.execute(
            "INSERT INTO $database.tagged VALUES ('r1', 't1', 'east', ['eng']), ('r2', 't1', 'west', ['sales']), " +
                "('r3', 't1', '', []), ('r4', 't1', 'east', ['eng', 'sales']), ('r5', 't1', '', ['ops'])",
        )
    }

    @Test
    fun `an untagged record should be public and a tagged one should need a value the caller holds`(): Unit = runBlocking {
        ids(mapOf("region" to listOf("east"), "dept" to listOf("eng"))).assert().containsExactly("r1", "r3", "r4")
    }

    @Test
    fun `without matchMissingTagKey every record should carry each tag`(): Unit = runBlocking {
        val strict = AbacQueryOptions(enabled = true, matchMissingTagKey = false)
        ids(mapOf("region" to listOf("east"), "dept" to listOf("eng")), strict).assert().containsExactly("r1", "r4")
        ids(mapOf("region" to listOf("east"), "role" to listOf("admin")), strict).assert().isEmpty()
    }

    @Test
    fun `a wildcard should admit every record that carries the tag`(): Unit = runBlocking {
        ids(mapOf("region" to listOf("*"), "dept" to listOf("*"))).assert().containsExactly("r1", "r2", "r4")
        ids(mapOf("dept" to listOf("sales", "ops"))).assert().containsExactly("r2", "r3", "r4", "r5")
    }

    @Test
    fun `the tag columns should be fields a caller filters by element`(): Unit = runBlocking {
        ids(emptyMap(), filter = InFilter(depts, listOf("sales"))).assert().containsExactly("r2", "r4")
        ids(emptyMap(), filter = IsEmptyFilter(depts)).assert().containsExactly("r3")
        ids(emptyMap(), filter = ContainsAllFilter(depts, listOf("eng", "sales"))).assert().containsExactly("r4")
        ids(emptyMap(), filter = NotEqualFilter(depts, "eng")).assert().containsExactly("r2", "r3", "r5")
    }

    private suspend fun ids(
        tags: Map<String, List<String>>,
        options: AbacQueryOptions = AbacQueryOptions(enabled = true),
        filter: FilterExpression = MatchAllFilter,
    ): List<String> {
        val dataset = dataset()
        val target = DatasetTarget(dataset, ClickHouseDatasetBindings(ClickHouseContainers.client))
        val gateway = DefaultDatasetQueryGateway(
            dataset,
            target.backend,
            target.catalog.provider(dataset.name, QueryModel.DATASET),
            policies = listOf(AbacQueryPolicy(options)),
        )
        val caller = QueryCaller(Caller(attributes = tags.mapKeys { (key, _) -> "abac.$key" }), entry = QueryEntry.HTTP)
        return gateway.list(ListQuery(filter, sort = listOf(Sort(QueryField("id"), Sort.Direction.ASC)), limit = 100), caller)
            .toList().map { it.path("id").stringValue() }
    }

    private fun dataset(): QueryDataset = QueryDataset.of(AggregateType("tck", "tagged")) {
        tenant("tenant_id")
        identity("id")
        tag("region", "region")
        tag("dept", "depts")
        storage(WowJson.mapper.createObjectNode().put("table", "$database.tagged"))
        listOf("id", "tenant_id", "region").forEach { name ->
            field(name) {
                types(QueryValueType.STRING)
                nullable(false)
            }
        }
        field("depts") {
            kind(QueryValueKind.ARRAY)
            nullable(false)
            items { types(QueryValueType.STRING) }
        }
    }
}
