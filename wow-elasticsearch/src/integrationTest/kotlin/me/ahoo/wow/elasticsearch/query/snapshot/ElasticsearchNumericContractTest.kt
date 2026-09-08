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

package me.ahoo.wow.elasticsearch.query.snapshot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.validateQuery
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import co.elastic.clients.elasticsearch._types.Refresh
import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch._types.mapping.RuntimeFieldType
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import co.elastic.clients.elasticsearch.core.IndexRequest
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.elasticsearch.ReactiveElasticsearchClients
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMapping
import me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapter
import me.ahoo.wow.query.dsl.filterExpression
import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import reactor.kotlin.test.test
import java.time.Duration

class ElasticsearchNumericContractTest {
    @JvmField
    @RegisterExtension
    val elasticsearch = ElasticsearchTestFixture()

    @ParameterizedTest
    @CsvSource("false, false, false", "true, false, false", "false, true, false", "true, true, false", "false, false, true")
    fun `mapped runtime failures remain execution errors`(arrayField: Boolean, binaryExpression: Boolean, epochGrouping: Boolean) {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        val index = MOCK_AGGREGATE_METADATA.toSnapshotIndexName()
        val mapping = TypeMapping.of { mapping ->
            mapping.runtime("samples") { runtime ->
                runtime.type(RuntimeFieldType.Long).script { script ->
                    script.source { it.scriptString("throw new IllegalArgumentException('mapped-runtime-failure');") }
                }
            }
        }
        client.indices().create { it.index(index).mappings(mapping) }.block()
        client.index(IndexRequest.of<Map<String, Any>> {
            it.index(index).id("failure").document(mapOf("case" to "failure")).refresh(Refresh.True)
        }).block()
        val value = when {
            epochGrouping -> QueryValueSchema(
                QueryValueKind.SCALAR,
                valueTypes = setOf(QueryValueType.INTEGER),
                semanticType = Temporal.Epoch(),
            )
            arrayField -> array
            else -> number
        }
        val logical = LogicalQuerySchema(QueryValueSchema(QueryValueKind.OBJECT, properties = mapOf(
            "samples" to value,
        )))
        val schema = ElasticsearchQuerySchemaAdapter.bind(logical, ElasticsearchIndexMapping.from(index, mapping))
        val field = AggregationExpression.Field(QueryField("samples"))
        val expression = if (binaryExpression) {
            AggregationExpression.Binary(AggregationExpressionOperator.ADD, field, AggregationExpression.Constant(0.0))
        } else {
            field
        }
        val query = if (epochGrouping) {
            AggregationQuery(
                groupBy = listOf(AggregationGroup.DateHistogram(QueryField("samples"), "day", AggregationDateUnit.DAY)),
                metrics = listOf(AggregationMetric.Count("count")),
            )
        } else {
            AggregationQuery(metrics = listOf(AggregationMetric.Numeric(AggregationFunction.SUM, expression, "total")))
        }
        ElasticsearchSnapshotQueryBackend(MOCK_AGGREGATE_METADATA, client)
            .aggregate(validateQuery(query, schema), schema).test()
            .expectErrorMatches { error ->
                error is ElasticsearchException && error.error().type() == "search_phase_execution_exception" &&
                    error.error().toString().contains("mapped-runtime-failure")
            }.verify(Duration.ofSeconds(30))
    }

    @Test
    fun `numeric fields count non null entries identically in direct and binary root and expanded metrics`() {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        val index = MOCK_AGGREGATE_METADATA.toSnapshotIndexName()
        val mapping = TypeMapping.of { it
            .properties("deleted") { it.boolean_ { it } }
            .properties("case") { it.keyword { it } }
            .properties("samples") { it.long_ { it } }
            .properties("lines") { it.nested { it
                .properties("case") { it.keyword { it } }
                .properties("samples") { it.long_ { it } }
            } }
        }
        client.indices().create { it.index(index).mappings(mapping) }.block()
        cases.forEach { (name, fixture) ->
            val record = linkedMapOf<String, Any?>("case" to name)
            if (name != "missing") record["samples"] = fixture.first
            client.index(IndexRequest.of<Map<String, Any?>> { it.index(index).id(name)
                .document(record + mapOf("deleted" to false, "lines" to listOf(record))).refresh(Refresh.True)
            }).block()
        }
        val backend = ElasticsearchSnapshotQueryBackend(MOCK_AGGREGATE_METADATA, client)
        shapes.forEach { shape ->
            val schema = ElasticsearchQuerySchemaAdapter.bind(definition(shape), ElasticsearchIndexMapping.from(index, mapping))
            listOf(false, true).forEach { nested ->
                val query = query(nested)
                val rows = backend.aggregate(validateQuery(query, schema), schema).collectList().block()!!
                rows.size.assert().isEqualTo(cases.size)
                rows.forEach { row ->
                    val expected = cases.getValue(row.path("case").asString()).second
                    query.metrics.forEach { metric ->
                        val actual = row.path(metric.alias)
                        if (expected == null) actual.isNull.assert().isTrue()
                        else actual.doubleValue().assert().isEqualTo(expected)
                    }
                }
            }
        }
        val scalarRecord = mapOf("case" to "scalar", "samples" to 7L)
        client.index(IndexRequest.of<Map<String, Any>> { it.index(index).id("scalar")
            .document(scalarRecord + mapOf("deleted" to false, "lines" to listOf(scalarRecord))).refresh(Refresh.True)
        }).block()
        val unionSchema = ElasticsearchQuerySchemaAdapter.bind(definition(shapes.last()), ElasticsearchIndexMapping.from(index, mapping))
        listOf(false, true).forEach { nested ->
            val summary = query(nested).copy(groupBy = emptyList())
            val row = backend.aggregate(validateQuery(summary, unionSchema), unionSchema).single().block()!!
            summary.metrics.forEach { metric ->
                row.path(metric.alias).doubleValue().assert().isEqualTo(if (metric.alias.startsWith("SUM")) 21.0 else 7.0)
            }
        }
    }

    @Test
    fun `numeric equality uses scaled stored precision with double control`() {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        val index = MOCK_AGGREGATE_METADATA.toSnapshotIndexName()
        val mapping = TypeMapping.of { it
            .properties("deleted") { it.boolean_ { it } }
            .properties("scaled") { it.scaledFloat { it.scalingFactor(10.0) } }
            .properties("precise") { it.double_ { it } }
        }
        client.indices().create { it.index(index).mappings(mapping) }.block()
        client.index(IndexRequest.of<Map<String, Any>> { it.index(index).id("one")
            .document(mapOf("deleted" to false, "scaled" to 1.04, "precise" to 1.04)).refresh(Refresh.True)
        }).block()
        val decimal = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.DECIMAL))
        val logical = LogicalQuerySchema(QueryValueSchema(QueryValueKind.OBJECT, properties = mapOf(
            "deleted" to QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.BOOLEAN)),
            "scaled" to decimal,
            "precise" to decimal,
        )))
        val schema = ElasticsearchQuerySchemaAdapter.bind(logical, ElasticsearchIndexMapping.from(index, mapping))
        val backend = ElasticsearchSnapshotQueryBackend(MOCK_AGGREGATE_METADATA, client)
        listOf(Triple("scaled", 1.01, 1L), Triple("scaled", 1.11, 0L), Triple("precise", 1.01, 0L), Triple("precise", 1.04, 1L))
            .forEach { (field, value, expected) ->
                val filter = filterExpression { field eq value }
                backend.count(validateQuery(filter, schema), schema).block().assert().isEqualTo(expected)
            }
    }

    private val number = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.INTEGER))
    private val array = QueryValueSchema(QueryValueKind.ARRAY, items = number)
    private val shapes = listOf(
        array,
        QueryValueSchema(QueryValueKind.UNION, alternatives = listOf(array, QueryValueSchema(QueryValueKind.NULL))),
        QueryValueSchema(QueryValueKind.UNION, alternatives = listOf(number, array)),
    )
    private val cases = linkedMapOf<String, Pair<Any?, Double?>>(
        "empty" to (emptyList<Long>() to null),
        "nulls" to (listOf(null) to null),
        "nullableSingleton" to (listOf(null, 7L) to 7.0),
        "singleton" to (listOf(7L) to 7.0),
        "multiple" to (listOf(1L, 2L) to null),
        "duplicates" to (listOf(7L, 7L) to null),
        "null" to (null to null),
        "missing" to (null to null),
    )

    private fun definition(value: QueryValueSchema): LogicalQuerySchema {
        val record = QueryValueSchema(QueryValueKind.OBJECT, properties = mapOf(
            "case" to QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING)),
            "samples" to value,
        ))
        return LogicalQuerySchema(QueryValueSchema(QueryValueKind.OBJECT, properties = record.properties + mapOf(
            "deleted" to QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.BOOLEAN)),
            "lines" to QueryValueSchema(QueryValueKind.ARRAY, items = record),
        )))
    }

    private fun query(nested: Boolean): AggregationQuery {
        val field = AggregationExpression.Field(QueryField("samples"))
        val binary = AggregationExpression.Binary(AggregationExpressionOperator.ADD, field, AggregationExpression.Constant(0.0))
        return AggregationQuery(
            elements = if (nested) listOf(AggregationElement(QueryField("lines"))) else emptyList(),
            groupBy = listOf(AggregationGroup.Terms(QueryField("case"), "case")),
            metrics = AggregationFunction.entries.flatMap { function ->
                listOf(
                    AggregationMetric.Numeric(function, field, "${function.name}Direct"),
                    AggregationMetric.Numeric(function, binary, "${function.name}Binary"),
                )
            },
        )
    }
}
