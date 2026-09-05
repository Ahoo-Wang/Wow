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
import io.mockk.spyk
import io.mockk.verify
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
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import me.ahoo.wow.query.schema.requireAccepted
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class ElasticsearchAggregationCompilerTest {
    @Test
    fun `numeric epoch date histogram should use a request local parameterized date runtime field`() {
        val schema = spyk(
            schema(
                field(
                    "state.createdAt",
                    QueryCapability.AGGREGATE_TEMPORAL,
                    "physical.created_at",
                    "long",
                    Temporal.Epoch(TimeUnit.MICROSECONDS),
                    resolvedPath = "document.createdAt",
                ),
            ),
        )

        val plan = ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                dateHistogram("document.createdAt", AggregationDateUnit.DAY, "day")
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
        ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation { count("count") },
            schema,
        ).runtimeMappings.assert().isEmpty()
        verify(exactly = 1) {
            schema.resolveFieldSchema(QueryField("document.createdAt"), QueryCapability.AGGREGATE_TEMPORAL)
        }
        verify(exactly = 0) {
            schema.resolvePhysicalField(
                QueryField("document.createdAt"),
                QueryCapability.AGGREGATE_TEMPORAL,
                any(),
                any(),
                any(),
            )
        }
    }

    @Test
    fun `terms should reuse its resolved binding`() {
        val schema = spyk(
            schema(field("state.status", QueryCapability.AGGREGATE_TERMS, "storage.status", "keyword")),
        )

        val plan = ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation { terms("state.status", "status"); count("count") },
            schema,
        )

        plan.groupSources.single().value().terms().field().assert().isEqualTo("storage.status")
        verify(exactly = 1) {
            schema.resolveFieldSchema(QueryField("state.status"), QueryCapability.AGGREGATE_TERMS)
        }
        verify(exactly = 0) {
            schema.resolvePhysicalField(
                QueryField("state.status"),
                QueryCapability.AGGREGATE_TERMS,
                any(),
                any(),
                any(),
            )
        }
    }

    @Test
    fun `date histogram should reject declared fields without temporal capability`() {
        val root = QueryField("state.extra")
        val declared = schema(field(root.path, QueryCapability.PRESENCE, "storage.extra", "object"))
        val dynamic = declared.copy(
            fields = declared.fields.mapValues { (_, value) -> value.copy(dynamicChildren = true) },
        )

        listOf(declared to root.path, dynamic to "state.extra.createdAt").forEach { (inputSchema, path) ->
            assertThrows<QuerySchemaValidationException> {
                ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(
                    aggregation {
                        dateHistogram(path, AggregationDateUnit.DAY, "day")
                        count("count")
                    },
                    inputSchema,
                )
            }.message.assert().contains("AGGREGATE_TEMPORAL")
        }

        val fallback = ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                dateHistogram("state.extra.createdAt", AggregationDateUnit.DAY, "day")
                count("count")
            },
            schema(),
        )
        fallback.groupSources.single().value().dateHistogram().field().assert()
            .isEqualTo("state.extra.createdAt")
    }

    @Test
    fun `date histogram should prefer temporal aliases and require temporal semantics`() {
        val temporal = field(
            "state.createdAt",
            QueryCapability.AGGREGATE_TEMPORAL,
            "storage.createdAt",
            "date",
            Temporal.Date,
            resolvedPath = "document.createdAt",
        )
        val aliasSchema = schema(
            temporal,
            field("document.createdAt", QueryCapability.PRESENCE, "document.createdAt", "date"),
        )
        val query = aggregation {
            dateHistogram("document.createdAt", AggregationDateUnit.DAY, "day")
            count("count")
        }

        val plan = ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(query, aliasSchema)

        plan.groupSources.single().value().dateHistogram().field().assert().isEqualTo("storage.createdAt")

        val unsupported = schema(
            field(
                "state.createdAt",
                QueryCapability.AGGREGATE_TEMPORAL,
                "storage.createdAt",
                "date",
                resolvedPath = "document.createdAt",
            ),
        )
        assertThrows<QuerySchemaValidationException> {
            ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(query, unsupported)
        }.message.assert().contains("does not have a supported temporal semantic type")
    }

    @Test
    fun `maximum expression should fit the default script source limit`() {
        val plan = ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation { sum(maximumExpression(), "total") },
            schema(),
        )

        val source = requireNotNull(plan.runtimeMappings.values.single().script()?.source()).scriptString()
        source.toByteArray(Charsets.UTF_8).size.assert().isLessThanOrEqualTo(65_535)
    }

    @Test
    fun `computed metric should compile a parameterized double runtime field`() {
        val plan = ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                expand("state.items")
                sum(field("price") * field("quantity") - constant(10.0), "total")
            },
            schema(),
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
        val plan = ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(
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
        val plan = ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation { sum("state.amount", "total") },
            schema(),
        )

        plan.runtimeMappings.assert().isEmpty()
        (plan.metrics.single() as ElasticsearchAggregationMetric.Numeric).field.assert().isEqualTo("state.amount")
    }

    @Test
    fun `any metric should compile an already resolved field to its physical path`() {
        val schema = schema(
            field(
                "state.productName",
                QueryCapability.AGGREGATE_TERMS,
                "document.productName.keyword",
                "keyword",
                resolvedPath = "resolved.productName.keyword",
            ),
        )
        val plan = ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation { any("resolved.productName.keyword", "productName") },
            schema,
        )

        val metric = plan.metrics.single() as ElasticsearchAggregationMetric.Any
        metric.alias.assert().isEqualTo("productName")
        metric.field.assert().isEqualTo("document.productName.keyword")
        plan.runtimeMappings.assert().isEmpty()
    }

    @Test
    @Suppress("LongMethod")
    fun `compiler should consume resolved filters and resolve logical aggregation fields`() {
        val schema = schema(
            field(
                "state.orders",
                QueryCapability.ELEMENT_SCOPE,
                "storage.orders",
                "nested",
                resolvedPath = "document.orders",
            ),
            field(
                "state.orders.status",
                QueryCapability.EXACT_MATCH,
                "storage.orders.status.keyword",
                "keyword",
                resolvedPath = "document.orders.status.keyword",
            ),
            field(
                "state.orders.lines",
                QueryCapability.ELEMENT_SCOPE,
                "storage.orders.lines",
                "nested",
                resolvedPath = "document.orders.lines",
            ),
            field(
                "state.orders.lines.quantity",
                QueryCapability.RANGE,
                "storage.orders.lines.quantity",
                "integer",
                resolvedPath = "document.orders.lines.quantity",
            ),
            field(
                "state.orders.lines.productId",
                QueryCapability.AGGREGATE_TERMS,
                "storage.orders.lines.productId.keyword",
                "keyword",
                resolvedPath = "document.orders.lines.productId.keyword",
            ),
            field(
                "state.orders.lines.amount",
                QueryCapability.AGGREGATE_NUMERIC,
                "storage.orders.lines.amount",
                "double",
                resolvedPath = "document.orders.lines.amount",
            ),
            field(
                "state.orders.lines.createdAt",
                QueryCapability.AGGREGATE_TEMPORAL,
                "storage.orders.lines.createdAt",
                "date",
                Temporal.Date,
                resolvedPath = "document.orders.lines.createdAt",
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
        val plan = ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(
            query,
            schema,
        )

        plan.elements.map { it.path }.assert().containsExactly("storage.orders", "storage.orders.lines")
        plan.elements[0].filter.bool().filter().first().term().field().assert()
            .isEqualTo("storage.orders.status.keyword")
        plan.elements[1].filter.bool().filter().first().range().untyped().field().assert()
            .isEqualTo("storage.orders.lines.quantity")
        plan.groupSources[0].value().terms().field().assert()
            .isEqualTo("storage.orders.lines.productId.keyword")
        plan.groupSources[1].value().histogram().field().assert().isEqualTo("storage.orders.lines.amount")
        plan.groupSources[2].value().dateHistogram().field().assert().isEqualTo("storage.orders.lines.createdAt")
        (plan.metrics.single() as ElasticsearchAggregationMetric.Numeric).field.assert()
            .isEqualTo("storage.orders.lines.amount")
    }

    @Test
    fun `relative field sharing its parent prefix should still resolve inside the element`() {
        val schema = schema(
            field("body", QueryCapability.ELEMENT_SCOPE, "events", "nested"),
            field("body.body.data", QueryCapability.AGGREGATE_TERMS, "events.payload.data.keyword", "keyword"),
        )

        val plan = ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(
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
    fun `compatible aggregation fields should retain the physical element parent`() {
        val schema = schema(
            field(
                "state.orders",
                QueryCapability.ELEMENT_SCOPE,
                "storage.orders",
                "nested",
                resolvedPath = "document.orders",
            ),
        )
        val query = schema.resolve(
            aggregation {
                expand("state.orders")
                terms("category", "category")
                dateHistogram("createdAt", AggregationDateUnit.DAY, "day")
                sum("amount", "amount")
                sum(field("price") * constant(2.0), "total")
            },
        ).requireAccepted(QuerySchemaValidationMode.COMPATIBLE)

        val plan = ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(query, schema)

        plan.groupSources[0].value().terms().field().assert().isEqualTo("storage.orders.category")
        plan.groupSources[1].value().dateHistogram().field().assert().isEqualTo("storage.orders.createdAt")
        (plan.metrics[0] as ElasticsearchAggregationMetric.Numeric).field.assert()
            .isEqualTo("storage.orders.amount")
        plan.runtimeMappings.values.single().script()!!.params().values.map { it.to(Any::class.java) }.assert()
            .contains("storage.orders.price")
    }

    @Test
    fun `compiler should order composite sources by effective group sort`() {
        val plan = ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(
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
            schema(),
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
        val plan = ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                dateHistogram("state.createdAt", AggregationDateUnit.SECOND, "second")
                count("count")
            },
            schema(),
        )

        plan.groupSources.single().value().dateHistogram().apply {
            fixedInterval()!!.time().assert().isEqualTo("1s")
            calendarInterval().assert().isNull()
        }
    }

    @Test
    fun `filter compiler should receive resolved and physical element scopes`() {
        val compiler = mockk<me.ahoo.wow.elasticsearch.query.AbstractElasticsearchFilterCompiler> {
            every { compile(any(), any()) } returns
                co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.matchAll { it }
            every { compileScoped(any(), any(), any(), any(), any()) } returns
                co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.matchAll { it }
        }

        val plan = ElasticsearchAggregationCompiler(compiler).compile(
            aggregation {
                filter { "physical.root" eq "ACTIVE" }
                expand("physical.items") { "physical.quantity" gt 0 }
                terms("physical.product", "product")
                count("count")
            },
            schema(),
        )

        plan.elements.single().path.assert().isEqualTo("physical.items")
        plan.groupSources.single().value().terms().field().assert().isEqualTo("physical.items.physical.product")
        verify(exactly = 1) { compiler.compile(any(), any()) }
        verify(exactly = 1) {
            compiler.compileScoped(
                any(),
                any(),
                QueryField("physical.items"),
                QueryField("physical.items"),
                QueryField("physical.items"),
            )
        }
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
