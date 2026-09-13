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

import com.mongodb.client.model.Filters
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.HavingExpression
import org.bson.Document
import org.bson.conversions.Bson

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
internal fun HavingExpression.toHavingDocument(): Bson = when (this) {
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
