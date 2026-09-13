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
import com.mongodb.client.model.densify.DensifyOptions
import com.mongodb.client.model.densify.DensifyRange
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.mongo.query.AbstractMongoFilterCompiler
import me.ahoo.wow.query.aggregation.DenseDateGrid
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.requireScalarMetricFilterFields
import org.bson.Document
import org.bson.conversions.Bson
import java.time.Instant
import java.time.ZoneId

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

        val dense = query.groupBy.singleOrNull()?.let { it as? AggregationGroup.DateHistogram }?.takeIf { it.dense }
            ?.let { DenseHistogramFill(it, DenseDateGrid(it.unit, ZoneId.of(it.timeZone))) }

        val groupId = query.groupBy.takeIf { it.isNotEmpty() }?.let { groups ->
            val id = Document()
            val filters = groups.mapNotNull { group ->
                val (filter, expression) = group.compile(logicalParent, physicalParent, schema, dense?.grid)
                id[group.alias] = expression
                filter
            }
            if (filters.isNotEmpty()) {
                add(Aggregates.match(Filters.and(filters)))
            }
            id
        }

        add(group(query, groupId, logicalParent, physicalParent, schema, now))
        if (dense != null) {
            addAll(denseStages(dense))
        }
        add(project(query, dense))
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
     * The single dense date histogram of [AggregationQuery.groupBy] together with its grid: the
     * pair exists exactly when groupBy is that one dense histogram, so later stages never
     * null-check or identity-match the two halves against each other.
     */
    internal class DenseHistogramFill(val group: AggregationGroup.DateHistogram, val grid: DenseDateGrid)

    /**
     * Stages that carry the grouped bucket index through numeric densification: the index moves
     * out of `_id` onto the alias, then `$densify` fills every missing integer between the data
     * min and max (`bounds: "full"` keeps the window interior-gap-only). The closing `$match`
     * drops densify-synthetic documents whose index does not round-trip — see [denseRoundTripMatch].
     */
    private fun denseStages(dense: DenseHistogramFill): List<Bson> = listOf(
        Aggregates.set(Field(dense.group.alias, "\$_id.${dense.group.alias}")),
        Aggregates.densify(
            dense.group.alias,
            DensifyRange.fullRangeWithStep(1L),
            DensifyOptions.densifyOptions(),
        ),
        Aggregates.match(denseRoundTripMatch(dense.group, dense.grid)),
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
}
