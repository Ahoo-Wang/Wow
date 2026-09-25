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

import com.mongodb.client.model.Sorts
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.distinctCountCapability
import me.ahoo.wow.query.schema.scopedPhysicalField
import org.bson.Document
import org.bson.conversions.Bson
import java.util.concurrent.TimeUnit

internal fun numericParticipation(
    expression: AggregationExpression,
    nullGuarded: Boolean,
    parent: QueryField?,
    physicalParent: String?,
    schema: QueryModelSchema,
): Pair<Any, Any> {
    if (expression is AggregationExpression.Field) {
        val field = expression.field.resolve(
            parent,
            physicalParent,
            schema,
            QueryCapability.AGGREGATE_NUMERIC,
        )
        val value = numericInput("\$$field")
        val isNumber = Document("\$isNumber", value)
        val input = if (nullGuarded) Document("\$cond", listOf(isNumber, value, null)) else value
        return input to isNumber
    }
    val input = expression.toMongoExpression(parent, physicalParent, schema)
    return input to Document("\$ne", listOf(input, null))
}

internal fun distinctCountInput(
    expression: AggregationExpression,
    parent: QueryField?,
    physicalParent: String?,
    schema: QueryModelSchema,
): Any = if (expression is AggregationExpression.Field) {
    val capability = schema.distinctCountCapability(expression.field, parent)
    "\$${expression.field.resolve(parent, physicalParent, schema, capability)}"
} else {
    expression.toMongoExpression(parent, physicalParent, schema)
}

private fun AggregationExpression.toMongoExpression(
    parent: QueryField?,
    physicalParent: String?,
    schema: QueryModelSchema,
): Any = when (this) {
    is AggregationExpression.Field -> {
        val field = field.resolve(parent, physicalParent, schema, QueryCapability.AGGREGATE_NUMERIC)
        val fieldReference = "\$$field"
        val value = numericInput(fieldReference)
        finiteDouble(
            Document(
                "\$cond",
                listOf(
                    Document("\$isNumber", value),
                    Document(
                        "\$convert",
                        Document("input", value)
                            .append("to", "double")
                            .append("onError", null)
                            .append("onNull", null),
                    ),
                    null,
                ),
            ),
        )
    }

    is AggregationExpression.Constant -> value
    is AggregationExpression.Binary -> {
        val leftValue = left.toMongoExpression(parent, physicalParent, schema)
        val rightValue = right.toMongoExpression(parent, physicalParent, schema)
        val conditions = mutableListOf<Any>(
            Document("\$ne", listOf("\$\$left", null)),
            Document("\$ne", listOf("\$\$right", null)),
        )
        if (operator == AggregationExpressionOperator.DIVIDE) {
            conditions += Document("\$ne", listOf("\$\$right", 0.0))
        }
        finiteDouble(
            Document(
                "\$let",
                Document("vars", Document("left", leftValue).append("right", rightValue))
                    .append(
                        "in",
                        Document(
                            "\$cond",
                            listOf(
                                Document("\$and", conditions),
                                Document(operator.mongoOperator, listOf("\$\$left", "\$\$right")),
                                null,
                            ),
                        ),
                    ),
            ),
        )
    }
}

internal val AggregationExpressionOperator.mongoOperator: String
    get() = when (this) {
        AggregationExpressionOperator.ADD -> "\$add"
        AggregationExpressionOperator.SUBTRACT -> "\$subtract"
        AggregationExpressionOperator.MULTIPLY -> "\$multiply"
        AggregationExpressionOperator.DIVIDE -> "\$divide"
    }

internal fun finiteDouble(input: Any): Document = Document(
    "\$let",
    Document("vars", Document("value", input))
        .append(
            "in",
            Document(
                "\$cond",
                listOf(
                    Document(
                        "\$and",
                        listOf(
                            Document("\$ne", listOf("\$\$value", null)),
                            Document("\$gte", listOf("\$\$value", -Double.MAX_VALUE)),
                            Document("\$lte", listOf("\$\$value", Double.MAX_VALUE)),
                        ),
                    ),
                    "\$\$value",
                    null,
                ),
            ),
        ),
)

internal fun epochDate(physicalPath: String, timeUnit: TimeUnit): Document {
    val value = scalarOrSingleton("\$$physicalPath")
    return Document(
        "\$let",
        Document("vars", Document("value", value)).append(
            "in",
            Document(
                "\$cond",
                listOf(
                    Document("\$isNumber", "\$\$value"),
                    Document(
                        "\$let",
                        Document("vars", Document("epoch", convert("\$\$value", "long"))).append(
                            "in",
                            Document(
                                "\$cond",
                                listOf(
                                    Document(
                                        "\$and",
                                        listOf(
                                            Document("\$ne", listOf("\$\$epoch", null)),
                                            Document("\$eq", listOf("\$\$epoch", "\$\$value")),
                                        ),
                                    ),
                                    convert(timeUnit.toEpochMillis("\$\$epoch"), "date"),
                                    null,
                                ),
                            ),
                        ),
                    ),
                    null,
                ),
            ),
        ),
    )
}

private fun TimeUnit.toEpochMillis(epoch: String): Any = when (this) {
    TimeUnit.NANOSECONDS -> floorDivide(epoch, 1_000_000L)
    TimeUnit.MICROSECONDS -> floorDivide(epoch, 1_000L)
    TimeUnit.MILLISECONDS -> epoch
    TimeUnit.SECONDS -> multiplyToLong(epoch, 1_000L)
    TimeUnit.MINUTES -> multiplyToLong(epoch, 60_000L)
    TimeUnit.HOURS -> multiplyToLong(epoch, 3_600_000L)
    TimeUnit.DAYS -> multiplyToLong(epoch, 86_400_000L)
}

private fun floorDivide(epoch: String, divisor: Long): Document = convert(
    Document("\$floor", Document("\$divide", listOf(convert(epoch, "decimal"), divisor))),
    "long",
)

private fun multiplyToLong(epoch: String, multiplier: Long): Document = convert(
    Document("\$multiply", listOf(epoch, multiplier)),
    "long",
)

private fun numericInput(fieldReference: String): Document = Document(
    "\$cond",
    listOf(
        Document("\$isArray", fieldReference),
        Document(
            "\$let",
            Document(
                "vars",
                Document(
                    "values",
                    Document(
                        "\$filter",
                        Document("input", fieldReference)
                            .append("cond", Document("\$ne", listOf("\$\$this", null)))
                    )
                ),
            ).append("in", scalarOrSingleton("\$\$values")),
        ),
        fieldReference,
    ),
)

internal fun scalarOrSingleton(fieldReference: String): Document {
    val isSingleton = Document("\$eq", listOf(Document("\$size", fieldReference), 1))
    val singleton = Document(
        "\$cond",
        listOf(isSingleton, Document("\$arrayElemAt", listOf(fieldReference, 0)), null),
    )
    return Document("\$cond", listOf(Document("\$isArray", fieldReference), singleton, fieldReference))
}

internal fun convert(input: Any, type: String): Document = Document(
    "\$convert",
    Document("input", input)
        .append("to", type)
        .append("onError", null)
        .append("onNull", null),
)

internal fun QueryField.resolve(
    parent: QueryField?,
    physicalParent: String?,
    schema: QueryModelSchema,
    capability: QueryCapability,
): String = schema.scopedPhysicalField(this, capability, parent, physicalParent?.let(::QueryField)).path

internal fun List<Sort>.toBson(): Bson = Sorts.orderBy(
    map {
        when (it.direction) {
            Sort.Direction.ASC -> Sorts.ascending(it.field.path)
            Sort.Direction.DESC -> Sorts.descending(it.field.path)
        }
    }
)

internal val AggregationMetric.countAlias: String
    get() = "__wow_value_count_$alias"
