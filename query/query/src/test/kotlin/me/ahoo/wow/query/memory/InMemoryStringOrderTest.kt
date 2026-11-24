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
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.aggregation.BoundedTopRows
import me.ahoo.wow.query.backend.GroupWindow
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import java.time.Duration

/**
 * Deep review r1 (the query path): the core compares two strings itself with `String.compareTo`, by UTF-16 code unit
 * (`compareScalars` in `ValueOrder.kt`), in the in-memory backend (the reference, and the test DSL's `memoryQueries`)
 * and in the residual operators every backend that delegates top-N runs (Elasticsearch). Every store orders text by
 * code point: PostgreSQL's `COLLATE "C"` (its snapshot tables say so), MongoDB's binary comparison, Elasticsearch's
 * keywords, ClickHouse's bytes. The two orders part where a character between U+E000 and U+FFFF meets one above
 * U+FFFF: `Ａ` (U+FF21) sorts before `😀` (U+1F600) by code point, after it by UTF-16 code unit (U+D83D).
 *
 * Each case expects the stores' answer, which PostgreSQL and MongoDB give (`ReviewStringOrderTest` of each backend).
 */
internal class InMemoryStringOrderTest : InMemoryReferenceFixtures() {
    private val rows: List<ObjectNode> = listOf(
        """{"id":"a","tenant_id":"t1","name":"z","note":"W"}""",
        """{"id":"b","tenant_id":"t1","name":"Ａ","note":"X"}""",
        """{"id":"c","tenant_id":"t1","name":"😀","note":"Y"}""",
        """{"id":"d","tenant_id":"t1","name":"","note":"Y"}""",
    ).map { WowJson.mapper.readTree(it) as ObjectNode }

    private fun backendOf() =
        InMemoryQueryBackend(backend.aggregateType, { rows.map(ObjectNode::deepCopy) }, Duration.ZERO)

    private fun idsOf(filter: FilterExpression, sort: List<Sort> = listOf(Sort(ID, Sort.Direction.ASC))) =
        runBlocking {
            backendOf().stream(QueryAdmission.Trusted.list(ListQuery(filter, sort = sort), schema)).toList()
                .map { it.path("id").stringValue() }
        }

    @Test
    fun `a list sorted by a text orders it by code point, as every store does`() {
        idsOf(MatchAllFilter, listOf(Sort(NAME, Sort.Direction.ASC))).assert().containsExactly("a", "d", "b", "c")
    }

    @Test
    fun `a range over a text compares it by code point, as every store does`() {
        idsOf(GreaterThanFilter(NAME, "Ａ")).assert().containsExactly("c")
    }

    @Test
    fun `an aggregation sorted by a text metric orders it by code point, as a store that sorts it does`() {
        val groups = runBlocking {
            backendOf().aggregate(
                QueryAdmission.Trusted.aggregate(
                    AggregationQuery(
                        groupBy = listOf(AggregationGroup.Terms(NOTE, "note")),
                        metrics = listOf(AggregationMetric.First(NAME, "first", orderBy = ID)),
                        sort = listOf(Sort(QueryField("first"), Sort.Direction.DESC)),
                    ),
                    schema,
                ),
                GroupWindow.All,
            ).toList()
        }
        groups.map { it.path("note").stringValue() }.assert().containsExactly("Y", "X", "W")
    }

    /**
     * The core's top-N of a backend that declares it RESIDUAL (Elasticsearch, the in-memory one): it keeps the first
     * `limit` rows by [BoundedTopRows]' comparison, the same `compareScalars`. Of `Ａ` and `😀`, the greater by code point is
     * `😀`, which PostgreSQL and MongoDB, sorting natively, put first.
     */
    @Test
    fun `the core's residual top-N keeps the greatest text metric by code point`() {
        val top = BoundedTopRows(listOf(Sort(QueryField("first"), Sort.Direction.DESC)), 1, readings = emptyMap())
        listOf("X" to "\uFF21", "Y" to "\uD83D\uDE00", "W" to "z").forEach { (note, first) ->
            top.add(WowJson.mapper.createObjectNode().put("note", note).put("first", first))
        }
        top.result().map { it.path("note").stringValue() }.assert().containsExactly("Y")
    }

    private companion object {
        val ID = QueryField("id")
        val NAME = QueryField("name")
        val NOTE = QueryField("note")
    }
}
