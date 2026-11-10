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
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.PageWindow
import me.ahoo.wow.query.backend.QueryBackendStorages
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.orderOf
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasets
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.Temporal
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.math.BigInteger
import java.time.Instant

/**
 * The reference backend compares stored values by what they are, not by how JSON spells them: a number by its exact
 * value whatever its width, a date string by its instant, an epoch by its exact instant.
 */
class InMemoryExactValuesTest {
    private val nodes = JsonNodeFactory.instance
    private val storage = InMemoryDatasetStorage()

    private fun row(id: String, build: ObjectNode.() -> Unit): ObjectNode =
        nodes.objectNode().put("id", id).put("tenant", "t").apply(build)

    private suspend fun target(rows: List<ObjectNode>): Pair<QueryModelSchema, InMemoryQueryBackend> {
        val dataset = QueryDataset.of(AggregateType("memory", "exact")) {
            tenant("tenant")
            identity("id")
            storage(storage.put("exact", rows))
            field("id") { types(QueryValueType.STRING) }
            field("tenant") { types(QueryValueType.STRING) }
            field("qty") {
                types(QueryValueType.INTEGER)
                nullable(true)
            }
            field("at") {
                types(QueryValueType.STRING)
                semantic(Temporal.Date)
                nullable(true)
            }
            field("epoch") {
                types(QueryValueType.INTEGER)
                temporalEpoch()
                nullable(true)
            }
        }
        val datasets = QueryDatasets(listOf(dataset))
        val catalog = QuerySchemaCatalog(
            storages = QueryBackendStorages(datasetBindings = storage, datasets = datasets),
            compiler = QueryModelCompiler.of(datasets = datasets),
            datasets = datasets,
        )
        return catalog.schema(dataset.name, QueryModel.DATASET) to
            checkNotNull(storage.binding(dataset)).backend as InMemoryQueryBackend
    }

    @Test
    fun `a date string orders by its instant, not by its text`() {
        val earlier = nodes.stringNode("2026-01-01T00:00:00Z")
        val sameInstant = nodes.stringNode("2026-01-01T01:00:00+01:00")
        val later = nodes.stringNode("2026-01-01T00:00:00.001Z")
        val sub = nodes.stringNode("2026-01-01T00:00:00.000000100Z")
        orderOf(earlier, later, Temporal.Date).assert().isNegative()
        orderOf(earlier, sameInstant, Temporal.Date).assert().isZero()
        orderOf(earlier, sub, Temporal.Date).assert().isNegative()
        orderOf(sub, later, Temporal.Date).assert().isNegative()
        // Without the date semantic the text decides.
        orderOf(earlier, later, null).assert().isPositive()
        // A string that is no date sorts after every date.
        orderOf(nodes.stringNode("soon"), later, Temporal.Date).assert().isPositive()
        val values = listOf<JsonNode>(nodes.arrayNode().add("2026-01-01T00:00:00.001Z").add("2026-01-01T00:00:00Z"))
        values.sortKey(ascending = true, temporal = Temporal.Date)!!.stringValue().assert()
            .isEqualTo("2026-01-01T00:00:00Z")
        values.sortKey(ascending = false, temporal = Temporal.Date)!!.stringValue().assert()
            .isEqualTo("2026-01-01T00:00:00.001Z")
    }

    @Test
    fun `a date sort and a date cursor follow time`(): Unit = runBlocking {
        val (schema, backend) = target(
            listOf(
                row("later") { put("at", "2026-01-01T00:00:00.001Z") },
                row("earlier") { put("at", "2026-01-01T00:00:00Z") },
                row("offset") { put("at", "2026-01-01T01:00:00+01:00") },
            ),
        )
        val ascending = listOf(Sort(QueryField("at"), Sort.Direction.ASC))
        backend.stream(QueryAdmission.Trusted.list(ListQuery(MatchAllFilter, sort = ascending), schema)).toList()
            .map { it.path("id").stringValue() }.assert().containsExactly("earlier", "offset", "later")
        val cursor = QueryAdmission.Trusted.cursor(CursorQuery(MatchAllFilter, sort = ascending, size = 1), schema)
        val seen = mutableListOf<String>()
        var after: me.ahoo.wow.query.backend.CursorPosition? = null
        repeat(4) {
            val page = backend.page(cursor, PageWindow.Keyset(after, 1))
            if (page.rows.isEmpty()) return@repeat
            seen += page.rows.single().path("id").stringValue()
            val position = page.positions!!.single()
            after = backend.cursorPositions.decode(backend.cursorPositions.encode(position), position.values.size)
        }
        seen.assert().containsExactly("earlier", "offset", "later")
    }

    @Test
    fun `FIRST and LAST by a date order by time`(): Unit = runBlocking {
        val (schema, backend) = target(
            listOf(
                row("later") { put("at", "2026-01-01T00:00:00.001Z").put("qty", 2) },
                row("earlier") { put("at", "2026-01-01T00:00:00Z").put("qty", 1) },
            ),
        )
        val at = QueryField("at")
        val row = backend.aggregate(
            QueryAdmission.Trusted.aggregate(
                AggregationQuery(
                    metrics = listOf(
                        AggregationMetric.First(QueryField("qty"), "first", at),
                        AggregationMetric.Last(QueryField("qty"), "last", at),
                    ),
                ),
                schema,
            ),
        ).toList().single()
        (row.path("first").longValue() to row.path("last").longValue()).assert().isEqualTo(1L to 2L)
    }

    @Test
    fun `terms group numbers by value, whatever their JSON width`(): Unit = runBlocking {
        val (schema, backend) = target(
            listOf(
                row("int") { put("qty", 1) },
                row("long") { put("qty", 1L) },
                row("big") { put("qty", BigInteger.ONE) },
                row("two") { put("qty", 2) },
            ),
        )
        val rows = backend.aggregate(
            QueryAdmission.Trusted.aggregate(
                AggregationQuery(
                    groupBy = listOf(AggregationGroup.Terms(QueryField("qty"), "qty")),
                    metrics = listOf(AggregationMetric.Count("n")),
                    sort = listOf(Sort(QueryField("qty"), Sort.Direction.ASC)),
                ),
                schema,
            ),
        ).toList()
        rows.map { it.path("qty").longValue() to it.path("n").longValue() }.assert().containsExactly(1L to 3L, 2L to 1L)
    }

    @Test
    fun `an epoch past the nanoseconds a long holds keeps its year`(): Unit = runBlocking {
        val future = Instant.parse("2500-05-06T12:34:56Z")
        val (schema, backend) = target(listOf(row("future") { put("epoch", future.toEpochMilli()) }))
        val rows = backend.aggregate(
            QueryAdmission.Trusted.aggregate(
                AggregationQuery(
                    groupBy = listOf(
                        AggregationGroup.DateHistogram(QueryField("epoch"), "year", AggregationDateUnit.YEAR),
                    ),
                    metrics = listOf(AggregationMetric.Count("n")),
                ),
                schema,
            ),
        ).toList()
        rows.single().path("year").longValue().assert().isEqualTo(Instant.parse("2500-01-01T00:00:00Z").toEpochMilli())
    }
}
