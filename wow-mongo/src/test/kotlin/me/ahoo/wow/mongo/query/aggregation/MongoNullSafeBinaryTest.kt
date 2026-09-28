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

package me.ahoo.wow.mongo.query.aggregation

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.mongo.query.mongoTestSchema
import me.ahoo.wow.query.QueryAdmission
import org.bson.BsonDocument
import org.bson.Document
import org.junit.jupiter.api.Test

/**
 * Pins the exact native document of a null-safe binary operation on both of its paths — record-level
 * [AggregationExpression] and post-group [DerivedExpression] — including the division-by-zero guard.
 */
class MongoNullSafeBinaryTest {
    private val admitted = QueryAdmission.Trusted.aggregate(
        AggregationQuery(metrics = listOf(AggregationMetric.Count("count"))),
        mongoTestSchema(fields = emptyMap()),
    )

    @Test
    fun `record expression divide guards nulls and a zero divisor`() {
        val expression = AggregationExpression.Binary(
            AggregationExpressionOperator.DIVIDE,
            AggregationExpression.Constant(6.0),
            AggregationExpression.Constant(2.0),
        )

        expression.toMongoExpression(admitted).assertJson(guarded("\$divide", "6.0", "2.0", divide = true))
    }

    @Test
    fun `record expression add guards nulls only`() {
        val expression = AggregationExpression.Binary(
            AggregationExpressionOperator.ADD,
            AggregationExpression.Constant(1.0),
            AggregationExpression.Constant(2.0),
        )

        expression.toMongoExpression(admitted).assertJson(guarded("\$add", "1.0", "2.0", divide = false))
    }

    @Test
    fun `derived divide guards nulls and a zero divisor`() {
        derivedDocument(AggregationExpressionOperator.DIVIDE)
            .assertJson(guarded("\$divide", "\"\$count\"", "{\"\$literal\": 2.0}", divide = true))
    }

    @Test
    fun `derived subtract guards nulls only`() {
        derivedDocument(AggregationExpressionOperator.SUBTRACT)
            .assertJson(guarded("\$subtract", "\"\$count\"", "{\"\$literal\": 2.0}", divide = false))
    }

    private fun derivedDocument(operator: AggregationExpressionOperator): Any {
        val derived = AggregationMetric.Derived(
            "ratio",
            DerivedExpression.Binary(operator, DerivedExpression.MetricRef("count"), DerivedExpression.Constant(2.0)),
        )
        val query = AggregationQuery(metrics = listOf(AggregationMetric.Count("count"), derived))
        return derivedProject(query, derived).toBsonDocument()
            .getDocument("\$project")
            .getDocument("ratio")
    }

    private fun Any.assertJson(expected: String) {
        val actual = when (this) {
            is BsonDocument -> this
            is Document -> toBsonDocument()
            else -> throw AssertionError("unexpected document type: ${this::class}")
        }
        actual.toJson().assert().isEqualTo(BsonDocument.parse(expected).toJson())
    }

    private fun guarded(mongoOperator: String, left: String, right: String, divide: Boolean): String {
        val zeroGuard = if (divide) ", {\"\$ne\": [\"\$\$right\", 0.0]}" else ""
        return """
            {"${'$'}let": {
              "vars": {"value": {"${'$'}let": {
                "vars": {"left": $left, "right": $right},
                "in": {"${'$'}cond": [
                  {"${'$'}and": [{"${'$'}ne": ["${'$'}${'$'}left", null]}, {"${'$'}ne": ["${'$'}${'$'}right", null]}$zeroGuard]},
                  {"$mongoOperator": ["${'$'}${'$'}left", "${'$'}${'$'}right"]},
                  null
                ]}
              }}},
              "in": {"${'$'}cond": [
                {"${'$'}and": [
                  {"${'$'}ne": ["${'$'}${'$'}value", null]},
                  {"${'$'}gte": ["${'$'}${'$'}value", -1.7976931348623157E308]},
                  {"${'$'}lte": ["${'$'}${'$'}value", 1.7976931348623157E308]}
                ]},
                "${'$'}${'$'}value",
                null
              ]}
            }}
        """.trimIndent()
    }
}
