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

import com.mongodb.client.model.Accumulators
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.BsonField
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import com.mongodb.client.model.Sorts
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
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

        var logicalParent: QueryField? = null
        var physicalParent: String? = null
        query.elements.forEach { element ->
            val previousLogicalParent = logicalParent
            logicalParent = element.path.absoluteTo(previousLogicalParent)
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
            val groupFilters = query.groupBy.map { group ->
                when (group) {
                    is AggregationGroup.Histogram -> {
                        val field = group.field.resolve(logicalParent, schema, group.capability)
                        Filters.expr(Document("\$isNumber", scalarOrSingleton("\$$field")))
                    }
                    is AggregationGroup.DateHistogram -> Filters.expr(
                        Document("\$ne", listOf(group.dateInput(logicalParent, schema), null)),
                    )
                    else -> {
                        val field = group.field.resolve(logicalParent, schema, group.capability)
                        Filters.and(Filters.exists(field), Filters.ne(field, null))
                    }
                }
            }
            add(Aggregates.match(Filters.and(groupFilters)))
        }

        add(group(query, logicalParent, schema))
        add(project(query))
        query.effectiveSort().takeIf { it.isNotEmpty() }?.let { add(Aggregates.sort(it.toBson())) }
        add(Aggregates.limit(query.limit))
    }

    private fun group(query: AggregationQuery, parent: QueryField?, schema: QueryModelSchema?): Bson {
        val id = query.groupBy
            .takeIf { it.isNotEmpty() }
            ?.associateTo(Document()) { it.alias to it.expression(parent, schema) }
        val accumulators = buildList {
            query.metrics.forEach { metric ->
                when (metric) {
                    is AggregationMetric.Count -> add(Accumulators.sum(metric.alias, 1))
                    is AggregationMetric.Any -> add(
                        Accumulators.max(
                            metric.alias,
                            "\$${metric.field.resolve(parent, schema, QueryCapability.AGGREGATE_TERMS)}",
                        ),
                    )
                    is AggregationMetric.Numeric -> {
                        val (input, contributes) = metric.toMongoInput(parent, schema)
                        add(metric.function.accumulate(metric.alias, input))
                        add(
                            Accumulators.sum(
                                metric.countAlias,
                                Document("\$cond", listOf(contributes, 1, 0)),
                            ),
                        )
                    }
                }
            }
        }
        return Aggregates.group(id, accumulators)
    }

    private fun project(query: AggregationQuery): Bson {
        val projections = buildList {
            add(Projections.excludeId())
            query.groupBy.forEach { add(Projections.computed(it.alias, "\$_id.${it.alias}")) }
            query.metrics.forEach { metric ->
                add(
                    when (metric) {
                        is AggregationMetric.Count -> Projections.include(metric.alias)
                        is AggregationMetric.Any -> Projections.include(metric.alias)
                        is AggregationMetric.Numeric -> Projections.computed(
                            metric.alias,
                            Document(
                                "\$cond",
                                listOf(
                                    Document("\$eq", listOf("\$${metric.countAlias}", 0)),
                                    null,
                                    "\$${metric.alias}",
                                ),
                            ),
                        )
                    },
                )
            }
        }
        return Aggregates.project(Projections.fields(projections))
    }

    private fun AggregationFunction.accumulate(field: String, input: Any): BsonField = when (this) {
        AggregationFunction.SUM -> Accumulators.sum(field, input)
        AggregationFunction.AVG -> Accumulators.avg(field, input)
        AggregationFunction.MIN -> Accumulators.min(field, input)
        AggregationFunction.MAX -> Accumulators.max(field, input)
    }

    private fun AggregationGroup.expression(parent: QueryField?, schema: QueryModelSchema?): Any = when (this) {
        is AggregationGroup.Terms -> "\$${field.resolve(parent, schema, QueryCapability.AGGREGATE_TERMS)}"
        is AggregationGroup.Histogram -> {
            val field = field.resolve(parent, schema, QueryCapability.AGGREGATE_NUMERIC)
            Document(
                "\$multiply",
                listOf(
                    Document(
                        "\$floor",
                        Document(
                            "\$divide",
                            listOf(scalarOrSingleton("\$$field"), interval),
                        ),
                    ),
                    interval,
                ),
            )
        }

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
        parent: QueryField?,
        schema: QueryModelSchema?,
    ): Pair<Any, Any> {
        val metricExpression = expression
        if (metricExpression is AggregationExpression.Field) {
            val field = metricExpression.field.resolve(parent, schema, QueryCapability.AGGREGATE_NUMERIC)
            val value = scalarOrSingleton("\$$field")
            val isNumber = Document("\$isNumber", value)
            val input = when (function) {
                AggregationFunction.MIN,
                AggregationFunction.MAX,
                -> Document("\$cond", listOf(isNumber, value, null))

                else -> value
            }
            return input to isNumber
        }
        val input = metricExpression.toMongoExpression(parent, schema)
        return input to Document("\$ne", listOf(input, null))
    }

    private fun AggregationExpression.toMongoExpression(parent: QueryField?, schema: QueryModelSchema?): Any = when (this) {
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

    private fun AggregationGroup.DateHistogram.dateInput(parent: QueryField?, schema: QueryModelSchema?): Any {
        val logicalField = field.absoluteTo(parent)
        val fieldSchema = schema?.field(logicalField)
        if (fieldSchema == null) {
            return Document("\$toDate", "\$${converter.convertField(logicalField.path)}")
        }
        val physicalPath = fieldSchema.binding(QueryCapability.AGGREGATE_TEMPORAL)?.physicalField?.path
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
        Document("\$floor", Document("\$divide", listOf(convert(epoch, "decimal"), divisor))),
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

    private fun QueryField.resolve(
        parent: QueryField?,
        schema: QueryModelSchema?,
        capability: QueryCapability,
    ): String {
        val logicalField = absoluteTo(parent)
        schema?.field(logicalField)?.binding(capability)?.physicalField?.path?.let { return it }
        if (schema == null || logicalField !in schema.fields) {
            return converter.convertField(logicalField.path)
        }
        throw QuerySchemaValidationException("Query field [$logicalField] does not support [$capability].")
    }

    private val AggregationGroup.capability: QueryCapability
        get() = when (this) {
            is AggregationGroup.Terms -> QueryCapability.AGGREGATE_TERMS
            is AggregationGroup.Histogram -> QueryCapability.AGGREGATE_NUMERIC
            is AggregationGroup.DateHistogram -> QueryCapability.AGGREGATE_TEMPORAL
        }

    private fun List<Sort>.toBson(): Bson = Sorts.orderBy(
        map {
            when (it.direction) {
                Sort.Direction.ASC -> Sorts.ascending(it.field.path)
                Sort.Direction.DESC -> Sorts.descending(it.field.path)
            }
        }
    )

    private val AggregationMetric.Numeric.countAlias: String
        get() = "__wow_value_count_$alias"
}
