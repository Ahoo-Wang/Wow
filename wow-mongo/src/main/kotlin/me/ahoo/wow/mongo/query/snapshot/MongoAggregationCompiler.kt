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
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.mongo.query.AbstractMongoFilterConverter
import org.bson.Document
import org.bson.conversions.Bson

internal class MongoAggregationCompiler(
    private val converter: AbstractMongoFilterConverter,
) {
    fun compile(query: AggregationQuery): List<Bson> = buildList {
        add(Aggregates.match(converter.convert(query.filter)))

        var parent: String? = null
        query.elements.forEach { element ->
            parent = if (parent == null) element.path.value else "$parent.${element.path.value}"
            add(Aggregates.unwind("\$$parent"))
            if (element.filter !== MatchAllFilter) {
                add(Aggregates.match(converter.convertWithoutDefaultDeletion(element.filter, parent)))
            }
        }

        if (query.groupBy.isNotEmpty()) {
            val groupFields = query.groupBy.map { it.field.resolve(parent) }
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
            ?.associateTo(Document()) { it.alias to it.expression(parent) }
        val group = Document("_id", id)
        query.metrics.forEach { metric ->
            when (metric) {
                is AggregationMetric.Count -> group[metric.alias] = Document("\$sum", 1)
                is AggregationMetric.Numeric -> {
                    val field = metric.expression.field().resolve(parent)
                    group[metric.alias] = Document("\$${metric.function.name.lowercase()}", "\$$field")
                    group[metric.countAlias] = Document(
                        "\$sum",
                        Document("\$cond", listOf(Document("\$isNumber", "\$$field"), 1, 0)),
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

    private fun AggregationGroup.expression(parent: String?): Any = when (this) {
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
                Document("date", Document("\$toDate", "\$${field.resolve(parent)}"))
                    .append("unit", unit.name.lowercase())
                    .append("timezone", if (timeZone == "Z") "UTC" else timeZone),
            ),
        )
    }

    private fun AggregationExpression.field(): LogicalField = when (this) {
        is AggregationExpression.Field -> field
        else -> error("Unsupported aggregation expression: ${this::class.java.name}.")
    }

    private fun LogicalField.resolve(parent: String?): String =
        SnapshotFieldConverter.convert(if (parent == null) value else "$parent.$value")

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
