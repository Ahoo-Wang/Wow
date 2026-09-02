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

import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch._types.mapping.RuntimeFieldType
import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.elasticsearch.query.aggregation.ElasticsearchAggregationCompiler
import me.ahoo.wow.elasticsearch.query.aggregation.ElasticsearchAggregationMetric
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.schema.QueryFieldBinding
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryRewriteMode
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class ElasticsearchAggregationCompilerTest {
    @Test
    fun `numeric epoch date histogram should use a request local parameterized date runtime field`() {
        val schema = schema(
            field(
                "state.createdAt",
                QueryCapability.AGGREGATE_TEMPORAL,
                "physical.created_at",
                "long",
                Temporal.Epoch(TimeUnit.MICROSECONDS),
            ),
        )

        val plan = ElasticsearchAggregationCompiler(SnapshotFilterConverter).compile(
            aggregation {
                dateHistogram("state.createdAt", AggregationDateUnit.DAY, "day")
                count("count")
            },
            schema,
        )

        plan.groupSources.single().value().dateHistogram().field().assert().isEqualTo("__wow_date_histogram_0")
        val runtime = plan.runtimeMappings.getValue("__wow_date_histogram_0")
        runtime.type().assert().isEqualTo(RuntimeFieldType.Date)
        val script = requireNotNull(runtime.script())
        script.params().values.map { it.to(Any::class.java) }.assert()
            .contains("physical.created_at", 1L, 1_000L)
        requireNotNull(script.source()).scriptString().assert()
            .contains("doc.containsKey")
            .contains("size() == 1")
            .contains("instanceof Number")
            .contains("Double.isFinite")
            .contains("%")
            .contains("Long.MAX_VALUE")
            .doesNotContain("physical.created_at")
        ElasticsearchAggregationCompiler(SnapshotFilterConverter).compile(
            aggregation { count("count") },
            schema,
        ).runtimeMappings.assert().isEmpty()
    }

    @Test
    fun `maximum expression should fit the default script source limit`() {
        val plan = ElasticsearchAggregationCompiler(SnapshotFilterConverter).compile(
            aggregation { sum(maximumExpression(), "total") },
        )

        val source = requireNotNull(plan.runtimeMappings.values.single().script()?.source()).scriptString()
        source.toByteArray(Charsets.UTF_8).size.assert().isLessThanOrEqualTo(65_535)
    }

    @Test
    fun `computed metric should compile a parameterized double runtime field`() {
        val plan = ElasticsearchAggregationCompiler(SnapshotFilterConverter).compile(
            aggregation {
                expand("state.items")
                sum(field("price") * field("quantity") - constant(10.0), "total")
            },
        )

        (plan.metrics.single() as ElasticsearchAggregationMetric.Numeric).field.assert().isEqualTo("__wow_expression_0")
        val runtime = plan.runtimeMappings.getValue("__wow_expression_0")
        runtime.type().assert().isEqualTo(RuntimeFieldType.Double)
        val script = requireNotNull(runtime.script())
        val source = requireNotNull(script.source()).scriptString()
        source.assert()
            .contains("doc.containsKey")
            .contains("instanceof Number")
            .contains("Double.isFinite")
            .contains("emit")
            .doesNotContain("state.items.price")
            .doesNotContain("10.0")
        script.params().values.map { it.to(Any::class.java) }.assert()
            .contains("state.items.price", "state.items.quantity", 10.0)
    }

    @Test
    fun `computed operands should use schema bindings and guard invalid values`() {
        val schema = schema(
            field("state.unreadable", QueryCapability.AGGREGATE_NUMERIC, "physical.unreadable", "double"),
            field("state.amount", QueryCapability.AGGREGATE_NUMERIC, "physical.amount", "double"),
        )
        val plan = ElasticsearchAggregationCompiler(SnapshotFilterConverter).compile(
            aggregation {
                sum(field("state.unreadable") / constant(0.0), "computed")
                sum("state.amount", "plain")
            },
            schema,
        )

        val source = requireNotNull(plan.runtimeMappings.values.single().script()?.source()).scriptString()
        source.assert()
            .contains("try {")
            .contains("catch (Exception ignored)")
            .contains("size() == 1")
            .contains("doubleValue() != 0.0")
        (plan.metrics.last() as ElasticsearchAggregationMetric.Numeric).field.assert().isEqualTo("physical.amount")
        plan.runtimeMappings.values.single().script()!!.params().values.map { it.to(Any::class.java) }.assert()
            .contains("physical.unreadable")
    }

    @Test
    fun `plain field metric should not create a runtime field`() {
        val plan = ElasticsearchAggregationCompiler(SnapshotFilterConverter).compile(
            aggregation { sum("state.amount", "total") },
        )

        plan.runtimeMappings.assert().isEmpty()
        (plan.metrics.single() as ElasticsearchAggregationMetric.Numeric).field.assert().isEqualTo("state.amount")
    }

    @Test
    fun `any metric should resolve a terms-capable field without a runtime mapping`() {
        val schema = schema(
            field(
                "state.productName",
                QueryCapability.AGGREGATE_TERMS,
                "document.productName.keyword",
                "keyword",
                resolvedPath = "resolved.productName.keyword",
            ),
        )
        val plan = ElasticsearchAggregationCompiler(SnapshotFilterConverter).compile(
            aggregation { any("state.productName", "productName") },
            schema,
        )

        val metric = plan.metrics.single() as ElasticsearchAggregationMetric.Any
        metric.alias.assert().isEqualTo("productName")
        metric.field.assert().isEqualTo("document.productName.keyword")
        plan.runtimeMappings.assert().isEmpty()
    }

    @Test
    fun `compiler should consume resolved filters and resolve logical aggregation fields`() {
        val schema = schema(
            field("state.orders", QueryCapability.ELEMENT_SCOPE, "document.orders", "nested"),
            field("state.orders.status", QueryCapability.EXACT_MATCH, "document.orders.status.keyword", "keyword"),
            field("state.orders.lines", QueryCapability.ELEMENT_SCOPE, "document.orders.lines", "nested"),
            field("state.orders.lines.quantity", QueryCapability.RANGE, "document.orders.lines.quantity", "integer"),
            field(
                "state.orders.lines.productId",
                QueryCapability.AGGREGATE_TERMS,
                "document.orders.lines.productId.keyword",
                "keyword",
            ),
            field(
                "state.orders.lines.amount",
                QueryCapability.AGGREGATE_NUMERIC,
                "document.orders.lines.amount",
                "double",
            ),
            field(
                "state.orders.lines.createdAt",
                QueryCapability.AGGREGATE_TEMPORAL,
                "document.orders.lines.createdAt",
                "date",
                Temporal.Date,
            ),
        )
        val query = schema.resolve(
            aggregation {
                expand("state.orders") { "status" eq "PAID" }
                expand("lines") { "quantity" gt 0 }
                terms("productId", "product")
                histogram("amount", 10.0, "amountRange")
                dateHistogram("createdAt", AggregationDateUnit.DAY, "day")
                sum("amount", "total")
            },
        ).value
        val plan = ElasticsearchAggregationCompiler(SnapshotFilterConverter).compile(
            query,
            schema,
        )

        plan.elements.map { it.path }.assert().containsExactly("document.orders", "document.orders.lines")
        plan.groupSources[0].value().terms().field().assert()
            .isEqualTo("document.orders.lines.productId.keyword")
        plan.groupSources[1].value().histogram().field().assert().isEqualTo("document.orders.lines.amount")
        plan.groupSources[2].value().dateHistogram().field().assert().isEqualTo("document.orders.lines.createdAt")
        (plan.metrics.single() as ElasticsearchAggregationMetric.Numeric).field.assert()
            .isEqualTo("document.orders.lines.amount")
    }

    @Test
    fun `relative field sharing its parent prefix should still resolve inside the element`() {
        val schema = schema(
            field("body", QueryCapability.ELEMENT_SCOPE, "events", "nested"),
            field("body.body.data", QueryCapability.AGGREGATE_TERMS, "events.payload.data.keyword", "keyword"),
        )

        val plan = ElasticsearchAggregationCompiler(SnapshotFilterConverter).compile(
            aggregation {
                expand("body")
                terms("body.data", "data")
                count("count")
            },
            schema,
        )

        plan.groupSources.single().value().terms().field().assert().isEqualTo("events.payload.data.keyword")
    }

    @Test
    fun `compiler should order composite sources by effective group sort`() {
        val plan = ElasticsearchAggregationCompiler(SnapshotFilterConverter).compile(
            aggregation {
                terms("state.productId", "product")
                histogram("state.amount", 10.0, "amountRange")
                dateHistogram("state.createdAt", AggregationDateUnit.DAY, "day", ZoneId.of("Asia/Shanghai"))
                count("count")
                sum("state.amount", "total")
                avg("state.amount", "average")
                min("state.amount", "minimum")
                max("state.amount", "maximum")
                sort {
                    "amountRange".desc()
                    "product".asc()
                }
                limit(7)
            },
        )

        plan.groupSources.map { it.name() }.assert().containsExactly("amountRange", "product", "day")
        plan.groupSources[0].value().histogram().apply {
            field().assert().isEqualTo("state.amount")
            interval().assert().isEqualTo(10.0)
            order().assert().isEqualTo(SortOrder.Desc)
        }
        plan.groupSources[2].value().dateHistogram().apply {
            field().assert().isEqualTo("state.createdAt")
            calendarInterval()!!.time().assert().isEqualTo("day")
            timeZone().assert().isEqualTo("Asia/Shanghai")
        }
        plan.metrics.map { it.alias }.assert()
            .containsExactly("count", "total", "average", "minimum", "maximum")
        plan.effectiveSort.assert().isEqualTo(
            listOf(
                Sort(QueryField("amountRange"), Sort.Direction.DESC),
                Sort(QueryField("product"), Sort.Direction.ASC),
                Sort(QueryField("day"), Sort.Direction.ASC),
            ),
        )
        plan.limit.assert().isEqualTo(7)
        plan.metricSorted.assert().isFalse()
    }

    @Test
    fun `second date histogram should use a fixed interval`() {
        val plan = ElasticsearchAggregationCompiler(SnapshotFilterConverter).compile(
            aggregation {
                dateHistogram("state.createdAt", AggregationDateUnit.SECOND, "second")
                count("count")
            },
        )

        plan.groupSources.single().value().dateHistogram().apply {
            fixedInterval()!!.time().assert().isEqualTo("1s")
            calendarInterval().assert().isNull()
        }
    }

    @Test
    fun `custom converter should receive caller paths without mapping resolution`() {
        val convertedParents = mutableListOf<String?>()
        val converter = mockk<me.ahoo.wow.elasticsearch.query.AbstractElasticsearchFilterConverter> {
            every { convert(any(), captureNullable(convertedParents)) } answers {
                co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.matchAll { it }
            }
        }

        val plan = ElasticsearchAggregationCompiler(converter).compile(
            aggregation {
                filter { "physical.root" eq "ACTIVE" }
                expand("physical.items") { "physical.quantity" gt 0 }
                terms("physical.product", "product")
                count("count")
            },
        )

        plan.elements.single().path.assert().isEqualTo("physical.items")
        plan.groupSources.single().value().terms().field().assert().isEqualTo("physical.items.physical.product")
        convertedParents.assert().containsExactly(null, "physical.items")
    }

    private fun maximumExpression(depth: Int = 8): AggregationExpression =
        if (depth == 1) {
            AggregationExpression.Field(QueryField("amount"))
        } else {
            AggregationExpression.Binary(
                AggregationExpressionOperator.ADD,
                maximumExpression(depth - 1),
                maximumExpression(depth - 1),
            )
        }

    private fun schema(vararg fields: TestField) = QueryModelSchema(
        QueryModel.SNAPSHOT,
        emptySet(),
        fields.associate { testField ->
            testField.logicalField to QueryFieldSchema(
                title = null,
                description = null,
                enumValues = null,
                valueTypes = setOf(QueryValueType.INTEGER),
                nullable = false,
                required = true,
                cardinality = QueryCardinality.SINGLE,
                semanticType = testField.semanticType,
                dynamicChildren = false,
                bindings = mapOf(
                    testField.capability to QueryFieldBinding(
                        testField.resolvedField,
                        testField.physicalField,
                        me.ahoo.wow.query.schema.QueryStorageType(testField.storageType),
                    ),
                ),
                rewriteMode = testField.rewriteMode,
            )
        },
    )

    private fun field(
        logicalPath: String,
        capability: QueryCapability,
        physicalPath: String,
        storageType: String,
        semanticType: Temporal? = null,
        resolvedPath: String = physicalPath,
        rewriteMode: QueryRewriteMode = QueryRewriteMode.NONE,
    ) = TestField(
        QueryField(logicalPath),
        capability,
        QueryField(resolvedPath),
        QueryField(physicalPath),
        storageType,
        semanticType,
        rewriteMode,
    )

    private data class TestField(
        val logicalField: QueryField,
        val capability: QueryCapability,
        val resolvedField: QueryField,
        val physicalField: QueryField,
        val storageType: String,
        val semanticType: Temporal?,
        val rewriteMode: QueryRewriteMode,
    )
}
