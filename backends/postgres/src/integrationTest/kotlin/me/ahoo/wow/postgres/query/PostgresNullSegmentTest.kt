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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.postgres.PostgresFixture
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.schema.BeanQuerySchemaSource
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaRegistration
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.querySchema
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A property or a map key named `null`, in any case, is a path segment as any other (#7212). The path below a `jsonb`
 * column is an array literal, `'{state,null}'`, in which an unquoted `NULL` is SQL `NULL`: the whole path was `NULL`,
 * the field read as missing, so a filter on it matched no row and a sort saw no value. Over the snapshot table a
 * service queries, with the storage it is queried through.
 */
class PostgresNullSegmentTest {
    private val type = AggregateType("nulls_" + UUID.randomUUID().toString().replace("-", "").take(12), "order")

    private val declaration = querySchema {
        field("state.null") { types(QueryValueType.INTEGER) }
        field("state.NULL") { types(QueryValueType.STRING) }
        field("state.Null.flag") { types(QueryValueType.BOOLEAN) }
        field("state.count") { types(QueryValueType.INTEGER) }
    }

    private fun ids(filter: FilterExpression, sort: List<Sort> = emptyList()): List<String> =
        runBlocking {
            val store = PostgresFixture.snapshotStore(type.context)
            STATES.forEach { (id, state) -> store.save(QueryFixtures.snapshot(type, id, state)) }
            val storage = PostgresSnapshotQueryStorage(PostgresFixture.dataSource, type.context)
            val binding = checkNotNull(storage.binding(type))
            val context = QuerySchemaContext(type, QueryModel.SNAPSHOT)
            val schema = DefaultQueryModelSchemaProvider(
                context,
                listOf(BeanQuerySchemaSource(listOf(QuerySchemaRegistration(context, declaration)))),
                binding.storage,
            ).schema()
            binding.backend.list(QueryAdmission.Trusted.list(ListQuery(filter, sort = sort, limit = 10), schema))
                .toList().map { it.path("aggregateId").stringValue() }
        }

    @Test
    fun `a field named null in any case should be read as the value it holds`() {
        ids(EqualFilter(QueryField("state.null"), 7)).assert().containsExactly("a")
        ids(EqualFilter(QueryField("state.NULL"), "x")).assert().containsExactly("b")
        ids(EqualFilter(QueryField("state.Null.flag"), true)).assert().containsExactly("c")
        // A sort by it sees its values: 1 before 7, the record without one first, as the compiler orders nulls.
        ids(EqualFilter(QueryField("state.count"), 1), listOf(Sort(QueryField("state.null"), Sort.Direction.ASC)))
            .assert().containsExactly("c", "d", "a")
    }

    @Test
    fun `a segment named null should be quoted in the path, and every other written as it was`() {
        PostgresSnapshotQueryExpressions.node(QueryField("state.a.null.Null.b"))
            .assert().isEqualTo("""(state #> '{a,"null","Null",b}')""")
        PostgresSnapshotQueryExpressions.node(QueryField("state.a.nullable.b"))
            .assert().isEqualTo("(state #> '{a,nullable,b}')")
    }

    private companion object {
        val STATES = listOf(
            "a" to """{"null":7,"count":1}""",
            "b" to """{"NULL":"x","count":2}""",
            "c" to """{"Null":{"flag":true},"count":1}""",
            "d" to """{"null":1,"count":1}""",
        )
    }
}
