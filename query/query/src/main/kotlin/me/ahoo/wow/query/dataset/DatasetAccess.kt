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

package me.ahoo.wow.query.dataset

import me.ahoo.wow.api.access.AccessAttribute
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FieldPredicate
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
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.spi.access.AccessValues

/**
 * How an access policy restricts the rows of a dataset bound to an aggregate ([QueryDatasetAccess]): by the current
 * access attributes of the aggregate each row is of, never by what the row recorded when it was written. The
 * restriction is one `ELEMENT_MATCH` on the reserved container [FIELD], `@access`: the aggregate's current access
 * record, none when the storage holds none of it, whose fields are its attributes by name as every record stores them
 * ([AccessValues]: the built-in `owner` and `space`, and each declared one). It reads "the aggregate has a current
 * access record that the predicate holds of":
 *
 * - the predicate is the policy's whole `read` predicate in one node, so a negation or a disjunction keeps its meaning,
 *   and a storage compiles it as one semi-join of the dataset's id column on the aggregates' current access records;
 * - an aggregate whose access the storage does not hold, or one recorded without attributes, matches nothing,
 *   whatever the predicate;
 * - only admission appends it ([me.ahoo.wow.query.policy.AccessQueryPolicy]): a caller cannot name `@access`, so no
 *   filter, sort or projection of a caller's reaches its predicate.
 *
 * A storage compiles it only where it declares [me.ahoo.wow.query.schema.StorageSupport.datasetAccess]. Its predicate
 * is in the vocabulary [me.ahoo.wow.query.policy.AccessFilters] compiles to ([requireComparisons]): `AND`, `OR`,
 * `NOR`, `EQ`, `IN`, case-sensitive `STARTS_WITH`, `CONTAINS_ALL`, `IS_EMPTY`, `IS_NULL` and `IS_NOT_NULL`, each on
 * one attribute and each operand a `String`, a `Long` or a `Boolean`.
 */
@InternalWowApi
object DatasetAccess {
    /** The container of the current access record of the aggregate a row is of. */
    @JvmField
    val FIELD: QueryField<*> = QueryField("@access")

    /** The field [attribute] is compared on inside [FIELD]: its name in the access record. */
    @JvmStatic
    fun fieldOf(attribute: AccessAttribute<*, *>): QueryField<*> = when (attribute) {
        AccessAttribute.OWNER -> QueryField(AccessValues.OWNER)
        AccessAttribute.SPACE -> QueryField(AccessValues.SPACE)
        else -> QueryField(attribute.name)
    }

    /**
     * The restriction of a policy's [predicate], compiled on the access record's fields ([fieldOf]): none for a
     * predicate that grants every aggregate, and nothing for one that grants none, neither reading an access record;
     * otherwise the one `ELEMENT_MATCH` of the predicate on [FIELD].
     */
    @JvmStatic
    fun restriction(predicate: FilterExpression): FilterExpression = when (predicate) {
        MatchAllFilter, MatchNoneFilter -> predicate
        else -> ElementMatchFilter(FIELD, requireComparisons(predicate))
    }

    /** The predicate of [filter] when it is a dataset's access restriction; null for any other filter. */
    @JvmStatic
    fun predicateOf(filter: FilterExpression): FilterExpression? =
        (filter as? ElementMatchFilter)?.takeIf { it.field == FIELD }?.predicate

    /** [predicate], checked to be in the vocabulary of an access restriction; an [IllegalArgumentException] if not. */
    @JvmStatic
    fun requireComparisons(predicate: FilterExpression): FilterExpression = predicate.also(::check)

    @Suppress("CyclomaticComplexMethod") // One branch per node of the restriction's vocabulary.
    private fun check(filter: FilterExpression) {
        when (filter) {
            MatchAllFilter, MatchNoneFilter -> Unit
            is AndFilter -> filter.operands.forEach(::check)
            is OrFilter -> filter.operands.forEach(::check)
            is NorFilter -> filter.operands.forEach(::check)
            is EqualFilter -> attribute(filter).also { operand(filter, filter.value) }
            is InFilter -> attribute(filter).also { filter.values.forEach { operand(filter, it) } }
            is ContainsAllFilter -> attribute(filter).also { filter.values.forEach { operand(filter, it) } }
            is StartsWithFilter -> attribute(filter).also {
                require(filter.stringComparison == StringComparison.CASE_SENSITIVE) {
                    "An access restriction compares a prefix case-sensitively: $filter."
                }
            }
            is IsEmptyFilter, is IsNullFilter, is IsNotNullFilter -> attribute(filter as FieldPredicate)
            else -> throw IllegalArgumentException("An access restriction compares no [${filter.operator}]: $filter.")
        }
    }

    private fun attribute(filter: FieldPredicate) {
        require(filter.field.path.isNotEmpty() && '.' !in filter.field.path) {
            "An access restriction compares one attribute by its name, not [${filter.field.path}]."
        }
    }

    private fun operand(filter: FilterExpression, value: Any?) {
        require(value is String || value is Long || value is Boolean) {
            "An access restriction compares a String, a Long or a Boolean, not [$value] in $filter."
        }
    }
}
