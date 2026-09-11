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
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.mongo.query.AbstractMongoFilterCompiler
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.operationValues
import me.ahoo.wow.query.schema.physicalField
import org.bson.Document
import org.bson.conversions.Bson
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

internal class MongoAggregationCompiler(
    private val filterCompiler: AbstractMongoFilterCompiler,
) {
    fun compile(query: AggregationQuery, schema: QueryModelSchema): List<Bson> = compile(query, schema, Instant.now())

    internal fun compile(query: AggregationQuery, schema: QueryModelSchema, now: Instant): List<Bson> = buildList {
        add(Aggregates.match(filterCompiler.compile(query.filter, schema, now)))

        var logicalParent: QueryField? = null
        var physicalParent: String? = null
        query.elements.forEach { element ->
            val previousLogicalParent = logicalParent
            logicalParent = previousLogicalParent?.append(element.path) ?: element.path
            physicalParent = element.path.resolve(
                parent = previousLogicalParent,
                physicalParent = physicalParent,
                schema = schema,
                capability = QueryCapability.ELEMENT_SCOPE,
            )
            add(Aggregates.unwind("\$$physicalParent"))
            if (element.filter !== MatchAllFilter) {
                add(
                    Aggregates.match(
                        filterCompiler.compileScoped(
                            element.filter,
                            schema,
                            logicalParent = logicalParent,
                            physicalParent = QueryField(physicalParent),
                            now = now,
                        ),
                    ),
                )
            }
        }

        val groupId = query.groupBy.takeIf { it.isNotEmpty() }?.let { groups ->
            val id = Document()
            val filters = groups.map { group ->
                val (filter, expression) = group.compile(logicalParent, physicalParent, schema)
                id[group.alias] = expression
                filter
            }
            add(Aggregates.match(Filters.and(filters)))
            id
        }

        add(group(query, groupId, logicalParent, physicalParent, schema))
        add(project(query))
        query.effectiveSort().takeIf { it.isNotEmpty() }?.let { add(Aggregates.sort(it.toBson())) }
        add(Aggregates.limit(query.limit))
    }

    @Suppress("LongMethod")
    private fun group(
        query: AggregationQuery,
        id: Document?,
        parent: QueryField?,
        physicalParent: String?,
        schema: QueryModelSchema,
    ): Bson {
        val accumulators = buildList {
            query.metrics.forEach { metric ->
                when (metric) {
                    is AggregationMetric.Count -> add(Accumulators.sum(metric.alias, 1))
                    is AggregationMetric.Any -> {
                        val field = metric.field.resolve(
                            parent,
                            physicalParent,
                            schema,
                            QueryCapability.AGGREGATE_TERMS,
                        )
                        add(Accumulators.max(metric.alias, "\$$field"))
                    }
                    is AggregationMetric.Numeric -> {
                        val nullGuarded = metric.function == AggregationFunction.MIN ||
                            metric.function == AggregationFunction.MAX
                        val (input, contributes) = numericParticipation(
                            metric.expression,
                            nullGuarded,
                            parent,
                            physicalParent,
                            schema,
                        )
                        add(metric.function.accumulate(metric.alias, input))
                        add(
                            Accumulators.sum(
                                metric.countAlias,
                                Document("\$cond", listOf(contributes, 1, 0)),
                            ),
                        )
                    }
                    is AggregationMetric.Percentile -> {
                        val (input, contributes) = numericParticipation(
                            metric.expression,
                            nullGuarded = true,
                            parent,
                            physicalParent,
                            schema,
                        )
                        add(
                            BsonField(
                                metric.alias,
                                Document(
                                    "\$percentile",
                                    Document("input", input)
                                        .append("p", listOf(metric.percentile / 100.0))
                                        .append("method", "approximate"),
                                ),
                            ),
                        )
                        add(
                            Accumulators.sum(
                                metric.countAlias,
                                Document("\$cond", listOf(contributes, 1, 0)),
                            ),
                        )
                    }
                    is AggregationMetric.DistinctCount -> {
                        add(
                            Accumulators.push(
                                metric.alias,
                                distinctCountInput(metric.expression, parent, physicalParent, schema),
                            ),
                        )
                    }
                }
            }
        }
        return Aggregates.group(id, accumulators)
    }

    @Suppress("LongMethod")
    private fun project(query: AggregationQuery): Bson {
        val projections = buildList {
            add(Projections.excludeId())
            query.groupBy.forEach { add(Projections.computed(it.alias, "\$_id.${it.alias}")) }
            query.metrics.forEach { metric ->
                add(
                    when (metric) {
                        is AggregationMetric.Count -> Projections.include(metric.alias)
                        is AggregationMetric.Any -> Projections.include(metric.alias)
                        is AggregationMetric.Numeric -> {
                            val accumulated: Any = if (metric.function == AggregationFunction.VARIANCE) {
                                Document("\$pow", listOf("\$${metric.alias}", 2))
                            } else {
                                "\$${metric.alias}"
                            }
                            Projections.computed(
                                metric.alias,
                                Document(
                                    "\$cond",
                                    listOf(
                                        Document("\$eq", listOf("\$${metric.countAlias}", 0)),
                                        null,
                                        accumulated,
                                    ),
                                ),
                            )
                        }
                        is AggregationMetric.Percentile -> Projections.computed(
                            metric.alias,
                            Document(
                                "\$cond",
                                listOf(
                                    Document("\$eq", listOf("\$${metric.countAlias}", 0)),
                                    null,
                                    Document("\$arrayElemAt", listOf("\$${metric.alias}", 0)),
                                ),
                            ),
                        )
                        is AggregationMetric.DistinctCount -> Projections.computed(
                            metric.alias,
                            Document(
                                "\$size",
                                Document(
                                    "\$setUnion",
                                    listOf(
                                        Document(
                                            "\$filter",
                                            Document(
                                                "input",
                                                Document(
                                                    "\$reduce",
                                                    Document("input", "\$${metric.alias}")
                                                        .append("initialValue", emptyList<Any>())
                                                        .append(
                                                            "in",
                                                            Document(
                                                                "\$concatArrays",
                                                                listOf("\$\$value", "\$\$this"),
                                                            ),
                                                        ),
                                                ),
                                            ).append("cond", Document("\$ne", listOf("\$\$this", null))),
                                        ),
                                    ),
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
        AggregationFunction.STDDEV,
        AggregationFunction.VARIANCE,
        -> BsonField(field, Document("\$stdDevPop", input))
    }

    private fun AggregationGroup.compile(
        parent: QueryField?,
        physicalParent: String?,
        schema: QueryModelSchema,
    ): Pair<Bson, Any> = when (this) {
        is AggregationGroup.Terms -> {
            val path = field.resolve(parent, physicalParent, schema, QueryCapability.AGGREGATE_TERMS)
            Filters.and(Filters.exists(path), Filters.ne(path, null)) to "\$$path"
        }
        is AggregationGroup.Histogram -> {
            val path = field.resolve(parent, physicalParent, schema, QueryCapability.AGGREGATE_NUMERIC)
            val input = scalarOrSingleton("\$$path")
            Filters.expr(Document("\$isNumber", input)) to Document(
                "\$multiply",
                listOf(
                    Document(
                        "\$floor",
                        Document(
                            "\$divide",
                            listOf(input, interval),
                        ),
                    ),
                    interval,
                ),
            )
        }

        is AggregationGroup.DateHistogram -> {
            val input = dateInput(parent, physicalParent, schema)
            val truncation = Document("date", input)
                .append("unit", unit.name.lowercase())
                .append("timezone", mongoTimeZone(timeZone))
                .apply { if (unit == AggregationDateUnit.WEEK) append("startOfWeek", "Monday") }
            Filters.expr(Document("\$ne", listOf(input, null))) to
                Document("\$toLong", Document("\$dateTrunc", truncation))
        }
    }

    private fun numericParticipation(
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

    private fun distinctCountInput(
        expression: AggregationExpression,
        parent: QueryField?,
        physicalParent: String?,
        schema: QueryModelSchema,
    ): Any {
        val value: Any = if (expression is AggregationExpression.Field) {
            val logicalField = parent?.append(expression.field) ?: expression.field
            val capability = when {
                schema.field(logicalField)?.binding(QueryCapability.AGGREGATE_TERMS) != null ->
                    QueryCapability.AGGREGATE_TERMS
                else -> QueryCapability.AGGREGATE_NUMERIC
            }
            "\$${expression.field.resolve(parent, physicalParent, schema, capability)}"
        } else {
            expression.toMongoExpression(parent, physicalParent, schema)
        }
        return Document(
            "\$cond",
            listOf(
                Document("\$isArray", value),
                value,
                Document(
                    "\$cond",
                    listOf(
                        Document("\$eq", listOf(value, null)),
                        emptyList<Any>(),
                        listOf(value),
                    ),
                ),
            ),
        )
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

    private fun AggregationGroup.DateHistogram.dateInput(
        parent: QueryField?,
        physicalParent: String?,
        schema: QueryModelSchema,
    ): Any {
        val logicalField = parent?.append(field) ?: field
        val fieldSchema = schema.field(logicalField)
            ?: throw QuerySchemaValidationException("Unknown query field [$logicalField].")
        val physicalPath = field.resolve(parent, physicalParent, schema, QueryCapability.AGGREGATE_TEMPORAL)
        val values = fieldSchema.value.operationValues().filter { it.kind != QueryValueKind.NULL }
        val temporal = values.takeIf { domains -> domains.all { it.kind == QueryValueKind.SCALAR } }
            ?.map { it.semanticType }?.distinct()?.singleOrNull()
        return when (val semanticType = temporal) {
            Temporal.Date -> convert(scalarOrSingleton("\$$physicalPath"), "date")
            is Temporal.Epoch -> epochDate(physicalPath, semanticType.timeUnit)
            else -> throw QuerySchemaValidationException(
                "Query field [$logicalField] does not have a supported temporal semantic type.",
            )
        }
    }

    private fun mongoTimeZone(timeZone: String): String {
        val zone = ZoneId.of(timeZone).normalized()
        if (zone !is ZoneOffset) return timeZone
        if (zone.totalSeconds % 60 != 0) {
            throw QuerySchemaValidationException("MongoDB time zone offsets must use whole minutes.")
        }
        return if (zone == ZoneOffset.UTC) "UTC" else zone.id
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
        physicalParent: String?,
        schema: QueryModelSchema,
        capability: QueryCapability,
    ): String {
        val physical = schema.physicalField(this, capability, parent)
        if (physicalParent != null && physical.relativeTo(QueryField(physicalParent)) == null) {
            throw QuerySchemaValidationException(
                "Physical field [$physical] is outside element scope [$physicalParent]."
            )
        }
        return physical.path
    }

    private fun List<Sort>.toBson(): Bson = Sorts.orderBy(
        map {
            when (it.direction) {
                Sort.Direction.ASC -> Sorts.ascending(it.field.path)
                Sort.Direction.DESC -> Sorts.descending(it.field.path)
            }
        }
    )

    private val AggregationMetric.countAlias: String
        get() = "__wow_value_count_$alias"
}
