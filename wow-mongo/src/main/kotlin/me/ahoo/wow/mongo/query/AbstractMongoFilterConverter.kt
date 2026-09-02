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
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BeforeTodayFilter
import me.ahoo.wow.api.query.BetweenFilter
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
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
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
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.RecentDaysFilter
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.SearchMode
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.ThisMonthFilter
import me.ahoo.wow.api.query.ThisWeekFilter
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.api.query.TomorrowFilter
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.query.FilterNormalizer
import me.ahoo.wow.query.converter.FieldConverter
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import org.bson.conversions.Bson

abstract class AbstractMongoFilterConverter(
    defaultDeletionState: DeletionState? = DeletionState.ACTIVE,
) {
    companion object {
        private val ESCAPE_CHARS = setOf('\\', '^', '$', '.', '|', '?', '*', '+', '(', ')', '[', ']', '{', '}')
    }

    protected abstract val fieldConverter: FieldConverter

    private val filterNormalizer = FilterNormalizer(defaultDeletionState = defaultDeletionState)
    private val filterNormalizerWithoutDefaultDeletion = FilterNormalizer(defaultDeletionState = null)

    fun convert(filter: FilterExpression, parent: String? = null): Bson =
        compile(filterNormalizer.normalize(filter), parent, mapField = true)

    internal fun convertWithoutDefaultDeletion(filter: FilterExpression, parent: String? = null): Bson =
        compile(filterNormalizerWithoutDefaultDeletion.normalize(filter), parent, mapField = true)

    internal fun convertField(field: String): String = fieldConverter.convert(field)

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun compile(filter: FilterExpression, parent: String?, mapField: Boolean): Bson = when (filter) {
        MatchAllFilter -> Filters.empty()
        MatchNoneFilter -> org.bson.Document("\$expr", false)
        is IdFilter -> Filters.eq(Documents.ID_FIELD, filter.value)
        is IdsFilter -> Filters.`in`(Documents.ID_FIELD, filter.values)
        is AggregateIdFilter -> Filters.eq(fieldConverter.convert(MessageRecords.AGGREGATE_ID), filter.value)
        is AggregateIdsFilter -> Filters.`in`(
            fieldConverter.convert(MessageRecords.AGGREGATE_ID),
            filter.values,
        )
        is TenantIdFilter -> Filters.eq(MessageRecords.TENANT_ID, filter.value)
        is OwnerIdFilter -> Filters.eq(MessageRecords.OWNER_ID, filter.value)
        is SpaceIdFilter -> Filters.eq(MessageRecords.SPACE_ID, filter.value)
        is AndFilter -> Filters.and(filter.operands.map { compile(it, parent, mapField) })
        is OrFilter -> Filters.or(filter.operands.map { compile(it, parent, mapField) })
        is NorFilter -> Filters.nor(filter.operands.map { compile(it, parent, mapField) })
        is EqualFilter -> Filters.eq(filter.field.convert(parent, mapField), filter.value.nativeValue())
        is NotEqualFilter -> Filters.ne(filter.field.convert(parent, mapField), filter.value.nativeValue())
        is GreaterThanFilter -> Filters.gt(filter.field.convert(parent, mapField), filter.value.requiredNativeValue())
        is GreaterThanOrEqualFilter -> Filters.gte(
            filter.field.convert(parent, mapField),
            filter.value.requiredNativeValue()
        )
        is LessThanFilter -> Filters.lt(filter.field.convert(parent, mapField), filter.value.requiredNativeValue())
        is LessThanOrEqualFilter -> Filters.lte(
            filter.field.convert(parent, mapField),
            filter.value.requiredNativeValue()
        )
        is ContainsFilter -> regex(
            filter.field.convert(parent, mapField),
            filter.value.escapeRegex(),
            filter.stringComparison.ignoreCase
        )
        is StartsWithFilter -> regex(
            filter.field.convert(parent, mapField),
            "^${filter.value.escapeRegex()}",
            filter.stringComparison.ignoreCase
        )
        is EndsWithFilter -> regex(
            filter.field.convert(parent, mapField),
            "${filter.value.escapeRegex()}$",
            filter.stringComparison.ignoreCase
        )
        is InFilter -> Filters.`in`(filter.field.convert(parent, mapField), filter.values.map { it.nativeValue() })
        is NotInFilter -> Filters.nin(filter.field.convert(parent, mapField), filter.values.map { it.nativeValue() })
        is BetweenFilter -> Filters.and(
            Filters.gte(filter.field.convert(parent, mapField), filter.lowerBound.requiredNativeValue()),
            Filters.lte(filter.field.convert(parent, mapField), filter.upperBound.requiredNativeValue()),
        )
        is ContainsAllFilter -> Filters.all(
            filter.field.convert(parent, mapField),
            filter.values.map { it.nativeValue() }
        )
        is IsEmptyFilter -> Filters.size(filter.field.convert(parent, mapField), 0)
        is IsNullFilter -> Filters.eq(filter.field.convert(parent, mapField), null)
        is IsNotNullFilter -> Filters.ne(filter.field.convert(parent, mapField), null)
        is ExistsFilter -> Filters.exists(filter.field.convert(parent, mapField))
        is NotExistsFilter -> Filters.exists(filter.field.convert(parent, mapField), false)
        is DeletionFilter -> when (filter.deletionState) {
            DeletionState.ACTIVE -> Filters.eq(StateAggregateRecords.DELETED, false)
            DeletionState.DELETED -> Filters.eq(StateAggregateRecords.DELETED, true)
            DeletionState.ALL -> Filters.empty()
        }
        is ElementMatchFilter -> Filters.elemMatch(
            filter.field.convert(parent, mapField),
            compile(filter.predicate, parent = null, mapField = false),
        )
        is SearchFilter -> Filters.text(
            if (filter.mode == SearchMode.PHRASE) {
                require('"' !in filter.query) { "MongoDB PHRASE search query cannot contain double quotes." }
                "\"${filter.query}\""
            } else {
                filter.query
            },
        )
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

    private fun me.ahoo.wow.api.query.QueryField.convert(parent: String?, mapField: Boolean): String =
        path(parent).let { if (mapField) fieldConverter.convert(it) else it }

    private fun me.ahoo.wow.api.query.QueryField.path(parent: String?): String =
        if (parent == null || path == parent || path.startsWith("$parent.")) path else "$parent.$path"

    private val StringComparison.ignoreCase: Boolean
        get() = this == StringComparison.CASE_INSENSITIVE

    private fun tools.jackson.databind.JsonNode.nativeValue(): Any? = when {
        isNull -> null
        isString -> asString()
        isNumber -> numberValue()
        isBoolean -> booleanValue()
        isPojo -> (this as tools.jackson.databind.node.POJONode).pojo
        isArray -> asSequence().map { it.nativeValue() }.toList()
        else -> error("Filter value must be a scalar, scalar array, or runtime POJO.")
    }

    private fun tools.jackson.databind.JsonNode.requiredNativeValue(): Any =
        requireNotNull(nativeValue()) { "Range filter value cannot be null." }

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
        ignoreCase: Boolean
    ): Bson =
        if (ignoreCase) {
            Filters.regex(field, value, "i")
        } else {
            Filters.regex(field, value)
        }
}
