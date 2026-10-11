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
import me.ahoo.wow.api.abac.AbacTags
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.dataset.DefaultDatasetQueryGateway
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.schema.QueryDataset
import me.ahoo.wow.query.snapshot.filter.AbacQueryOptions
import me.ahoo.wow.query.snapshot.filter.AbacQueryPolicy
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.tck.query.target
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.util.context.ContextView

/** ABAC over a wide table: a single-valued tag in a string column and a multi-valued one in an `Array(String)`. */
class ClickHouseDatasetAbacTest {
    private val database = ClickHouseContainers.DATABASE

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
    fun `an untagged record is public and a tagged one needs a value the principal holds`() {
        ids(mapOf("region" to listOf("east"), "dept" to listOf("eng"))).assert().containsExactly("r1", "r3", "r4")
    }

    @Test
    fun `without matchMissingTagKey every record must carry each tag`() {
        val strict = AbacQueryOptions(matchMissingTagKey = false)
        ids(mapOf("region" to listOf("east"), "dept" to listOf("eng")), strict).assert().containsExactly("r1", "r4")
        ids(mapOf("region" to listOf("east"), "role" to listOf("admin")), strict).assert().isEmpty()
    }

    @Test
    fun `a wildcard admits every record that carries the tag`() {
        ids(mapOf("region" to listOf("*"), "dept" to listOf("*"))).assert().containsExactly("r1", "r2", "r4")
        ids(mapOf("dept" to listOf("sales", "ops"))).assert().containsExactly("r2", "r3", "r4", "r5")
    }

    @Test
    fun `the tag columns are fields a caller filters by element`() {
        ids(emptyMap()) { "depts" isIn listOf("sales") }.assert().containsExactly("r2", "r4")
        ids(emptyMap()) { "depts".isEmptyCollection() }.assert().containsExactly("r3")
        ids(emptyMap()) { "depts" containsAll listOf("eng", "sales") }.assert().containsExactly("r4")
        ids(emptyMap()) { "depts" ne "eng" }.assert().containsExactly("r2", "r3", "r5")
    }

    private fun ids(
        tags: AbacTags,
        options: AbacQueryOptions = AbacQueryOptions.DEFAULT,
        filter: me.ahoo.wow.query.dsl.FilterDsl.() -> Unit = { matchAll() },
    ): List<String> {
        val dataset = dataset()
        val target = ClickHouseDatasetQueryBackendFactory(ClickHouseExecutor(ClickHouseContainers.client))
            .target(dataset)
        val policy = object : AbacQueryPolicy(options) {
            override fun getPrincipalTags(contextView: ContextView, context: QueryContext<*>): Mono<AbacTags> =
                Mono.just(tags)
        }
        val gateway = DefaultDatasetQueryGateway(dataset, target.backend, target.schemaProvider, policies = listOf(policy))
        return gateway.dynamicList(
            listQuery {
                filter { filter() }
                sort { "id".asc() }
            },
        ).map { it.path("id").asString() }.collectList().block()!!
    }

    private fun dataset(): QueryDataset = QueryDataset.of(MaterializedNamedAggregate("tck", "tagged")) {
        tenant("tenant_id")
        identity("id")
        tag("region", "region")
        tag("dept", "depts")
        storage(JsonSerializer.createObjectNode().put("table", "$database.tagged"))
        field("id") {
            types(QueryValueType.STRING)
            nullable(false)
        }
        field("tenant_id") {
            types(QueryValueType.STRING)
            nullable(false)
        }
        field("region") {
            types(QueryValueType.STRING)
            nullable(false)
        }
        field("depts") {
            kind(QueryValueKind.ARRAY)
            nullable(false)
            items { types(QueryValueType.STRING) }
        }
    }
}
