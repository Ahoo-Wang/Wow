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

package me.ahoo.wow.elasticsearch.query.aggregation

import co.elastic.clients.elasticsearch._types.mapping.RuntimeFieldType
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDatePart
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.api.query.dsl.aggregation
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMapping
import me.ahoo.wow.elasticsearch.query.compile
import me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapter
import me.ahoo.wow.elasticsearch.query.schema.bind
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.Temporal
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/** The group sources the compiler plans: date histograms over epochs, date parts, terms and their missing key. */
internal class ElasticsearchAggregationSourcesTest : ElasticsearchAggregationCompilerFixtures() {
    @Test
    fun `numeric epoch histogram uses request local parameterized date runtime`() {
        val plan = compiler.compile(
            aggregation {
                dateHistogram("createdAt", AggregationDateUnit.DAY, "day")
                count("count")
            },
            schema
        )
        plan.groupSources.single().value().dateHistogram().field().assert().isEqualTo("__wow_date_histogram_0")
        val runtime = plan.runtimeMappings.getValue("__wow_date_histogram_0")
        runtime.type().assert().isEqualTo(RuntimeFieldType.Date)
        val script = requireNotNull(runtime.script())
        script.params().values.map { it.to(Any::class.java) }.assert().contains("createdAt", 1L, 1_000L)
        requireNotNull(script.source()).scriptString().assert().contains("doc.containsKey").contains("size() == 1")
            .contains("Double.isFinite").contains("Long.MAX_VALUE")
        compiler.compile(aggregation { count("count") }, schema).runtimeMappings.assert().isEmpty()
    }

    /**
     * A single-valued epoch in milliseconds stored as an integral number, grouped in UTC, groups on the field itself:
     * `date_histogram` reads it as epoch milliseconds, so no script runs per document, and it sends no `time_zone`,
     * which a numeric field refuses. Another zone, an array or a nested element keeps the runtime date field (a
     * floating-point field is not admitted for an epoch at all).
     */
    @Test
    fun `epoch millis on an integral field groups natively in UTC while other epochs keep the runtime date`() {
        val millis = QueryValueSchema(
            QueryValueKind.SCALAR,
            valueTypes = setOf(QueryValueType.INTEGER),
            semanticType = Temporal.Epoch(TimeUnit.MILLISECONDS),
        )
        val bound = ElasticsearchQuerySchemaAdapter.bind(
            LogicalQuerySchema(
                obj(
                    mapOf(
                        "deleted" to QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.BOOLEAN)),
                        "createdAt" to millis,
                        "seenAt" to array(millis),
                        "orders" to array(obj(mapOf("createdAt" to millis))),
                    )
                )
            ),
            ElasticsearchIndexMapping.from(
                "test",
                TypeMapping.of {
                    it.properties("deleted") { it.boolean_ { it } }
                        .properties("createdAt") { it.long_ { it } }
                        .properties("seenAt") { it.long_ { it } }
                        .properties("orders") {
                            it.nested { orders -> orders.properties("createdAt") { it.long_ { it } } }
                        }
                }
            ),
        )
        fun daily(field: String, zone: ZoneId = ZoneOffset.UTC) = compiler.compile(
            aggregation {
                dateHistogram(field, AggregationDateUnit.DAY, "day", zone)
                count("count")
            },
            bound,
        )
        listOf(ZoneOffset.UTC, ZoneId.of("UTC"), ZoneId.of("Etc/UTC")).forEach { zone ->
            val native = daily("createdAt", zone)
            native.groupSources.single().value().dateHistogram().apply {
                field().assert().isEqualTo("createdAt")
                timeZone().assert().isNull()
            }
            native.runtimeMappings.assert().isEmpty()
        }

        val zoned = daily("createdAt", ZoneId.of("Asia/Shanghai"))
        zoned.groupSources.single().value().dateHistogram().apply {
            field().assert().isEqualTo("__wow_date_histogram_0")
            timeZone().assert().isEqualTo("Asia/Shanghai")
        }

        val array = daily("seenAt")
        array.groupSources.single().value().dateHistogram().field().assert().isEqualTo("__wow_date_histogram_0")
        array.runtimeMappings.keys.assert().containsExactly("__wow_date_histogram_0")

        val nested = compiler.compile(
            aggregation {
                expand("orders")
                dateHistogram("createdAt", AggregationDateUnit.DAY, "day")
                count("count")
            },
            bound,
        )
        nested.groupSources.single().value().dateHistogram().field().assert().isEqualTo("__wow_date_histogram_0")
        nested.runtimeMappings.keys.assert().containsExactly("__wow_date_histogram_0")
    }

    @Test
    fun `date part groups by a long runtime field computed in the time zone`() {
        val plan = compiler.compile(
            aggregation {
                datePart("createdAt", AggregationDatePart.DAY_OF_WEEK, "weekday", java.time.ZoneId.of("Asia/Shanghai"))
                count("count")
            },
            schema
        )
        plan.groupSources.single().value().terms().field().assert().isEqualTo("__wow_date_part_0")
        val runtime = plan.runtimeMappings.getValue("__wow_date_part_0")
        runtime.type().assert().isEqualTo(RuntimeFieldType.Long)
        val script = requireNotNull(runtime.script())
        script.params().values.map { it.to(Any::class.java) }.assert()
            .contains("createdAt", "Asia/Shanghai", "DAY_OF_WEEK", 1L, 1_000L)
        requireNotNull(script.source()).scriptString().assert().contains("size() == 1")
            .contains("Double.isFinite").contains("getDayOfWeek().getValue()").contains("ZoneId.of(params.zone)")
    }

    @Test
    fun `terms and any consume logical names and choose keyword native binding`() {
        val plan = compiler.compile(
            aggregation {
                terms("name", "name")
                any("name", "sample")
            },
            schema
        )
        plan.groupSources.single().value().terms().field().assert().isEqualTo("name.keyword")
        (plan.metrics.single() as ElasticsearchAggregationMetric.Any).field.assert().isEqualTo("name.keyword")
        assertThrows<QuerySchemaValidationException> {
            compiler.compile(aggregation { any("name.keyword", "sample") }, schema)
        }
    }

    @Test
    fun `terms missingKey routes the source through a keyword runtime field`() {
        val plan = compiler.compile(
            aggregation {
                terms("name", "name", missingKey = "UNKNOWN")
                count("count")
            },
            schema,
        )
        val runtime = plan.runtimeMappings.getValue("__wow_missing_terms_0")
        runtime.type().assert().isEqualTo(RuntimeFieldType.Keyword)
        val script = requireNotNull(runtime.script())
        script.params().values.map { it.to(Any::class.java) }.assert().contains("name.keyword", "UNKNOWN")
        requireNotNull(script.source()).scriptString().assert()
            .contains("doc.containsKey")
            .contains("size() == 1")
            .contains("emit(")
            .contains("params.missing")
        plan.groupSources.single().value().terms().field().assert().isEqualTo("__wow_missing_terms_0")
    }

    @Test
    fun `terms without missingKey keeps the physical field`() {
        val plan = compiler.compile(
            aggregation {
                terms("name", "name")
                count("count")
            },
            schema,
        )
        plan.groupSources.single().value().terms().field().assert().isEqualTo("name.keyword")
        plan.runtimeMappings.containsKey("__wow_missing_terms_0").assert().isFalse()
        plan.runtimeMappings.assert().isEmpty()
    }
}
