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

package me.ahoo.wow.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.LogicalOperator
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.PortableLogicalExpression
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.expression.StringComparisonMode
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryResultShape
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class CanonicalQueryJsonTest {
    @Test
    fun `canonical expression should round trip with the authoritative mapper`() {
        val expression: QueryExpression = PortableLogicalExpression(
            LogicalOperator.AND,
            listOf(
                MatchAll,
                PredicateExpression(
                    LogicalField("amount"),
                    PortableOperator.BETWEEN,
                    listOf(
                        QueryValue.DecimalValue(BigDecimal("1.25")),
                        QueryValue.InstantValue(Instant.parse("2026-08-12T00:00:00Z"))
                    )
                )
            )
        )

        val decoded = expression.toJsonString().toObject<QueryExpression>()

        decoded.assert().isEqualTo(expression)
    }

    @Test
    fun `predicate string comparison should round trip and default when absent from legacy JSON`() {
        val expression: QueryExpression = PredicateExpression(
            LogicalField("name"),
            PortableOperator.CONTAINS,
            listOf(QueryValue.StringValue("Wow")),
            StringComparisonMode.CASE_INSENSITIVE
        )

        val json = expression.toJsonString()
        val decoded = json.toObject<QueryExpression>() as PredicateExpression
        val legacyDecoded = """
            {
              "type": "predicate",
              "field": "name",
              "operator": "CONTAINS",
              "values": [{"type": "string", "value": "Wow"}]
            }
        """.trimIndent().toObject<QueryExpression>() as PredicateExpression

        JsonSerializer.readTree(json)["stringComparison"].stringValue().assert()
            .isEqualTo("CASE_INSENSITIVE")
        decoded.assert().isEqualTo(expression)
        decoded.stringComparison.assert().isEqualTo(StringComparisonMode.CASE_INSENSITIVE)
        legacyDecoded.stringComparison.assert().isEqualTo(StringComparisonMode.DEFAULT)
    }

    @Test
    fun `nested query value should round trip with the authoritative mapper`() {
        val value: QueryValue = QueryValue.ObjectValue(
            mapOf(
                "items" to QueryValue.ListValue(
                    listOf(QueryValue.StringValue("one"), QueryValue.BinaryValue(byteArrayOf(1, 2)))
                )
            )
        )

        val decoded = value.toJsonString().toObject<QueryValue>()

        decoded.assert().isEqualTo(value)
    }

    @Test
    fun `binary value JSON should expose exactly one payload field and round trip when nested`() {
        val value: QueryValue = QueryValue.ObjectValue(
            mapOf("binary" to QueryValue.BinaryValue(byteArrayOf(1, 2)))
        )

        val json = value.toJsonString()
        val tree = JsonSerializer.readTree(json)
        val binary = tree["values"]["binary"]

        binary.propertyNames().toSet().assert().containsExactlyInAnyOrder("type", "value")
        json.toObject<QueryValue>().assert().isEqualTo(value)
    }

    @Test
    fun `request serialization should expose only canonical safe data`() {
        val request = ListQueryRequest(
            target = QueryTarget(MaterializedNamedAggregate("sales", "order"), QueryDocumentKind.SNAPSHOT),
            resultShape = QueryResultShape.Typed(String::class.java),
            limit = 0
        )

        val json = request.toJsonString()

        json.assert().contains("\"documentKind\":\"SNAPSHOT\"")
        json.assert().contains("\"limit\":0")
        json.assert().doesNotContain("org.bson")
        json.assert().doesNotContain("elasticsearch")
        json.assert().doesNotContain("authority")
    }
}
