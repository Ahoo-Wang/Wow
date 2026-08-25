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

package me.ahoo.wow.query

import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.OrFilter
import java.time.Clock
import java.time.ZoneId

class FilterNormalizer(
    private val clock: Clock = Clock.systemDefaultZone(),
    defaultZoneId: ZoneId = ZoneId.systemDefault(),
    private val defaultDeletionState: DeletionState? = DeletionState.ACTIVE,
) {
    private val relativeTimeFilterNormalizer = RelativeTimeFilterNormalizer(defaultZoneId)

    fun normalize(expression: FilterExpression): FilterExpression {
        val now = clock.instant()
        val structural = normalizeStructural(expression)
        val scoped = if (defaultDeletionState == null || structural.hasExplicitDeletionScope()) {
            structural
        } else {
            AndFilter(listOf(DeletionFilter(defaultDeletionState), structural))
        }
        return simplify(relativeTimeFilterNormalizer.normalize(scoped, now))
    }

    private fun normalizeStructural(expression: FilterExpression): FilterExpression = when (expression) {
        is EqualFilter -> if (expression.value.isNull) IsNullFilter(expression.field) else expression
        is NotEqualFilter -> if (expression.value.isNull) IsNotNullFilter(expression.field) else expression
        is AndFilter -> AndFilter(expression.operands.map(::normalizeStructural))
        is OrFilter -> OrFilter(expression.operands.map(::normalizeStructural))
        is NorFilter -> NorFilter(expression.operands.map(::normalizeStructural))
        is ElementMatchFilter -> ElementMatchFilter(expression.field, normalizeStructural(expression.predicate))
        else -> expression
    }

    private fun FilterExpression.hasExplicitDeletionScope(): Boolean = when (this) {
        is DeletionFilter -> true
        is AndFilter -> operands.any { it.hasExplicitDeletionScope() }
        else -> false
    }

    private fun simplify(expression: FilterExpression): FilterExpression = when (expression) {
        is AndFilter -> simplifyAnd(expression.operands.map(::simplify))
        is OrFilter -> simplifyOr(expression.operands.map(::simplify))
        is NorFilter -> simplifyNor(expression.operands.map(::simplify))
        is ElementMatchFilter -> ElementMatchFilter(expression.field, simplify(expression.predicate))
        else -> expression
    }

    private fun simplifyAnd(operands: List<FilterExpression>): FilterExpression {
        if (operands.any { it === MatchNoneFilter }) return MatchNoneFilter
        val flattened = operands.flatMap { if (it is AndFilter) it.operands else listOf(it) }
            .filterNot { it === MatchAllFilter }
        return when (flattened.size) {
            0 -> MatchAllFilter
            1 -> flattened.first()
            else -> AndFilter(flattened)
        }
    }

    private fun simplifyOr(operands: List<FilterExpression>): FilterExpression {
        if (operands.any { it === MatchAllFilter }) return MatchAllFilter
        val flattened = operands.flatMap { if (it is OrFilter) it.operands else listOf(it) }
            .filterNot { it === MatchNoneFilter }
        return when (flattened.size) {
            0 -> MatchNoneFilter
            1 -> flattened.first()
            else -> OrFilter(flattened)
        }
    }

    private fun simplifyNor(operands: List<FilterExpression>): FilterExpression {
        if (operands.any { it === MatchAllFilter }) return MatchNoneFilter
        val filtered = operands.filterNot { it === MatchNoneFilter }
        return if (filtered.isEmpty()) MatchAllFilter else NorFilter(filtered)
    }
}
