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

import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Projections
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.DerivedExpression
import org.bson.Document
import org.bson.conversions.Bson

/**
 * Compiles the projection right after `$group`: each group's key out of `_id`, and each metric through `$ifNull`
 * with the empty value of its kind.
 */
@Suppress("LongMethod")
internal fun project(query: AggregationQuery): Bson {
    val projections = buildList {
        add(Projections.excludeId())
        query.groupBy.forEach { group ->
            add(Projections.computed(group.alias, "\$_id.${group.alias}"))
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
 * Compiles [derived] into its own `$project` stage: computed fields of one `$project`
 * document cannot reference each other, so declaration order becomes evaluation order
 * by staging every derived metric after the metrics it references. The stage carries
 * the group aliases, every other declared metric alias and the [windows]' fields forward —
 * inclusion-mode `$project` drops unlisted fields, and later stages never restore them.
 */
internal fun derivedProject(
    query: AggregationQuery,
    derived: AggregationMetric.Derived,
    windows: MongoWindows = MongoWindows(query),
): Bson {
    val projections = buildList {
        add(Projections.excludeId())
        query.groupBy.forEach { add(Projections.include(it.alias)) }
        query.metrics.forEach { metric ->
            add(
                if (metric === derived) {
                    Projections.computed(metric.alias, metric.expression.toDerivedDocument(windows))
                } else {
                    Projections.include(metric.alias)
                },
            )
        }
        windows.fields.forEach { add(Projections.include(it)) }
    }
    return Aggregates.project(Projections.fields(projections))
}

/**
 * Null propagation, divide-by-zero, and finiteness are the record-level [AggregationExpression] guards,
 * shared through [nullSafeBinary]: referenced metrics have already been projected to their guarded final values by
 * [project] or an earlier [derivedProject] stage.
 */
private fun DerivedExpression.toDerivedDocument(windows: MongoWindows): Any = when (this) {
    is DerivedExpression.MetricRef -> "\$$metric"

    /**
     * A bare number is an include flag in `$project`, so constants must be pinned with `$literal`
     * to evaluate as values in computed fields and `$let` variables.
     */
    is DerivedExpression.Constant -> Document("\$literal", value)
    is DerivedExpression.Binary ->
        nullSafeBinary(operator, left.toDerivedDocument(windows), right.toDerivedDocument(windows))

    // A window's number, in double space like the core's (a sum of decimals is a decimal here).
    is DerivedExpression.Window -> Document("\$toDouble", "\$${windows.field(this)}")
}
