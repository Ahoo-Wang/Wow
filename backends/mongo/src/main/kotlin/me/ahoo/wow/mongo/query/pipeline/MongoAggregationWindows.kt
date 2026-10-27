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

package me.ahoo.wow.mongo.query.pipeline

import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.RunningFunction
import me.ahoo.wow.api.query.Sort
import org.bson.Document
import org.bson.conversions.Bson

/**
 * The windows of an aggregation's derived metrics, computed natively with `$setWindowFields` over the projected
 * groups: each into a `__wow_w<n>` field (the reserved prefix, so no alias collides) the derived metrics read and a final `$unset`
 * drops. They run after the projection — the dense fill included — and before HAVING, sort and limit, so they see
 * every group, as the core's residual windows do, with the same NULL rules: a NULL contributes nothing and a frame
 * of NULLs is NULL; a NULL has no rank; NULL sorts first ascending and last descending, ties broken by the
 * remaining groups ascending.
 */
internal class MongoWindows(private val query: AggregationQuery) {
    val windows: List<DerivedExpression.Window> = query.metrics
        .filterIsInstance<AggregationMetric.Derived>()
        .flatMap { it.expression.windows() }
        .distinct()

    /** The field [window] is computed into. */
    fun field(window: DerivedExpression.Window): String = "__wow_w${windows.indexOf(window)}"

    /** Every field the stages add, so each derived `$project` carries them forward and the end drops them. */
    val fields: List<String> = windows.indices.flatMap { listOf("__wow_w$it", "__wow_w${it}_n", "__wow_w${it}_k") }

    fun stages(): List<Bson> = windows.flatMap { window ->
        val field = field(window)
        val metric = "\$${window.metric}"
        when (window) {
            is DerivedExpression.Total -> summed(field, metric, partition(window.within), sortBy = null, frame = null)
            is DerivedExpression.Running -> {
                val sortBy = runningOrder(window)
                val frame = listOf<Any>(window.frame?.let { -(it - 1) } ?: "unbounded", "current")
                if (window.function == RunningFunction.AVG) {
                    listOf(windowFields(partition(window.within), sortBy, field, average(metric, frame)))
                } else {
                    summed(field, metric, partition(window.within), sortBy, frame)
                }
            }
            is DerivedExpression.Rank -> ranked(field, window, metric)
            is DerivedExpression.Previous -> error("PREVIOUS is computed by the core.")
        }
    }

    /** The end of the pipeline's window work: the `__wow_w` fields are no group's. */
    fun unset(): Bson = Document("\$unset", fields)

    /** A sum, NULL when no value in the frame is one: `$sum` alone answers 0 for a frame of NULLs. */
    private fun summed(
        field: String,
        metric: String,
        partition: Document?,
        sortBy: Document?,
        frame: List<Any>?
    ): List<Bson> {
        val window = frame?.let { Document("window", Document("documents", it)) }
        val sum = Document("\$sum", metric).apply { window?.let(::putAll) }
        val present = Document("\$sum", Document("\$cond", listOf(Document("\$ne", listOf(metric, null)), 1, 0)))
            .apply { window?.let(::putAll) }
        val counted = Document("\$cond", listOf(Document("\$eq", listOf("\$${field}_n", 0)), null, "\$$field"))
        return listOf(
            windowFields(partition, sortBy, Document(field, sum).append("${field}_n", present)),
            Document("\$set", Document(field, counted)),
        )
    }

    private fun average(metric: String, frame: List<Any>): Document =
        Document("\$avg", metric).append("window", Document("documents", frame))

    /**
     * `$denseRank` orders by one field: a key that puts NULL past every value in the rank's direction, so a NULL
     * takes no rank from a value, and is then set back to NULL.
     */
    private fun ranked(field: String, window: DerivedExpression.Rank, metric: String): List<Bson> {
        val ascending = window.direction == Sort.Direction.ASC
        val last = if (ascending) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
        val key = "${field}_k"
        return listOf(
            Document("\$set", Document(key, Document("\$ifNull", listOf(metric, last)))),
            windowFields(
                partition(window.within),
                Document(key, if (ascending) 1 else -1),
                Document(field, Document("\$denseRank", Document())),
            ),
            Document(
                "\$set",
                Document(field, Document("\$cond", listOf(Document("\$eq", listOf(metric, null)), null, "\$$field"))),
            ),
        )
    }

    /** The order a running window walks: its own, then the groups it does not partition by, ascending. */
    private fun runningOrder(window: DerivedExpression.Running): Document {
        val orderBy = window.order.field.path
        val sortBy = Document(orderBy, if (window.order.direction == Sort.Direction.ASC) 1 else -1)
        query.groupBy.map { it.alias }
            .filter { it != orderBy && it !in window.within }
            .forEach { sortBy[it] = 1 }
        return sortBy
    }

    private fun partition(within: List<String>): Document? =
        within.takeIf { it.isNotEmpty() }?.let { aliases -> Document(aliases.associateWith { "\$$it" }) }

    private fun windowFields(partition: Document?, sortBy: Document?, output: Document): Bson =
        Document(
            "\$setWindowFields",
            Document().apply {
                partition?.let { put("partitionBy", it) }
                sortBy?.let { put("sortBy", it) }
                put("output", output)
            },
        )

    private fun windowFields(partition: Document?, sortBy: Document?, field: String, output: Document): Bson =
        windowFields(partition, sortBy, Document(field, output))
}

/** The windows of this expression, in tree order. */
internal fun DerivedExpression.windows(): List<DerivedExpression.Window> = when (this) {
    is DerivedExpression.Window -> listOf(this)
    is DerivedExpression.Binary -> left.windows() + right.windows()
    is DerivedExpression.MetricRef, is DerivedExpression.Constant -> emptyList()
}
