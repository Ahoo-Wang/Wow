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
import com.mongodb.client.model.Field
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.densify.DensifyOptions
import com.mongodb.client.model.densify.DensifyRange
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.mongo.query.AbstractMongoFilterCompiler
import me.ahoo.wow.query.aggregation.DenseDateGrid
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.distinctCountCapability
import me.ahoo.wow.query.schema.operationValues
import me.ahoo.wow.query.schema.physicalField
import me.ahoo.wow.query.schema.requireScalarMetricFilterFields
import org.bson.Document
import org.bson.conversions.Bson
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.concurrent.TimeUnit

@Suppress("LargeClass")
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

        val denseGroup = query.groupBy.singleOrNull()?.let { it as? AggregationGroup.DateHistogram }?.takeIf { it.dense }
        val denseGrid = denseGroup?.let { DenseDateGrid(it.unit, ZoneId.of(it.timeZone)) }

        val groupId = query.groupBy.takeIf { it.isNotEmpty() }?.let { groups ->
            val id = Document()
            val filters = groups.mapNotNull { group ->
                val (filter, expression) = group.compile(logicalParent, physicalParent, schema, denseGrid)
                id[group.alias] = expression
                filter
            }
            if (filters.isNotEmpty()) {
                add(Aggregates.match(Filters.and(filters)))
            }
            id
        }

        add(group(query, groupId, logicalParent, physicalParent, schema, now))
        if (denseGroup != null && denseGrid != null) {
            addAll(denseStages(denseGroup))
        }
        add(project(query, denseGroup, denseGrid))
        query.metrics.forEach { metric ->
            if (metric is AggregationMetric.Derived) {
                add(derivedProject(query, metric))
            }
        }
        query.having?.let { add(Aggregates.match(it.toHavingDocument())) }
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
        now: Instant,
    ): Bson {
        val accumulators = buildList {
            query.metrics.forEach { metric ->
                val guard = metricFilter(metric, parent, physicalParent, schema, now)
                    ?.toGuardCondition()
                when (metric) {
                    is AggregationMetric.Count -> add(
                        if (guard == null) {
                            Accumulators.sum(metric.alias, 1)
                        } else {
                            Accumulators.sum(metric.alias, Document("\$cond", listOf(guard, 1, 0)))
                        },
                    )
                    is AggregationMetric.Any -> {
                        val field = metric.field.resolve(
                            parent,
                            physicalParent,
                            schema,
                            QueryCapability.AGGREGATE_TERMS,
                        )
                        add(
                            if (guard == null) {
                                Accumulators.max(metric.alias, "\$$field")
                            } else {
                                Accumulators.max(metric.alias, Document("\$cond", listOf(guard, "\$$field", null)))
                            },
                        )
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
                        val guardedInput = guard.wrapParticipation(input)
                        add(metric.function.accumulate(metric.alias, guardedInput))
                        add(
                            Accumulators.sum(
                                metric.countAlias,
                                Document("\$cond", listOf(guard.wrapContribution(contributes), 1, 0)),
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
                                    Document("input", guard.wrapParticipation(input))
                                        .append("p", listOf(metric.percentile / 100.0))
                                        .append("method", "approximate"),
                                ),
                            ),
                        )
                        add(
                            Accumulators.sum(
                                metric.countAlias,
                                Document("\$cond", listOf(guard.wrapContribution(contributes), 1, 0)),
                            ),
                        )
                    }
                    is AggregationMetric.DistinctCount -> {
                        add(
                            Accumulators.addToSet(
                                metric.alias,
                                guard.wrapParticipation(
                                    distinctCountInput(metric.expression, parent, physicalParent, schema),
                                ),
                            ),
                        )
                    }
                    is AggregationMetric.Derived -> Unit
                }
            }
        }
        return Aggregates.group(id, accumulators)
    }

    /**
     * Compiles the record-level filter of [metric] against its enclosing scope,
     * or returns `null` for [MatchAllFilter] so unfiltered metrics keep their unwrapped accumulators.
     */
    private fun metricFilter(
        metric: AggregationMetric,
        parent: QueryField?,
        physicalParent: String?,
        schema: QueryModelSchema,
        now: Instant,
    ): Bson? {
        val filter = metric.filter
        if (filter === MatchAllFilter) {
            return null
        }
        filter.requireScalarMetricFilterFields(parent, schema)
        if (parent == null) {
            return filterCompiler.compile(filter, schema, now)
        }
        return filterCompiler.compileScoped(
            filter,
            schema,
            logicalParent = parent,
            physicalParent = QueryField(requireNotNull(physicalParent)),
            now = now,
        )
    }

    /**
     * Wraps a participating value so records rejected by the filter contribute `null` instead.
     */
    private fun Any?.wrapParticipation(input: Any): Any = if (this == null) {
        input
    } else {
        Document("\$cond", listOf(this, input, null))
    }

    /**
     * Extends a contribution predicate with the filter so rejected records add zero to the value count.
     */
    private fun Any?.wrapContribution(contributes: Any): Any = if (this == null) {
        contributes
    } else {
        Document("\$and", listOf(this, contributes))
    }

    /**
     * Compiles the fill projection right after `$group`. Every metric projection reads the
     * accumulated value through `$ifNull` with its empty-semantics fallback — a no-op rewrite on
     * grouped documents, which always carry the accumulated fields, that gives `$densify`-synthetic
     * documents the empty value of their metric. A dense date histogram additionally inverts its
     * bucket index back into the display key with `$dateAdd(timezone)`, mirroring [DenseDateGrid.keyOf].
     */
    @Suppress("LongMethod")
    private fun project(
        query: AggregationQuery,
        denseGroup: AggregationGroup.DateHistogram?,
        denseGrid: DenseDateGrid?,
    ): Bson {
        val denseKey = denseGroup?.let { group -> denseGrid?.let { grid -> denseKeyProjection(group, grid) } }
        val projections = buildList {
            add(Projections.excludeId())
            query.groupBy.forEach { group ->
                add(
                    if (group === denseGroup && denseKey != null) {
                        Projections.computed(group.alias, denseKey)
                    } else {
                        Projections.computed(group.alias, "\$_id.${group.alias}")
                    },
                )
            }
            query.metrics.forEach { metric ->
                when (metric) {
                    is AggregationMetric.Derived -> Unit
                    is AggregationMetric.Count -> add(
                        Projections.computed(metric.alias, Document("\$ifNull", listOf("\$${metric.alias}", 0L))),
                    )
                    is AggregationMetric.Any -> add(Projections.include(metric.alias))
                    is AggregationMetric.Numeric -> {
                        val accumulated: Any = if (metric.function == AggregationFunction.VARIANCE) {
                            Document("\$pow", listOf("\$${metric.alias}", 2))
                        } else {
                            "\$${metric.alias}"
                        }
                        add(
                            Projections.computed(
                                metric.alias,
                                Document(
                                    "\$cond",
                                    listOf(
                                        Document(
                                            "\$eq",
                                            listOf(Document("\$ifNull", listOf("\$${metric.countAlias}", 0L)), 0),
                                        ),
                                        null,
                                        accumulated,
                                    ),
                                ),
                            ),
                        )
                    }
                    is AggregationMetric.Percentile -> add(
                        Projections.computed(
                            metric.alias,
                            Document(
                                "\$cond",
                                listOf(
                                    Document(
                                        "\$eq",
                                        listOf(Document("\$ifNull", listOf("\$${metric.countAlias}", 0L)), 0),
                                    ),
                                    null,
                                    Document(
                                        "\$arrayElemAt",
                                        listOf(Document("\$ifNull", listOf("\$${metric.alias}", emptyList<Any>())), 0),
                                    ),
                                ),
                            ),
                        ),
                    )
                    is AggregationMetric.DistinctCount -> add(
                        Projections.computed(
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
                                                    Document(
                                                        "input",
                                                        Document(
                                                            "\$ifNull",
                                                            listOf("\$${metric.alias}", emptyList<Any>())
                                                        ),
                                                    )
                                                        .append("initialValue", emptyList<Any>())
                                                        .append(
                                                            "in",
                                                            Document(
                                                                "\$concatArrays",
                                                                listOf(
                                                                    "\$\$value",
                                                                    Document(
                                                                        "\$cond",
                                                                        listOf(
                                                                            Document("\$isArray", "\$\$this"),
                                                                            "\$\$this",
                                                                            Document(
                                                                                "\$cond",
                                                                                listOf(
                                                                                    Document(
                                                                                        "\$eq",
                                                                                        listOf("\$\$this", null)
                                                                                    ),
                                                                                    emptyList<Any>(),
                                                                                    listOf("\$\$this"),
                                                                                ),
                                                                            ),
                                                                        ),
                                                                    ),
                                                                ),
                                                            ),
                                                        ),
                                                ),
                                            ).append("cond", Document("\$ne", listOf("\$\$this", null))),
                                        ),
                                    ),
                                ),
                            ),
                        )
                    )
                }
            }
        }
        return Aggregates.project(Projections.fields(projections))
    }

    /**
     * Stages that carry the grouped bucket index through numeric densification: the index moves
     * out of `_id` onto the alias, then `$densify` fills every missing integer between the data
     * min and max (`bounds: "full"` keeps the window interior-gap-only).
     */
    private fun denseStages(denseGroup: AggregationGroup.DateHistogram): List<Bson> = listOf(
        Aggregates.set(Field(denseGroup.alias, "\$_id.${denseGroup.alias}")),
        Aggregates.densify(
            denseGroup.alias,
            DensifyRange.fullRangeWithStep(1L),
            DensifyOptions.densifyOptions(),
        ),
    )

    private fun denseKeyProjection(group: AggregationGroup.DateHistogram, grid: DenseDateGrid): Document = Document(
        "\$toLong",
        Document(
            "\$dateAdd",
            Document("startDate", Date.from(grid.anchor.toInstant()))
                .append("unit", group.unit.name.lowercase())
                .append("amount", "\$${group.alias}")
                .append("timezone", mongoTimeZone(group.timeZone)),
        ),
    )

    private fun AggregationFunction.accumulate(field: String, input: Any): BsonField = when (this) {
        AggregationFunction.SUM -> Accumulators.sum(field, input)
        AggregationFunction.AVG -> Accumulators.avg(field, input)
        AggregationFunction.MIN -> Accumulators.min(field, input)
        AggregationFunction.MAX -> Accumulators.max(field, input)
        AggregationFunction.STDDEV,
        AggregationFunction.VARIANCE,
        -> BsonField(field, Document("\$stdDevPop", input))
    }

    /**
     * Compiles [derived] into its own `$project` stage: computed fields of one `$project`
     * document cannot reference each other, so declaration order becomes evaluation order
     * by staging every derived metric after the metrics it references. The stage carries
     * the group aliases and every other declared metric alias forward — inclusion-mode
     * `$project` drops unlisted fields, and later stages never restore them.
     */
    private fun derivedProject(query: AggregationQuery, derived: AggregationMetric.Derived): Bson {
        val projections = buildList {
            add(Projections.excludeId())
            query.groupBy.forEach { add(Projections.include(it.alias)) }
            query.metrics.forEach { metric ->
                add(
                    if (metric === derived) {
                        Projections.computed(metric.alias, metric.expression.toDerivedDocument())
                    } else {
                        Projections.include(metric.alias)
                    },
                )
            }
        }
        return Aggregates.project(Projections.fields(projections))
    }

    /**
     * Null propagation, divide-by-zero, and finiteness mirror the record-level [AggregationExpression]
     * guards: referenced metrics have already been projected to their guarded final values by
     * [project] or an earlier [derivedProject] stage.
     */
    private fun DerivedExpression.toDerivedDocument(): Any = when (this) {
        is DerivedExpression.MetricRef -> "\$$metric"

        /**
         * A bare number is an include flag in `$project`, so constants must be pinned with `$literal`
         * to evaluate as values in computed fields and `$let` variables.
         */
        is DerivedExpression.Constant -> Document("\$literal", value)
        is DerivedExpression.Binary -> {
            val leftValue = left.toDerivedDocument()
            val rightValue = right.toDerivedDocument()
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

    /**
     * Compiles [HavingExpression] into the post-derivation `$match` filter over projected metric
     * aliases. Snapshot floats are stored as Decimal128 (the writer maps JSON floats to
     * BigDecimal), and MongoDB compares a Decimal128 metric against the BSON-double condition
     * value in decimal space — `Decimal128(0.8) $gte 0.8` is false although both round-trip as
     * the same wire double. Every numeric condition therefore wraps the metric in `$toDouble`
     * under `$expr`, so comparisons follow the IEEE-double semantics of the surfaced metric value
     * and the Elasticsearch evaluator. The BSON comparison total order still ranks `null` below
     * every number — a bare `$lt`/`$lte`/`$ne` would match it — so every numeric form conjoins a
     * `$ne: null` guard. [HavingExpression.IsNull] is the only unguarded form: the first
     * `$project` and every derived stage carry ALL metric aliases forward, so the alias always
     * exists and `Document(metric, null)` is IS NULL while `Filters.ne(metric, null)` is NOT NULL.
     */
    private fun HavingExpression.toHavingDocument(): Bson = when (this) {
        is HavingExpression.And -> Filters.and(operands.map { it.toHavingDocument() })
        is HavingExpression.Or -> Filters.or(operands.map { it.toHavingDocument() })
        is HavingExpression.IsNull -> if (negated) {
            Filters.ne(metric, null)
        } else {
            Document(metric, null)
        }
        is HavingExpression.Condition -> numericHavingMatch(metric) {
            Document(operator.matchOperator, listOf(it, value))
        }
        is HavingExpression.Between -> numericHavingMatch(metric) {
            Filters.and(
                Document("\$gte", listOf(it, lower)),
                Document("\$lte", listOf(it, upper)),
            )
        }
        is HavingExpression.In -> numericHavingMatch(metric) {
            Document("\$in", listOf(it, values))
        }
    }

    private fun numericHavingMatch(metric: String, condition: (toDouble: Document) -> Bson): Bson = Document(
        "\$expr",
        Filters.and(
            Document("\$ne", listOf("\$$metric", null)),
            condition(Document("\$toDouble", "\$$metric")),
        ),
    )

    private val ComparisonOperator.matchOperator: String
        get() = when (this) {
            ComparisonOperator.EQ -> "\$eq"
            ComparisonOperator.NE -> "\$ne"
            ComparisonOperator.GT -> "\$gt"
            ComparisonOperator.GTE -> "\$gte"
            ComparisonOperator.LT -> "\$lt"
            ComparisonOperator.LTE -> "\$lte"
        }

    private fun AggregationGroup.compile(
        parent: QueryField?,
        physicalParent: String?,
        schema: QueryModelSchema,
        denseGrid: DenseDateGrid?,
    ): Pair<Bson?, Any> = when (this) {
        is AggregationGroup.Terms -> {
            val path = field.resolve(parent, physicalParent, schema, QueryCapability.AGGREGATE_TERMS)
            if (missingKey == null) {
                Filters.and(Filters.exists(path), Filters.ne(path, null)) to "\$$path"
            } else {
                null to Document("\$ifNull", listOf("\$$path", missingKey))
            }
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
            if (denseGrid != null) {
                // `$densify` has no timezone option, so dense histograms group by the integer
                // bucket index (`$dateDiff` from the [DenseDateGrid.anchor]) and densify numerically;
                // the index is inverted back into the display key by [denseKeyProjection].
                Filters.expr(Document("\$ne", listOf(input, null))) to Document(
                    "\$dateDiff",
                    Document("startDate", Date.from(denseGrid.anchor.toInstant()))
                        .append("endDate", Document("\$dateTrunc", truncation))
                        .append("unit", unit.name.lowercase())
                        .append("timezone", mongoTimeZone(timeZone))
                        .apply { if (unit == AggregationDateUnit.WEEK) append("startOfWeek", "Monday") },
                )
            } else {
                Filters.expr(Document("\$ne", listOf(input, null))) to
                    Document("\$toLong", Document("\$dateTrunc", truncation))
            }
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
