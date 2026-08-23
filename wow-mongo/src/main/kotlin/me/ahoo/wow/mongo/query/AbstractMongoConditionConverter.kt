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

package me.ahoo.wow.mongo.query

import com.mongodb.client.model.Filters
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BeforeTodayFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.EarlierDaysFilter
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LastMonthFilter
import me.ahoo.wow.api.query.LastWeekFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NextWeekFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.RecentDaysFilter
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.api.query.ThisMonthFilter
import me.ahoo.wow.api.query.ThisWeekFilter
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.api.query.TomorrowFilter
import me.ahoo.wow.api.query.legacyConditionOrNull
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.query.FilterNormalizer
import me.ahoo.wow.query.converter.AbstractConditionConverter
import me.ahoo.wow.query.converter.FieldConverter
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import org.bson.conversions.Bson

abstract class AbstractMongoConditionConverter(
    defaultDeletionState: DeletionState? = DeletionState.ACTIVE,
) : AbstractConditionConverter<Bson>() {
    companion object {
        private val ESCAPE_CHARS = setOf('\\', '^', '$', '.', '|', '?', '*', '+', '(', ')', '[', ']', '{', '}')
    }

    protected abstract val fieldConverter: FieldConverter

    private val filterNormalizer = FilterNormalizer(defaultDeletionState = defaultDeletionState)

    fun convert(filter: FilterExpression): Bson =
        filter.legacyConditionOrNull()?.let(::convert) ?: internalConvert(filterNormalizer.normalize(filter))

    private fun internalConvert(filter: FilterExpression, mapField: Boolean = true): Bson {
        filter.legacyConditionOrNull()?.let {
            return super.internalConvert(if (mapField) convertCondition(it) else it)
        }
        return compile(filter, mapField)
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun compile(filter: FilterExpression, mapField: Boolean): Bson = when (filter) {
        MatchAllFilter -> Filters.empty()
        MatchNoneFilter -> org.bson.Document("\$expr", false)
        is AndFilter -> Filters.and(filter.operands.map { internalConvert(it, mapField) })
        is OrFilter -> Filters.or(filter.operands.map { internalConvert(it, mapField) })
        is NorFilter -> Filters.nor(filter.operands.map { internalConvert(it, mapField) })
        is EqualFilter -> Filters.eq(filter.field.convert(mapField), filter.value.nativeValue())
        is NotEqualFilter -> Filters.ne(filter.field.convert(mapField), filter.value.nativeValue())
        is GreaterThanFilter -> Filters.gt(filter.field.convert(mapField), filter.value.requiredNativeValue())
        is GreaterThanOrEqualFilter -> Filters.gte(
            filter.field.convert(mapField),
            filter.value.requiredNativeValue()
        )
        is LessThanFilter -> Filters.lt(filter.field.convert(mapField), filter.value.requiredNativeValue())
        is LessThanOrEqualFilter -> Filters.lte(filter.field.convert(mapField), filter.value.requiredNativeValue())
        is ContainsFilter -> regex(
            filter.field.convert(mapField),
            filter.value.escapeRegex(),
            filter.stringComparison.ignoreCase
        )
        is StartsWithFilter -> regex(
            filter.field.convert(mapField),
            "^${filter.value.escapeRegex()}",
            filter.stringComparison.ignoreCase
        )
        is EndsWithFilter -> regex(
            filter.field.convert(mapField),
            "${filter.value.escapeRegex()}$",
            filter.stringComparison.ignoreCase
        )
        is InFilter -> Filters.`in`(filter.field.convert(mapField), filter.values.map { it.nativeValue() })
        is NotInFilter -> Filters.nin(filter.field.convert(mapField), filter.values.map { it.nativeValue() })
        is BetweenFilter -> Filters.and(
            Filters.gte(filter.field.convert(mapField), filter.lowerBound.requiredNativeValue()),
            Filters.lte(filter.field.convert(mapField), filter.upperBound.requiredNativeValue()),
        )
        is ContainsAllFilter -> Filters.all(filter.field.convert(mapField), filter.values.map { it.nativeValue() })
        is IsEmptyFilter -> Filters.size(filter.field.convert(mapField), 0)
        is IsNullFilter -> Filters.eq(filter.field.convert(mapField), null)
        is IsNotNullFilter -> Filters.ne(filter.field.convert(mapField), null)
        is ExistsFilter -> Filters.exists(filter.field.convert(mapField))
        is NotExistsFilter -> Filters.exists(filter.field.convert(mapField), false)
        is DeletionFilter -> when (filter.deletionState) {
            DeletionState.ACTIVE -> Filters.eq(StateAggregateRecords.DELETED, false)
            DeletionState.DELETED -> Filters.eq(StateAggregateRecords.DELETED, true)
            DeletionState.ALL -> Filters.empty()
        }
        is ElementMatchFilter -> Filters.elemMatch(
            filter.field.convert(mapField),
            internalConvert(filter.predicate, mapField = false),
        )
        is SearchFilter -> Filters.text(filter.query)
        is TodayFilter,
        is BeforeTodayFilter,
        is TomorrowFilter,
        is ThisWeekFilter,
        is NextWeekFilter,
        is LastWeekFilter,
        is ThisMonthFilter,
        is LastMonthFilter,
        is RecentDaysFilter,
        is EarlierDaysFilter,
        -> error("Relative-time filter must be normalized before compilation.")
        else -> error("Unsupported filter expression: ${filter::class.java.name}.")
    }

    private fun me.ahoo.wow.api.query.LogicalField.convert(mapField: Boolean): String =
        if (mapField) fieldConverter.convert(value) else value

    private val StringComparison.ignoreCase: Boolean
        get() = this == StringComparison.CASE_INSENSITIVE

    private fun tools.jackson.databind.JsonNode.nativeValue(): Any? = when {
        isNull -> null
        isString -> asString()
        isNumber -> numberValue()
        isBoolean -> booleanValue()
        isArray -> asSequence().map { it.nativeValue() }.toList()
        else -> error("Filter value must be a JSON scalar or scalar array.")
    }

    private fun tools.jackson.databind.JsonNode.requiredNativeValue(): Any =
        requireNotNull(nativeValue()) { "Range filter value cannot be null." }

    protected open fun convertCondition(condition: Condition): Condition {
        val convertedField = fieldConverter.convert(condition.field)
        val convertedChildren = when (condition.operator) {
            Operator.AND, Operator.OR, Operator.NOR -> condition.children.map(::convertCondition)
            else -> condition.children
        }
        if (convertedField == condition.field && convertedChildren == condition.children) {
            return condition
        }
        return condition.copy(field = convertedField, children = convertedChildren)
    }

    override fun convert(condition: Condition): Bson {
        val convertedCondition = convertCondition(condition)
        return super.convert(convertedCondition)
    }

    override fun and(condition: Condition): Bson {
        require(condition.children.isNotEmpty()) {
            "AND operator children cannot be empty."
        }
        return Filters.and(condition.children.map { internalConvert(it) })
    }

    override fun or(condition: Condition): Bson {
        require(condition.children.isNotEmpty()) {
            "OR operator children cannot be empty."
        }
        return Filters.or(condition.children.map { internalConvert(it) })
    }

    override fun nor(condition: Condition): Bson {
        require(condition.children.isNotEmpty()) {
            "NOR operator children cannot be empty."
        }
        return Filters.nor(condition.children.map { internalConvert(it) })
    }

    override fun id(condition: Condition): Bson = Filters.eq(condition.value)

    override fun ids(condition: Condition): Bson =
        Filters.`in`(
            Documents.ID_FIELD,
            condition.valueAs<Iterable<String>>(),
        )

    override fun tenantId(condition: Condition): Bson = Filters.eq(MessageRecords.TENANT_ID, condition.value)

    override fun ownerId(condition: Condition): Bson = Filters.eq(MessageRecords.OWNER_ID, condition.value)

    override fun spaceId(condition: Condition): Bson = Filters.eq(MessageRecords.SPACE_ID, condition.value)

    override fun all(condition: Condition): Bson = Filters.empty()

    override fun eq(condition: Condition): Bson = Filters.eq(condition.field, condition.value)

    override fun ne(condition: Condition): Bson = Filters.ne(condition.field, condition.value)

    override fun gt(condition: Condition): Bson = Filters.gt(condition.field, condition.value)

    override fun lt(condition: Condition): Bson = Filters.lt(condition.field, condition.value)

    override fun gte(condition: Condition): Bson = Filters.gte(condition.field, condition.value)

    override fun lte(condition: Condition): Bson = Filters.lte(condition.field, condition.value)

    private fun String.escapeRegex(): String {
        val sb = StringBuilder(length + 16)
        for (char in this) {
            if (char in ESCAPE_CHARS) {
                sb.append('\\')
            }
            sb.append(char)
        }
        return sb.toString()
    }

    private fun regex(
        field: String,
        value: String,
        ignoreCase: Boolean?
    ): Bson =
        if (ignoreCase == true) {
            Filters.regex(field, value, "i")
        } else {
            Filters.regex(field, value)
        }

    override fun contains(condition: Condition): Bson =
        regex(condition.field, condition.valueAs<String>().escapeRegex(), condition.ignoreCase())

    override fun match(condition: Condition): Bson = Filters.text(condition.valueAs())

    override fun startsWith(condition: Condition): Bson =
        regex(condition.field, "^${condition.valueAs<String>().escapeRegex()}", condition.ignoreCase())

    override fun endsWith(condition: Condition): Bson =
        regex(condition.field, "${condition.valueAs<String>().escapeRegex()}$", condition.ignoreCase())

    override fun isIn(condition: Condition): Bson = Filters.`in`(condition.field, condition.valueAs<Iterable<*>>())

    override fun notIn(condition: Condition): Bson = Filters.nin(condition.field, condition.valueAs<Iterable<*>>())

    override fun between(condition: Condition): Bson {
        val valueIterable = condition.valueAs<Iterable<Any>>()
        val ite = valueIterable.iterator()
        require(ite.hasNext()) {
            "BETWEEN operator value must be a array with 2 elements."
        }
        val first = ite.next()
        require(ite.hasNext()) {
            "BETWEEN operator value must be a array with 2 elements."
        }
        val second = ite.next()
        return Filters.and(Filters.gte(condition.field, first), Filters.lte(condition.field, second))
    }

    override fun allIn(condition: Condition): Bson = Filters.all(condition.field, condition.valueAs<Iterable<*>>())

    override fun elemMatch(condition: Condition): Bson =
        Filters.elemMatch(
            condition.field,
            condition.children.first().let { internalConvert(it) },
        )

    override fun isNull(condition: Condition): Bson = Filters.eq(condition.field, null)

    override fun notNull(condition: Condition): Bson = Filters.ne(condition.field, null)

    override fun isTrue(condition: Condition): Bson = Filters.eq(condition.field, true)

    override fun isFalse(condition: Condition): Bson = Filters.eq(condition.field, false)

    override fun exists(condition: Condition): Bson = Filters.exists(condition.field, condition.valueAs())

    override fun deleted(condition: Condition): Bson =
        when (condition.deletionState()) {
            DeletionState.ACTIVE -> {
                Filters.eq(StateAggregateRecords.DELETED, false)
            }

            DeletionState.DELETED -> {
                Filters.eq(StateAggregateRecords.DELETED, true)
            }

            DeletionState.ALL -> {
                Filters.empty()
            }
        }
}
