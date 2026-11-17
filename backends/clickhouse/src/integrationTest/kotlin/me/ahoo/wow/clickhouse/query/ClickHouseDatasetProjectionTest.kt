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
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.cursor
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.backend.paged
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

/**
 * A record holds exactly the fields its projection asks for: the columns a statement selects for itself (a keyset
 * page's positions, the one column a statement that keeps no field still needs) neither replace nor remove a declared
 * field of the same name (#6290), a projection that keeps no field answers an empty record per match, and an
 * exclusion applies after an inclusion (#6294).
 */
class ClickHouseDatasetProjectionTest {
    private val id = QueryField("id")
    private val amount = QueryField("amount")

    /** A dataset of `id`, `tenant_id`, [field] and `amount` over two rows, `a` and `b`. */
    private fun target(table: String, field: String = "payload", identity: String = "id"): DatasetTarget {
        val database = ClickHouseContainers.DATABASE
        ClickHouseContainers.execute("DROP TABLE IF EXISTS $database.$table")
        ClickHouseContainers.execute(
            "CREATE TABLE $database.$table (id String, tenant_id String, `$field` String, amount Decimal(18, 2)) " +
                "ENGINE = MergeTree ORDER BY id",
        )
        ClickHouseContainers.execute(
            "INSERT INTO $database.$table VALUES ('a', 't1', 'payload-a', 1.25), ('b', 't1', 'payload-b', 2.50)",
        )
        val dataset = QueryDataset.of(AggregateType("projection", table)) {
            tenant("tenant_id")
            identity(identity)
            storage(WowJson.mapper.createObjectNode().put("table", "$database.$table"))
            listOf("id", "tenant_id", field).forEach { name ->
                field(name) {
                    types(QueryValueType.STRING)
                    nullable(false)
                }
            }
            field("amount") {
                types(QueryValueType.DECIMAL)
                nullable(false)
            }
        }
        return DatasetTarget(dataset, ClickHouseDatasetBindings(ClickHouseContainers.client))
    }

    private suspend fun DatasetTarget.cursor(query: CursorQuery) =
        backend.cursor(QueryAdmission.Trusted.cursor(query, schema()))

    private suspend fun DatasetTarget.list(query: ListQuery): List<ObjectNode> =
        backend.list(QueryAdmission.Trusted.list(query, schema())).toList()

    @Test
    fun `a keyset page keeps a declared field named as its first position column`(): Unit = runBlocking {
        val target = target("projection_position_field", field = "__position0")
        val first = target.cursor(CursorQuery(size = 1, sort = listOf(Sort(id, Sort.Direction.ASC))))
        first.list.single().path("__position0").stringValue().assert().isEqualTo("payload-a")
        val next = target.cursor(
            CursorQuery(size = 1, sort = listOf(Sort(id, Sort.Direction.ASC)), cursor = first.nextCursor),
        )
        next.list.single().path("__position0").stringValue().assert().isEqualTo("payload-b")
    }

    @Test
    fun `a keyset page keeps an identity named as its first position column, and pages by it`(): Unit = runBlocking {
        val target = target("projection_position_identity", field = "__position0", identity = "__position0")
        val first = target.cursor(CursorQuery(size = 1))
        first.list.single().path("__position0").stringValue().assert().isEqualTo("payload-a")
        val next = target.cursor(CursorQuery(size = 1, cursor = first.nextCursor))
        next.list.single().path("__position0").stringValue().assert().isEqualTo("payload-b")
    }

    @Test
    fun `a list and a page excluding every field answer one empty record per match`(): Unit = runBlocking {
        val target = target("projection_exclude_all")
        val none = Projection(exclude = listOf("id", "tenant_id", "payload", "amount").map(::QueryField))
        target.list(ListQuery(projection = none, limit = 2)).map(ObjectNode::size).assert().containsExactly(0, 0)
        val page = target.backend.paged(QueryAdmission.Trusted.paged(PagedQuery(projection = none), target.schema()))
        page.list.map(ObjectNode::size).assert().containsExactly(0, 0)
        page.total.assert().isEqualTo(2L)
        val cursor = target.cursor(CursorQuery(projection = none, size = 1))
        cursor.list.single().size().assert().isEqualTo(0)
        cursor.nextCursor.assert().isNotNull()
    }

    @Test
    fun `an exclusion applies after an inclusion`(): Unit = runBlocking {
        val target = target("projection_mixed")
        val mixed = Projection(include = listOf(id, amount), exclude = listOf(amount))
        target.list(ListQuery(projection = mixed, limit = 2)).map { it.propertyNames().asSequence().toList() }
            .assert().containsExactly(listOf("id"), listOf("id"))
        target.cursor(CursorQuery(projection = mixed, size = 1)).list.single().propertyNames().asSequence().toList()
            .assert().containsExactly("id")
        val nothing = Projection(include = listOf(amount), exclude = listOf(amount))
        target.list(ListQuery(projection = nothing, limit = 2)).map(ObjectNode::size).assert().containsExactly(0, 0)
    }

    @Test
    fun `an inclusion or an exclusion alone keeps what it names`(): Unit = runBlocking {
        val target = target("projection_single")
        target.list(ListQuery(projection = Projection(include = listOf(id, amount)), limit = 2))
            .map { it.propertyNames().asSequence().toList() }
            .assert().containsExactly(listOf("id", "amount"), listOf("id", "amount"))
        target.list(ListQuery(projection = Projection(exclude = listOf(amount)), limit = 2))
            .map { it.propertyNames().asSequence().toList() }
            .assert().containsExactly(listOf("id", "tenant_id", "payload"), listOf("id", "tenant_id", "payload"))
    }
}
