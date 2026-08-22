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
import com.mongodb.client.model.UnwindOptions
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.converter.ConditionConverter
import org.bson.Document
import org.bson.conversions.Bson

internal object MongoAggregationCompiler {
    fun compile(query: AggregationQuery, conditionConverter: ConditionConverter<Bson>): List<Bson> = buildList {
        add(Aggregates.match(conditionConverter.convert(query.condition)))
        query.elements.forEach { element ->
            val field = SnapshotFieldConverter.convert(element.path)
            add(Aggregates.unwind("\$$field", UnwindOptions().preserveNullAndEmptyArrays(false)))
            if (element.condition.operator != Operator.ALL) {
                val elementCondition = Condition.and(
                    Condition.deleted(DeletionState.ALL),
                    element.condition,
                )
                add(Aggregates.match(conditionConverter.convert(elementCondition)))
            }
        }
        if (query.groupBy.isNotEmpty()) {
            add(
                Aggregates.match(
                    Filters.and(
                        query.groupBy.flatMap { group ->
                            val field = SnapshotFieldConverter.convert(group.field)
                            listOf(Filters.exists(field, true), Filters.ne(field, null))
                        }
                    )
                )
            )
        }
        add(Document("\$group", query.toMongoGroup()))
        add(Document("\$project", query.toMongoProjection()))
        if (query.groupBy.isNotEmpty()) {
            add(Document("\$sort", query.toMongoSort()))
            add(Document("\$limit", query.limit))
        }
    }

    private fun AggregationQuery.toMongoGroup(): Document {
        val groupId = groupBy.takeIf { it.isNotEmpty() }?.let {
            Document().apply {
                groupBy.forEach { group -> put(group.alias, group.toMongoExpression()) }
            }
        }
        return Document("_id", groupId).apply {
            metrics.forEach { metric -> put(metric.alias, metric.toMongoAccumulator()) }
        }
    }

    private fun AggregationQuery.toMongoProjection(): Document = Document("_id", 0).apply {
        groupBy.forEach { group ->
            val value = "\$_id.${group.alias}"
            put(group.alias, if (group is AggregationGroup.DateHistogram) Document("\$toLong", value) else value)
        }
        metrics.forEach { metric ->
            val value = "\$${metric.alias}"
            put(
                metric.alias,
                if (metric is AggregationMetric.Count) {
                    Document("\$toLong", value)
                } else {
                    Document("\$convert", Document("input", value).append("to", "double"))
                }
            )
        }
    }

    private fun AggregationQuery.toMongoSort(): Document = Document().apply {
        effectiveSort().forEach { sort -> put(sort.field, sort.direction.toMongoDirection()) }
    }

    private fun AggregationGroup.toMongoExpression(): Any {
        val field = "\$${SnapshotFieldConverter.convert(field)}"
        return when (this) {
            is AggregationGroup.Terms -> field
            is AggregationGroup.Histogram -> Document(
                "\$multiply",
                listOf(Document("\$floor", Document("\$divide", listOf(field.toMongoDouble(), interval))), interval),
            )

            is AggregationGroup.DateHistogram -> Document(
                "\$dateTrunc",
                Document("date", field.toMongoDate())
                    .append("unit", unit.toMongoDateUnit())
                    .append("timezone", timeZone)
                    .also { options ->
                        if (unit == AggregationDateUnit.WEEK) {
                            options.append("startOfWeek", "monday")
                        }
                    },
            )
        }
    }

    private fun AggregationMetric.toMongoAccumulator(): Document = when (this) {
        is AggregationMetric.Count -> Document("\$sum", 1)
        is AggregationMetric.Numeric -> {
            val field = when (val value = expression) {
                is AggregationExpression.Field -> "\$${SnapshotFieldConverter.convert(value.field)}"
            }.toMongoDouble()
            Document(
                when (function) {
                    AggregationFunction.SUM -> "\$sum"
                    AggregationFunction.AVG -> "\$avg"
                    AggregationFunction.MIN -> "\$min"
                    AggregationFunction.MAX -> "\$max"
                },
                field,
            )
        }
    }

    private fun AggregationQuery.effectiveSort(): List<Sort> = buildList {
        addAll(sort)
        val sortedAliases = sort.mapTo(hashSetOf(), Sort::field)
        groupBy.map(AggregationGroup::alias)
            .filterNot(sortedAliases::contains)
            .forEach { add(Sort(it, Sort.Direction.ASC)) }
    }

    private fun String.toMongoDouble(): Document =
        Document("\$convert", Document("input", this).append("to", "double"))

    private fun String.toMongoDate(): Document =
        Document("\$convert", Document("input", this).append("to", "date"))

    private fun AggregationDateUnit.toMongoDateUnit(): String = name.lowercase()

    private fun Sort.Direction.toMongoDirection(): Int = if (this == Sort.Direction.ASC) 1 else -1
}
