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

package me.ahoo.wow.schema.typed.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AggregationTimeZones
import me.ahoo.wow.example.domain.order.Order
import me.ahoo.wow.schema.JsonSchema.Companion.asJsonSchema
import me.ahoo.wow.schema.SchemaGeneratorBuilder
import me.ahoo.wow.schema.typed.SnapshotAggregationElements
import me.ahoo.wow.schema.typed.SnapshotAggregationNumericFields
import me.ahoo.wow.schema.typed.SnapshotAggregationTemporalFields
import me.ahoo.wow.schema.typed.SnapshotAggregationTermsFields
import me.ahoo.wow.tck.mock.MockCommandAggregate
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

class AggregatedAggregationQueryTest {
    private val generator = SchemaGeneratorBuilder().build()
    private val termsField = object : SnapshotAggregationTermsFields<Order> {}
    private val numericField = object : SnapshotAggregationNumericFields<Order> {}
    private val temporalField = object : SnapshotAggregationTemporalFields<Order> {}
    private val element = object : SnapshotAggregationElements<Order> {}

    private fun JsonNode.nodes(): List<JsonNode> = (this as Iterable<JsonNode>).toList()

    @Test
    fun `should construct every typed aggregation subtype`() {
        val query = AggregatedAggregationQuery(
            elements = listOf(AggregatedAggregationElement(element)),
            groupBy = listOf(
                AggregatedAggregationGroup.Terms(termsField, "terms"),
                AggregatedAggregationGroup.Histogram(numericField, "histogram", 10.0),
                AggregatedAggregationGroup.DateHistogram(temporalField, "date", AggregationDateUnit.DAY),
            ),
            metrics = listOf(
                AggregatedAggregationMetric.Count("count"),
                AggregatedAggregationMetric.Numeric(
                    AggregationFunction.SUM,
                    AggregatedAggregationExpression.Field(numericField),
                    "sum",
                ),
            ),
        )

        query.elements.single().path.assert().isSameAs(element)
        query.groupBy.map { it.alias }.assert().containsExactly("terms", "histogram", "date")
        query.metrics.map { it.alias }.assert().containsExactly("count", "sum")
    }

    @Test
    fun `should generate typed aggregation query`() {
        val schema = generator.generateSchema(
            AggregatedAggregationQuery::class.java,
            Order::class.java,
        ).asJsonSchema()

        schema.actual.toString().assert().contains(
            "filter",
            "elements",
            "groupBy",
            "metrics",
            "sort",
            "limit",
            "DATE_HISTOGRAM",
            "NUMERIC",
            "FIELD",
        )
    }

    @Test
    fun `should publish only supported element filter operators`() {
        val schema = generator.generateSchema(
            AggregationElementFilterExpressionSchema::class.java,
        ).asJsonSchema().actual
        val oneOf = schema.path("definitions").path("filterExpression").path("oneOf")
        val references = (oneOf as Iterable<tools.jackson.databind.JsonNode>)
            .map { it.path("\$ref").stringValue().substringAfterLast('/') }

        references.assert().contains("and", "eq", "between", "today", "earlierDays")
        references.assert().doesNotContain(
            "containsAll",
            "isEmpty",
            "exists",
            "notExists",
            "deletion",
            "elementMatch",
            "search",
        )
        schema.path("definitions").has("search").assert().isFalse()
        schema.path("definitions").has("elementTimeZone").assert().isTrue()
    }

    @Test
    fun `should bind element filter fields to path and operator type`() {
        val schema = generator.generateSchema(
            AggregatedAggregationElement::class.java,
            MockCommandAggregate::class.java,
        ).asJsonSchema().actual
        val definitions = schema.path("definitions")

        fun element(path: String) = (schema.path("oneOf") as Iterable<tools.jackson.databind.JsonNode>)
            .single { it.path("properties").path("path").path("const").stringValue() == path }
        fun definition(element: tools.jackson.databind.JsonNode, name: String): tools.jackson.databind.JsonNode {
            val filterReference = element.path("properties").path("filter").path("\$ref").stringValue()
            val prefix = filterReference.substringAfterLast('/').removeSuffix("filterExpression")
            return definitions.path("$prefix$name")
        }
        fun fields(definition: tools.jackson.databind.JsonNode): List<String> =
            (definition.path("properties").path("field").path("enum") as Iterable<tools.jackson.databind.JsonNode>)
                .map { it.stringValue() }

        val orders = element("state.orders")
        fields(definition(orders, "contains")).assert().containsExactly("state.orders.status")

        val orderRanges = definition(orders, "gtShape").path("oneOf") as Iterable<tools.jackson.databind.JsonNode>
        fields(orderRanges.single { it.path("properties").path("value").path("type").stringValue() == "number" })
            .assert().containsExactly("state.orders.amount")
        fields(orderRanges.single { it.path("properties").path("value").path("type").stringValue() == "string" })
            .assert().containsExactly("state.orders.status")

        val lines = element("state.orders.lines")
        fields(definition(lines, "contains")).assert().containsExactly("state.orders.lines.sku")
        fields(definition(orders, "isNull")).assert().containsExactly("state.orders.status", "state.orders.amount")
        definition(orders, "exists").isMissingNode.assert().isTrue()
        definition(orders, "notExists").isMissingNode.assert().isTrue()
        definition(lines, "todayShape").path("properties").has("datePattern").assert().isFalse()
        val timeZoneReference = definition(lines, "todayShape").path("properties").path("zoneId")
            .path("\$ref").stringValue().substringAfterLast('/')
        val elementTimeZones = definitions.path(timeZoneReference).path("oneOf").nodes()
        val zoneIds = elementTimeZones.first { it.has("enum") }.path("enum").nodes().map { it.stringValue() }
        val offsetPattern = Regex(elementTimeZones.first { it.has("pattern") }.path("pattern").stringValue())
        zoneIds.assert().contains("UTC", "Asia/Shanghai")
        zoneIds.assert().doesNotContain("Not/AZone", "Z", "UTC+08:00")
        offsetPattern.matches("+08:00").assert().isTrue()
        listOf("Not/AZone", "Z", "UTC+08:00").forEach { invalid ->
            offsetPattern.matches(invalid).assert().isFalse()
        }

        val lineExact = definition(lines, "eqShape").path("oneOf").nodes()
        val rankExact = lineExact.single { variant ->
            fields(variant).contains("state.orders.lines.rank")
        }.path("properties").path("value")
        rankExact.path("type").stringValue().assert().isEqualTo("integer")
        rankExact.path("minimum").longValue().assert().isEqualTo(Int.MIN_VALUE.toLong())
        rankExact.path("maximum").longValue().assert().isEqualTo(Int.MAX_VALUE.toLong())
        fields(
            lineExact.single { variant ->
                variant.path("properties").path("value").path("type").toString().contains("boolean")
            },
        ).assert().containsExactly("state.orders.lines.cancelled")
    }

    @Test
    fun `should bind valid element chains and output fields to source`() {
        val schema = generator.generateSchema(
            AggregatedAggregationQuery::class.java,
            MockCommandAggregate::class.java,
        ).asJsonSchema().actual
        val sources = schema.path("oneOf").nodes()
        schema.path("properties").path("sort").path("maxItems").intValue().assert()
            .isEqualTo(AggregationQuery.MAX_SORT_FIELDS)
        val lineSource = sources.single {
            it.path("properties").path("elements").path("maxItems").intValue() == 2
        }
        val elementPaths = lineSource.path("properties").path("elements").path("prefixItems").nodes().map {
            it.path("allOf").path(1).path("properties").path("path").path("const").stringValue()
        }
        elementPaths.assert().containsExactly("state.orders", "state.orders.lines")

        val groupTypes = lineSource.path("properties").path("groupBy").path("items").path("oneOf").nodes()
        val terms = groupTypes.single {
            it.path("properties").path("type").path("const").stringValue() == "TERMS"
        }
        val termsFields = terms.path("properties").path("field").path("enum").nodes().map { it.stringValue() }
        termsFields.assert().contains(
            "state.orders.lines.sku",
            "state.orders.lines.rank",
            "state.orders.lines.amount",
            "state.orders.lines.cancelled",
        )
        termsFields.assert().doesNotContain("state.orders.status", "state.data")

        val metricTypes = lineSource.path("properties").path("metrics").path("items").path("oneOf").nodes()
        val numeric = metricTypes.single {
            it.path("properties").path("type").path("const").stringValue() == "NUMERIC"
        }
        val metricFields = numeric.path("properties").path("expression").path("properties")
            .path("field").path("enum").nodes().map { it.stringValue() }
        metricFields.assert().containsExactly("state.orders.lines.rank", "state.orders.lines.amount")
    }

    @Test
    fun `should publish runtime alias restrictions`() {
        val schema = generator.generateSchema(
            AggregatedAggregationQuery::class.java,
            Order::class.java,
        ).asJsonSchema().actual
        val aliases = schema.findValues("alias")

        aliases.assert().hasSize(5)
        aliases.map { it.path("minLength").intValue() }.distinct().assert().containsExactly(1)
        val pattern = Regex(aliases.map { it.path("pattern").stringValue() }.distinct().single())
        pattern.matches("totalAmount").assert().isTrue()
        listOf("", " ", "\t", "_id", "a.b", "\$value", "__wow_x", "nul\u0000alias")
            .forEach { alias -> pattern.matches(alias).assert().isFalse() }
    }

    @Test
    fun `should publish exact portable time zones`() {
        val schema = generator.generateSchema(AggregationTimeZoneSchema::class.java).asJsonSchema().actual
        val alternatives = schema.path("oneOf") as Iterable<tools.jackson.databind.JsonNode>
        val enumValues = alternatives.first { it.has("enum") }.path("enum")
        val zoneIds = (enumValues as Iterable<tools.jackson.databind.JsonNode>).map { it.stringValue() }
        val offsetPattern = Regex(alternatives.first { it.has("pattern") }.path("pattern").stringValue())

        zoneIds.assert().contains("UTC", "Asia/Shanghai")
        zoneIds.assert().doesNotContain("Z", "UTC+08:00")
        zoneIds.toSet().assert().isEqualTo(AggregationTimeZones.ids)
        listOf("+00:00", "-08:30", "+18:00").forEach { offset ->
            offsetPattern.matches(offset).assert().isTrue()
        }
        listOf("Z", "UTC+08:00", "+18:01", "+19:00").forEach { invalid ->
            offsetPattern.matches(invalid).assert().isFalse()
        }
    }

    @Test
    fun `should expose operation-specific field enums`() {
        val elements = generator.generateSchema(
            SnapshotAggregationElements::class.java,
            MockCommandAggregate::class.java,
        ).asJsonSchema().actual.toString()
        val terms = generator.generateSchema(
            SnapshotAggregationTermsFields::class.java,
            MockCommandAggregate::class.java,
        ).asJsonSchema().actual.toString()
        val numeric = generator.generateSchema(
            SnapshotAggregationNumericFields::class.java,
            MockCommandAggregate::class.java,
        ).asJsonSchema().actual.toString()
        val temporal = generator.generateSchema(
            SnapshotAggregationTemporalFields::class.java,
            MockCommandAggregate::class.java,
        ).asJsonSchema().actual.toString()

        elements.assert().contains("state.orders", "state.orders.lines")
        check("state.orders.lines.sku" !in elements)
        terms.assert().contains("state.orders.lines.sku", "state.orders.lines.cancelled")
        check("state.orders.lines.createdAt" !in terms)
        numeric.assert().contains("state.orders.lines.rank", "state.orders.lines.amount")
        check("state.orders.lines.sku" !in numeric)
        temporal.assert().contains("state.orders.lines.createdAt")
        check("state.orders.lines.amount" !in temporal)
    }
}
