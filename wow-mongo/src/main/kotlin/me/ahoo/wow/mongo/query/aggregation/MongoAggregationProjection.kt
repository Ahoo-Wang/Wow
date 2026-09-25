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

import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.query.aggregation.DenseDateGrid
import org.bson.Document
import org.bson.conversions.Bson
import java.util.Date

/**
 * Compiles the fill projection right after `$group`. Every metric projection reads the
 * accumulated value through `$ifNull` with its empty-semantics fallback — a no-op rewrite on
 * grouped documents, which always carry the accumulated fields, that gives `$densify`-synthetic
 * documents the empty value of their metric. A dense date histogram additionally inverts its
 * bucket index back into the display key with `$dateAdd(timezone)` — `$dateFromParts(timezone)`
 * for HOUR, keeping the grid on the local wall clock — mirroring [DenseDateGrid.keyOf].
 */
@Suppress("LongMethod")
internal fun project(
    query: AggregationQuery,
    dense: MongoAggregationCompiler.DenseHistogramFill?,
): Bson {
    val projections = buildList {
        add(Projections.excludeId())
        if (dense == null) {
            query.groupBy.forEach { group ->
                add(Projections.computed(group.alias, "\$_id.${group.alias}"))
            }
        } else {
            // dense exists only when groupBy is exactly the single dense date histogram
            add(Projections.computed(dense.group.alias, denseKeyProjection(dense.group, dense.grid)))
        }
        query.metrics.forEach { metric ->
            when (metric) {
                is AggregationMetric.Derived -> Unit
                is AggregationMetric.Count -> add(
                    Projections.computed(metric.alias, Document("\$ifNull", listOf("\$${metric.alias}", 0L))),
                )
                is AggregationMetric.Any -> add(Projections.include(metric.alias))
                is AggregationMetric.Edge -> add(
                    Projections.computed(metric.alias, Document("\$ifNull", listOf("\$${metric.alias}", null))),
                )
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
 * A grid point must be an EXISTING local time: a zone that skipped a whole local date (e.g.
 * Pacific/Apia 2011-12-30) collapses the synthetic index onto the NEXT real bucket under
 * `$dateAdd`, so the fill-aware `$project` inversion would emit a duplicate of that bucket's
 * key. Synthetic documents whose index does not round-trip are therefore dropped BEFORE the
 * inversion. Real documents always round-trip — their index derives from `$dateTrunc` of an
 * existing local time — so the stage is a no-op for them. Dense HOUR grids round-trip through
 * the wall-clock [denseHourKey] inversion and [wallHourIndex], dropping wall hours skipped
 * whole by a DST gap. `$dateAdd` takes no `startOfWeek` parameter; `$dateDiff` counts week
 * boundaries on it, so WEEK passes Monday explicitly.
 */
internal fun denseRoundTripMatch(group: AggregationGroup.DateHistogram, grid: DenseDateGrid): Bson {
    if (group.unit == AggregationDateUnit.HOUR) {
        val key = denseHourKey(group, grid, "\$${group.alias}")
        return Filters.expr(
            Document(
                "\$eq",
                listOf(
                    wallHourIndex(Date.from(grid.anchor.toInstant()), key, mongoTimeZone(group.timeZone)),
                    "\$${group.alias}",
                ),
            ),
        )
    }
    val dateDiff = Document(
        "\$dateDiff",
        Document("startDate", Date.from(grid.anchor.toInstant()))
            .append("endDate", denseDateAdd(group, grid, "\$${group.alias}"))
            .append("unit", group.unit.name.lowercase())
            .append("timezone", mongoTimeZone(group.timeZone))
            .apply { if (group.unit == AggregationDateUnit.WEEK) append("startOfWeek", "Monday") }
    )
    return Filters.expr(Document("\$eq", listOf(dateDiff, "\$${group.alias}")))
}

private fun denseKeyProjection(group: AggregationGroup.DateHistogram, grid: DenseDateGrid): Document = Document(
    "\$toLong",
    if (group.unit == AggregationDateUnit.HOUR) {
        denseHourKey(group, grid, "\$${group.alias}")
    } else {
        denseDateAdd(group, grid, "\$${group.alias}")
    },
)

private fun denseDateAdd(group: AggregationGroup.DateHistogram, grid: DenseDateGrid, amount: Any): Document =
    Document(
        "\$dateAdd",
        Document("startDate", Date.from(grid.anchor.toInstant()))
            .append("unit", group.unit.name.lowercase())
            .append("amount", amount)
            .append("timezone", mongoTimeZone(group.timeZone)),
    )

/**
 * Compiles [derived] into its own `$project` stage: computed fields of one `$project`
 * document cannot reference each other, so declaration order becomes evaluation order
 * by staging every derived metric after the metrics it references. The stage carries
 * the group aliases and every other declared metric alias forward — inclusion-mode
 * `$project` drops unlisted fields, and later stages never restore them.
 */
internal fun derivedProject(query: AggregationQuery, derived: AggregationMetric.Derived): Bson {
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
