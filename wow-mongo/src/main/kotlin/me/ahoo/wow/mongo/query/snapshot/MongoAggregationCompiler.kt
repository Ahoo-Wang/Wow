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

import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FieldType
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.mongo.query.AbstractMongoFilterConverter
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.Decimal128
import java.util.concurrent.TimeUnit

internal class MongoAggregationCompiler(
    private val converter: AbstractMongoFilterConverter,
) {
    fun compile(query: AggregationQuery): List<Bson> = buildList {
        add(Aggregates.match(converter.convert(query.filter)))

        var parent: String? = null
        query.elements.forEach { element ->
            parent = if (parent == null) element.path.name else "$parent.${element.path.name}"
            add(Aggregates.unwind("\$$parent"))
            if (element.filter !== MatchAllFilter) {
                add(Aggregates.match(converter.convertWithoutDefaultDeletion(element.filter, parent)))
            }
        }

        val numericDateHistograms = query.groupBy.mapIndexedNotNull { index, group ->
            (group as? AggregationGroup.DateHistogram)
                ?.takeIf { it.field.temporalTypeOrDefault() is FieldType.Temporal.NumericEpoch }
                ?.let { index to it }
        }
        if (numericDateHistograms.isNotEmpty()) {
            add(
                Document(
                    "\$set",
                    numericDateHistograms.associateTo(Document()) { (index, group) ->
                        dateHistogramField(index) to group.numericDate(parent)
                    },
                ),
            )
        }

        if (query.groupBy.isNotEmpty()) {
            val groupFields = query.groupBy.map { it.field.resolve(parent) } +
                numericDateHistograms.map { (index) -> dateHistogramField(index) }
            add(
                Aggregates.match(
                    Filters.and(groupFields.flatMap { listOf(Filters.exists(it), Filters.ne(it, null)) }),
                ),
            )
        }

        add(group(query, parent))
        add(project(query))
        query.effectiveSort().takeIf { it.isNotEmpty() }?.let { add(Aggregates.sort(it.toBson())) }
        add(Aggregates.limit(query.limit))
    }

    private fun group(query: AggregationQuery, parent: String?): Bson {
        val id = query.groupBy
            .takeIf { it.isNotEmpty() }
            ?.let { groups ->
                Document().apply {
                    groups.forEachIndexed { index, group ->
                        this[group.alias] = group.expression(parent, index)
                    }
                }
            }
        val group = Document("_id", id)
        query.metrics.forEach { metric ->
            when (metric) {
                is AggregationMetric.Count -> group[metric.alias] = Document("\$sum", 1)
                is AggregationMetric.Numeric -> {
                    val (input, contributes) = metric.toMongoInput(parent)
                    group[metric.alias] = Document("\$${metric.function.name.lowercase()}", input)
                    group[metric.countAlias] = Document(
                        "\$sum",
                        Document("\$cond", listOf(contributes, 1, 0)),
                    )
                }
            }
        }
        return Document("\$group", group)
    }

    private fun project(query: AggregationQuery): Bson {
        val project = Document("_id", 0)
        query.groupBy.forEach { project[it.alias] = "\$_id.${it.alias}" }
        query.metrics.forEach { metric ->
            project[metric.alias] = when (metric) {
                is AggregationMetric.Count -> 1
                is AggregationMetric.Numeric -> Document(
                    "\$cond",
                    listOf(Document("\$eq", listOf("\$${metric.countAlias}", 0)), null, "\$${metric.alias}"),
                )
            }
        }
        return Aggregates.project(project)
    }

    private fun AggregationGroup.expression(parent: String?, groupIndex: Int): Any = when (this) {
        is AggregationGroup.Terms -> "\$${field.resolve(parent)}"
        is AggregationGroup.Histogram -> Document(
            "\$multiply",
            listOf(
                Document("\$floor", Document("\$divide", listOf("\$${field.resolve(parent)}", interval))),
                interval,
            ),
        )

        is AggregationGroup.DateHistogram -> Document(
            "\$toLong",
            Document(
                "\$dateTrunc",
                Document("date", dateInput(parent, groupIndex))
                    .append("unit", unit.name.lowercase())
                    .append("timezone", if (timeZone == "Z") "UTC" else timeZone)
                    .apply {
                        if (unit == AggregationDateUnit.WEEK) append("startOfWeek", "Monday")
                    },
            ),
        )
    }

    private fun AggregationGroup.DateHistogram.dateInput(parent: String?, groupIndex: Int): String =
        when (field.temporalTypeOrDefault()) {
            FieldType.Temporal.Date -> "\$${field.resolve(parent)}"
            is FieldType.Temporal.NumericEpoch -> "\$${dateHistogramField(groupIndex)}"
            is FieldType.Temporal.FormattedString -> error("DateHistogram does not support TEMPORAL_STRING fields.")
        }

    private fun AggregationGroup.DateHistogram.numericDate(parent: String?): Document {
        val fieldReference = "\$${field.resolve(parent)}"
        val singleton = Document(
            "\$cond",
            listOf(
                Document("\$eq", listOf(Document("\$size", fieldReference), 1)),
                Document("\$arrayElemAt", listOf(fieldReference, 0)),
                null,
            ),
        )
        val raw = Document("\$cond", listOf(Document("\$isArray", fieldReference), singleton, fieldReference))
        val integer = convert(raw, "long")
        val timeUnit = (field.temporalTypeOrDefault() as FieldType.Temporal.NumericEpoch).timeUnit
        val decimal = convert("\$\$integer", "decimal")
        val epochMillis = when (timeUnit) {
            TimeUnit.NANOSECONDS -> Document(
                "\$trunc",
                listOf(Document("\$divide", listOf(decimal, Decimal128(1_000_000L)))),
            )

            TimeUnit.MICROSECONDS -> Document(
                "\$trunc",
                listOf(Document("\$divide", listOf(decimal, Decimal128(1_000L)))),
            )

            TimeUnit.MILLISECONDS -> Document("\$multiply", listOf(decimal, Decimal128(1L)))
            TimeUnit.SECONDS -> Document("\$multiply", listOf(decimal, Decimal128(1_000L)))
            TimeUnit.MINUTES -> Document("\$multiply", listOf(decimal, Decimal128(60_000L)))
            TimeUnit.HOURS -> Document("\$multiply", listOf(decimal, Decimal128(3_600_000L)))
            TimeUnit.DAYS -> Document("\$multiply", listOf(decimal, Decimal128(86_400_000L)))
        }
        return Document(
            "\$let",
            Document("vars", Document("raw", raw).append("integer", integer))
                .append(
                    "in",
                    Document(
                        "\$cond",
                        listOf(
                            Document(
                                "\$and",
                                listOf(
                                    Document("\$isNumber", "\$\$raw"),
                                    Document("\$ne", listOf("\$\$integer", null)),
                                    Document("\$eq", listOf("\$\$raw", "\$\$integer")),
                                ),
                            ),
                            convert(convert(epochMillis, "long"), "date"),
                            null,
                        ),
                    ),
                ),
        )
    }

    private fun convert(input: Any, target: String): Document = Document(
        "\$convert",
        Document("input", input)
            .append("to", target)
            .append("onError", null)
            .append("onNull", null),
    )

    private fun AggregationMetric.Numeric.toMongoInput(parent: String?): Pair<Any, Any> {
        val metricExpression = expression
        if (metricExpression is AggregationExpression.Field) {
            val field = metricExpression.field.resolve(parent)
            val isNumber = Document("\$isNumber", "\$$field")
            val input = when (function) {
                AggregationFunction.MIN,
                AggregationFunction.MAX,
                -> Document("\$cond", listOf(isNumber, "\$$field", null))

                else -> "\$$field"
            }
            return input to isNumber
        }
        val input = metricExpression.toMongoExpression(parent)
        return input to Document("\$ne", listOf(input, null))
    }

    private fun AggregationExpression.toMongoExpression(parent: String?): Any = when (this) {
        is AggregationExpression.Field -> {
            val field = field.resolve(parent)
            val fieldReference = "\$$field"
            val isSingleton = Document("\$eq", listOf(Document("\$size", fieldReference), 1))
            val singleton = Document(
                "\$cond",
                listOf(isSingleton, Document("\$arrayElemAt", listOf(fieldReference, 0)), null),
            )
            val value = Document("\$cond", listOf(Document("\$isArray", fieldReference), singleton, fieldReference))
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
            val leftValue = left.toMongoExpression(parent)
            val rightValue = right.toMongoExpression(parent)
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

        else -> error("Unsupported aggregation expression: ${this::class.java.name}.")
    }

    private val AggregationExpressionOperator.mongoOperator: String
        get() = when (this) {
            AggregationExpressionOperator.ADD -> "\$add"
            AggregationExpressionOperator.SUBTRACT -> "\$subtract"
            AggregationExpressionOperator.MULTIPLY -> "\$multiply"
            AggregationExpressionOperator.DIVIDE -> "\$divide"
        }

    private fun finiteDouble(input: Any): Document = Document(
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

    private fun LogicalField.resolve(parent: String?): String =
        SnapshotFieldConverter.convert(if (parent == null) name else "$parent.$name")

    private fun List<Sort>.toBson(): Bson = Sorts.orderBy(
        map {
            when (it.direction) {
                Sort.Direction.ASC -> Sorts.ascending(it.field)
                Sort.Direction.DESC -> Sorts.descending(it.field)
            }
        }
    )

    private val AggregationMetric.Numeric.countAlias: String
        get() = "__wow_value_count_$alias"

    private fun dateHistogramField(groupIndex: Int): String = "__wow_date_histogram_$groupIndex"
}
