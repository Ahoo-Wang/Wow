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
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.mongo.query.AbstractMongoFilterConverter
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import org.bson.Document
import org.bson.conversions.Bson
import java.util.concurrent.TimeUnit

internal class MongoAggregationCompiler(
    private val converter: AbstractMongoFilterConverter,
) {
    fun compile(query: AggregationQuery, schema: QueryModelSchema? = null): List<Bson> = buildList {
        add(Aggregates.match(converter.convert(query.filter)))

        var logicalParent: String? = null
        var physicalParent: String? = null
        query.elements.forEach { element ->
            val previousLogicalParent = logicalParent
            logicalParent = if (logicalParent == null) {
                element.path.value
            } else {
                "$logicalParent.${element.path.value}"
            }
            physicalParent = element.path.resolve(
                parent = previousLogicalParent,
                schema = schema,
                capability = QueryCapability.ELEMENT_SCOPE,
            )
            add(Aggregates.unwind("\$$physicalParent"))
            if (element.filter !== MatchAllFilter) {
                add(Aggregates.match(converter.convertWithoutDefaultDeletion(element.filter, physicalParent)))
            }
        }

        if (query.groupBy.isNotEmpty()) {
            val groupFields = query.groupBy.map { group ->
                group.field.resolve(logicalParent, schema, group.capability)
            }
            add(
                Aggregates.match(
                    Filters.and(groupFields.flatMap { listOf(Filters.exists(it), Filters.ne(it, null)) }),
                ),
            )
        }

        add(group(query, logicalParent, schema))
        add(project(query))
        query.effectiveSort().takeIf { it.isNotEmpty() }?.let { add(Aggregates.sort(it.toBson())) }
        add(Aggregates.limit(query.limit))
    }

    private fun group(query: AggregationQuery, parent: String?, schema: QueryModelSchema?): Bson {
        val id = query.groupBy
            .takeIf { it.isNotEmpty() }
            ?.associateTo(Document()) { it.alias to it.expression(parent, schema) }
        val group = Document("_id", id)
        query.metrics.forEach { metric ->
            when (metric) {
                is AggregationMetric.Count -> group[metric.alias] = Document("\$sum", 1)
                is AggregationMetric.Numeric -> {
                    val (input, contributes) = metric.toMongoInput(parent, schema)
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

    private fun AggregationGroup.expression(parent: String?, schema: QueryModelSchema?): Any = when (this) {
        is AggregationGroup.Terms -> "\$${field.resolve(parent, schema, QueryCapability.AGGREGATE_TERMS)}"
        is AggregationGroup.Histogram -> Document(
            "\$multiply",
            listOf(
                Document(
                    "\$floor",
                    Document(
                        "\$divide",
                        listOf("\$${field.resolve(parent, schema, QueryCapability.AGGREGATE_NUMERIC)}", interval),
                    ),
                ),
                interval,
            ),
        )

        is AggregationGroup.DateHistogram -> Document(
            "\$toLong",
            Document(
                "\$dateTrunc",
                Document("date", dateInput(parent, schema))
                    .append("unit", unit.name.lowercase())
                    .append("timezone", if (timeZone == "Z") "UTC" else timeZone)
                    .apply {
                        if (unit == AggregationDateUnit.WEEK) append("startOfWeek", "Monday")
                    },
            ),
        )
    }

    private fun AggregationMetric.Numeric.toMongoInput(
        parent: String?,
        schema: QueryModelSchema?,
    ): Pair<Any, Any> {
        val metricExpression = expression
        if (metricExpression is AggregationExpression.Field) {
            val field = metricExpression.field.resolve(parent, schema, QueryCapability.AGGREGATE_NUMERIC)
            val isNumber = Document("\$isNumber", "\$$field")
            val input = when (function) {
                AggregationFunction.MIN,
                AggregationFunction.MAX,
                -> Document("\$cond", listOf(isNumber, "\$$field", null))

                else -> "\$$field"
            }
            return input to isNumber
        }
        val input = metricExpression.toMongoExpression(parent, schema)
        return input to Document("\$ne", listOf(input, null))
    }

    private fun AggregationExpression.toMongoExpression(parent: String?, schema: QueryModelSchema?): Any = when (this) {
        is AggregationExpression.Field -> {
            val field = field.resolve(parent, schema, QueryCapability.AGGREGATE_NUMERIC)
            val fieldReference = "\$$field"
            val value = scalarOrSingleton(fieldReference)
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
            val leftValue = left.toMongoExpression(parent, schema)
            val rightValue = right.toMongoExpression(parent, schema)
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

    private fun AggregationGroup.DateHistogram.dateInput(parent: String?, schema: QueryModelSchema?): Any {
        val logicalField = field.absolute(parent)
        val fieldSchema = schema?.resolve(logicalField)
        if (fieldSchema == null) {
            return Document("\$toDate", "\$${SnapshotFieldConverter.convert(logicalField.value)}")
        }
        val physicalPath = fieldSchema.bindings[QueryCapability.AGGREGATE_TEMPORAL]?.physicalPath
            ?: throw QuerySchemaValidationException(
                "Query field [$logicalField] does not support [${QueryCapability.AGGREGATE_TEMPORAL}].",
            )
        return when (val semanticType = fieldSchema.semanticType) {
            Temporal.Date -> convert(scalarOrSingleton("\$$physicalPath"), "date")
            is Temporal.Epoch -> epochDate(physicalPath, semanticType.timeUnit)
            else -> throw QuerySchemaValidationException(
                "Query field [$logicalField] does not have a supported temporal semantic type.",
            )
        }
    }

    private fun epochDate(physicalPath: String, timeUnit: TimeUnit): Document {
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
        Document("\$floor", Document("\$divide", listOf(epoch, divisor))),
        "long",
    )

    private fun multiplyToLong(epoch: String, multiplier: Long): Document = convert(
        Document("\$multiply", listOf(epoch, multiplier)),
        "long",
    )

    private fun scalarOrSingleton(fieldReference: String): Document {
        val isSingleton = Document("\$eq", listOf(Document("\$size", fieldReference), 1))
        val singleton = Document(
            "\$cond",
            listOf(isSingleton, Document("\$arrayElemAt", listOf(fieldReference, 0)), null),
        )
        return Document("\$cond", listOf(Document("\$isArray", fieldReference), singleton, fieldReference))
    }

    private fun convert(input: Any, type: String): Document = Document(
        "\$convert",
        Document("input", input)
            .append("to", type)
            .append("onError", null)
            .append("onNull", null),
    )

    private fun LogicalField.resolve(
        parent: String?,
        schema: QueryModelSchema?,
        capability: QueryCapability,
    ): String {
        val logicalField = absolute(parent)
        val fieldSchema = schema?.resolve(logicalField)
            ?: return SnapshotFieldConverter.convert(logicalField.value)
        return fieldSchema.bindings[capability]?.physicalPath
            ?: throw QuerySchemaValidationException("Query field [$logicalField] does not support [$capability].")
    }

    private fun LogicalField.absolute(parent: String?): LogicalField =
        LogicalField(if (parent == null) value else "$parent.$value")

    private val AggregationGroup.capability: QueryCapability
        get() = when (this) {
            is AggregationGroup.Terms -> QueryCapability.AGGREGATE_TERMS
            is AggregationGroup.Histogram -> QueryCapability.AGGREGATE_NUMERIC
            is AggregationGroup.DateHistogram -> QueryCapability.AGGREGATE_TEMPORAL
        }

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
}
