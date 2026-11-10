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

package me.ahoo.wow.query.memory

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.boundSchemaFixture
import me.ahoo.wow.query.schema.cursorUniqueKey
import me.ahoo.wow.query.schema.objectFixture
import me.ahoo.wow.query.schema.scalarFixture
import me.ahoo.wow.query.schema.uniqueKey
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.ObjectNode
import java.time.Duration

/** A model without an identity has no unique key to end a sort with: a list still sorts, its ties in stored order. */
class InMemoryTieBreakerTest {
    private val schema = boundSchemaFixture(
        objectFixture("name" to scalarFixture(), "rank" to scalarFixture(QueryValueType.INTEGER)),
        model = QueryModel("custom"),
    )

    /** A row as this fixture's bindings store it, under `native`. */
    private fun row(name: String, rank: Int): ObjectNode =
        WowJson.mapper.createObjectNode().apply { putObject("native").put("name", name).put("rank", rank) }

    @Test
    fun `a model without an identity has no unique key, and a cursor of it is refused`() {
        schema.uniqueKey().assert().isEmpty()
        assertThrows<QuerySchemaValidationException> { schema.cursorUniqueKey() }
            .violation.assert().isInstanceOf(QueryViolation.IdentityUndefined::class.java)
    }

    @Test
    fun `a list of a model without an identity sorts, its ties in stored order`(): Unit = runBlocking {
        val rows = listOf(row("b", 1), row("c", 2), row("a", 1), row("d", 2))
        val backend = InMemoryQueryBackend(AggregateType("custom", "row"), { rows }, Duration.ZERO)
        fun names(direction: Sort.Direction) = runBlocking {
            val query = ListQuery(MatchAllFilter, sort = listOf(Sort(QueryField("rank"), direction)))
            backend.list(QueryAdmission.Trusted.list(query, schema)).toList()
                .map { it.path("native").path("name").stringValue() }
        }
        names(Sort.Direction.ASC).assert().containsExactly("b", "a", "c", "d")
        names(Sort.Direction.DESC).assert().containsExactly("c", "d", "b", "a")
    }
}
