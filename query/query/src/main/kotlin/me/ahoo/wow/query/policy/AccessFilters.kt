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

package me.ahoo.wow.query.policy

import me.ahoo.wow.api.access.AccessAttribute
import me.ahoo.wow.api.access.AccessOperator
import me.ahoo.wow.api.access.AccessPredicate
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.JsonNode

/**
 * An access predicate ([AccessPredicate]) as a filter of the query algebra, which every backend pushes down: the
 * predicate read in negation normal form, each comparison on the field its attribute is stored under in the model
 * queried, as [fieldOf] says.
 *
 * - A comparison holds of a present value, and so does its negation, guarded by `IS_NOT_NULL` of its field: an
 *   absent value, or a record holding none, grants neither way, on every backend alike (SQL's three-valued `NOT` and a
 *   document store's missing fields agree only so). The check of absence, `IS_NULL`, is the one comparison of no
 *   value; its negation is `IS_NOT_NULL`.
 * - A comparison with a claim the caller does not hold ([AccessPredicate.Unknown]), or of an attribute the model does
 *   not store ([fieldOf] null, a dataset with no column for it), matches nothing, negated or not.
 * - One value: `EQUAL` is [EqualFilter], `IN` [InFilter] and `STARTS_WITH` [StartsWithFilter]. Several: `CONTAINS`
 *   is [ContainsAllFilter] of the one value, `CONTAINS_ANY` an [OrFilter] of those, and `IS_EMPTY` an [IsEmptyFilter]
 *   of a present field. An enum's value is compared as the name Jackson writes for it, and an integer as a long, as
 *   the records store them.
 *
 * It agrees with [AccessPredicate.allows], which the kernel evaluates: the query TCK's `AccessFilterSpec` holds every
 * backend, the in-memory one among them, to it.
 */
@InternalWowApi
object AccessFilters {
    /** [predicate] as a filter, each attribute compared on [fieldOf] of it, or matching nothing where that is null. */
    fun compile(predicate: AccessPredicate, fieldOf: (AccessAttribute<*, *>) -> QueryField<*>?): FilterExpression =
        compile(predicate, negated = false) { comparison, negated ->
            fieldOf(comparison.attribute)?.let { comparison(comparison, it, negated) }
        }

    /**
     * [predicate] as a filter whose comparisons [comparisonOf] builds, each matching nothing where it gives none: for a
     * model that holds an attribute as a filter of its own rather than as a field, such as the owner and the space of
     * a snapshot or a dataset row ([me.ahoo.wow.api.query.OwnerIdFilter], [me.ahoo.wow.api.query.SpaceIdFilter])
     * under the default rule ([me.ahoo.wow.api.access.ScopeAccess]). [comparisonOf] builds a comparison as it is: a
     * negated one matches nothing.
     */
    fun compileWith(
        predicate: AccessPredicate,
        comparisonOf: (AccessPredicate.Comparison) -> FilterExpression?,
    ): FilterExpression = compile(predicate, negated = false) { comparison, negated ->
        if (negated) null else comparisonOf(comparison)
    }

    private fun compile(
        predicate: AccessPredicate,
        negated: Boolean,
        comparisonOf: (AccessPredicate.Comparison, Boolean) -> FilterExpression?,
    ): FilterExpression = when (predicate) {
        is AccessPredicate.Comparison -> comparisonOf(predicate, negated) ?: MatchNoneFilter
        is AccessPredicate.Not -> compile(predicate.predicate, !negated, comparisonOf)
        is AccessPredicate.AllOf -> junction(predicate.predicates, negated, all = true, comparisonOf)
        is AccessPredicate.AnyOf -> junction(predicate.predicates, negated, all = false, comparisonOf)
        AccessPredicate.Allowed -> if (negated) MatchNoneFilter else MatchAllFilter
        AccessPredicate.Denied -> if (negated) MatchAllFilter else MatchNoneFilter
        AccessPredicate.Unknown -> MatchNoneFilter
    }

    /** All or one of [predicates]; negated, by De Morgan, one or all of their negations. */
    private fun junction(
        predicates: List<AccessPredicate>,
        negated: Boolean,
        all: Boolean,
        comparisonOf: (AccessPredicate.Comparison, Boolean) -> FilterExpression?,
    ): FilterExpression {
        val operands = predicates.map { compile(it, negated, comparisonOf) }
        return if (all != negated) and(operands) else or(operands)
    }

    private fun comparison(comparison: AccessPredicate.Comparison, field: QueryField<*>, negated: Boolean): FilterExpression {
        val values = comparison.values.map(::stored)
        val holds = when (comparison.operator) {
            // The one comparison of absence: it needs no presence guard.
            AccessOperator.IS_NULL -> return if (negated) IsNotNullFilter(field) else IsNullFilter(field)
            AccessOperator.EQUAL -> EqualFilter(field, values.single())
            AccessOperator.IN -> InFilter(field, values)
            AccessOperator.STARTS_WITH -> StartsWithFilter(field, values.single() as String)
            AccessOperator.CONTAINS -> ContainsAllFilter(field, values)
            AccessOperator.CONTAINS_ANY -> or(values.map { ContainsAllFilter(field, listOf(it)) })
            AccessOperator.IS_EMPTY -> IsEmptyFilter(field)
        }
        val present = IsNotNullFilter(field)
        return when {
            negated -> AndFilter(listOf(present, NorFilter(listOf(holds))))
            comparison.operator == AccessOperator.IS_EMPTY -> AndFilter(listOf(present, holds))
            else -> holds
        }
    }

    /** [value] as a record stores it: an enum by the name Jackson writes, an integer as a long, any other as it is. */
    private fun stored(value: Any): Any = when (value) {
        is Enum<*> -> WowJson.mapper.valueToTree<JsonNode>(value).stringValue()
        is Int -> value.toLong()
        else -> value
    }

    private fun and(operands: List<FilterExpression>): FilterExpression {
        if (MatchNoneFilter in operands) return MatchNoneFilter
        val kept = operands.filterNot { it == MatchAllFilter }
        return when (kept.size) {
            0 -> MatchAllFilter
            1 -> kept.single()
            else -> AndFilter(kept)
        }
    }

    private fun or(operands: List<FilterExpression>): FilterExpression {
        if (MatchAllFilter in operands) return MatchAllFilter
        val kept = operands.filterNot { it == MatchNoneFilter }
        return when (kept.size) {
            0 -> MatchNoneFilter
            1 -> kept.single()
            else -> OrFilter(kept)
        }
    }
}
