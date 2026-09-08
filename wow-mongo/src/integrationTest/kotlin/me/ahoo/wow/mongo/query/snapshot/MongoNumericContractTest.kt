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

package me.ahoo.wow.mongo.query.snapshot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.validateQuery
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import me.ahoo.wow.mongo.query.schema.MongoQuerySchemaAdapter
import me.ahoo.wow.tck.container.MongoTestFixture
import org.bson.Document
import reactor.kotlin.core.publisher.toMono

class MongoNumericContractTest {
    @JvmField
    @RegisterExtension
    val mongo = MongoTestFixture()

    @Test
    fun `numeric fields count non null entries identically in direct and binary root and expanded metrics`() {
        val collection = mongo.database().getCollection("numeric_contract")
        val documents = cases.map { (name, fixture) ->
            val record = Document("case", name)
            if (name != "missing") record["samples"] = fixture.first
            Document(record).append("deleted", false).append("lines", listOf(record))
        }
        collection.insertMany(documents).toMono().block()
        val backend = MongoSnapshotQueryBackend(MOCK_AGGREGATE_METADATA, collection)
        shapes.forEach { shape ->
            val schema = MongoQuerySchemaAdapter(collection).resolve(definition(shape)).block()!!
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
        val scalarRecord = Document("case", "scalar").append("samples", 7L)
        collection.insertOne(Document(scalarRecord).append("deleted", false).append("lines", listOf(scalarRecord)))
            .toMono().block()
        val unionSchema = MongoQuerySchemaAdapter(collection).resolve(definition(shapes.last())).block()!!
        listOf(false, true).forEach { nested ->
            val summary = query(nested).copy(groupBy = emptyList())
            val row = backend.aggregate(validateQuery(summary, unionSchema), unionSchema).single().block()!!
            summary.metrics.forEach { metric ->
                row.path(metric.alias).doubleValue().assert().isEqualTo(if (metric.alias.startsWith("SUM")) 21.0 else 7.0)
            }
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
