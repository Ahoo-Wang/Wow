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
import me.ahoo.wow.api.query.QueryField
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
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.query.FilterNormalizer
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import org.bson.conversions.Bson

abstract class AbstractMongoFilterCompiler(
    defaultDeletionState: DeletionState? = DeletionState.ACTIVE,
) {
    companion object {
        private val ESCAPE_CHARS = setOf('\\', '^', '$', '.', '|', '?', '*', '+', '(', ')', '[', ']', '{', '}')
    }

    private val filterNormalizer = FilterNormalizer(defaultDeletionState = defaultDeletionState)
    private val filterNormalizerWithoutDefaultDeletion = FilterNormalizer(defaultDeletionState = null)

    fun compile(filter: FilterExpression, schema: QueryModelSchema): Bson =
        compile(filterNormalizer.normalize(filter), schema, FilterScope())

    internal fun compileWithoutDefaultDeletion(
        filter: FilterExpression,
        schema: QueryModelSchema,
        logicalParent: QueryField? = null,
        physicalParent: QueryField? = null,
    ): Bson = compile(
        filterNormalizerWithoutDefaultDeletion.normalize(filter),
        schema,
        FilterScope(logicalParent, physicalParent),
    )

    private data class FilterScope(
        val logicalParent: QueryField? = null,
        val physicalParent: QueryField? = null,
        val relativeToParent: Boolean = false,
    )

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun compile(filter: FilterExpression, schema: QueryModelSchema, scope: FilterScope): Bson = when (filter) {
        MatchAllFilter -> Filters.empty()
        MatchNoneFilter -> org.bson.Document("\$expr", false)
        is IdFilter -> Filters.eq(schema.identityField().path, filter.value)
        is IdsFilter -> Filters.`in`(schema.identityField().path, filter.values)
        is AggregateIdFilter -> Filters.eq(schema.field(MessageRecords.AGGREGATE_ID).path, filter.value)
        is AggregateIdsFilter -> Filters.`in`(
            schema.field(MessageRecords.AGGREGATE_ID).path,
            filter.values,
        )
        is TenantIdFilter -> Filters.eq(schema.field(MessageRecords.TENANT_ID).path, filter.value)
        is OwnerIdFilter -> Filters.eq(schema.field(MessageRecords.OWNER_ID).path, filter.value)
        is SpaceIdFilter -> Filters.eq(schema.field(MessageRecords.SPACE_ID).path, filter.value)
        is AndFilter -> Filters.and(filter.operands.map { compile(it, schema, scope) })
        is OrFilter -> Filters.or(filter.operands.map { compile(it, schema, scope) })
        is NorFilter -> Filters.nor(filter.operands.map { compile(it, schema, scope) })
        is EqualFilter -> Filters.eq(
            filter.field.resolve(schema, QueryCapability.EXACT_MATCH, scope),
            filter.value.nativeValue()
        )
        is NotEqualFilter -> Filters.ne(
            filter.field.resolve(schema, QueryCapability.EXACT_MATCH, scope),
            filter.value.nativeValue()
        )
        is GreaterThanFilter -> Filters.gt(
            filter.field.resolve(schema, QueryCapability.RANGE, scope),
            filter.value.requiredNativeValue()
        )
        is GreaterThanOrEqualFilter -> Filters.gte(
            filter.field.resolve(schema, QueryCapability.RANGE, scope),
            filter.value.requiredNativeValue()
        )
        is LessThanFilter -> Filters.lt(
            filter.field.resolve(schema, QueryCapability.RANGE, scope),
            filter.value.requiredNativeValue()
        )
        is LessThanOrEqualFilter -> Filters.lte(
            filter.field.resolve(schema, QueryCapability.RANGE, scope),
            filter.value.requiredNativeValue()
        )
        is ContainsFilter -> regex(
            filter.field.resolve(schema, QueryCapability.LITERAL_MATCH, scope),
            filter.value.escapeRegex(),
            filter.stringComparison.ignoreCase
        )
        is StartsWithFilter -> regex(
            filter.field.resolve(schema, QueryCapability.LITERAL_MATCH, scope),
            "^${filter.value.escapeRegex()}",
            filter.stringComparison.ignoreCase
        )
        is EndsWithFilter -> regex(
            filter.field.resolve(schema, QueryCapability.LITERAL_MATCH, scope),
            "${filter.value.escapeRegex()}$",
            filter.stringComparison.ignoreCase
        )
        is InFilter -> Filters.`in`(
            filter.field.resolve(schema, QueryCapability.EXACT_MATCH, scope),
            filter.values.map { it.nativeValue() },
        )
        is NotInFilter -> Filters.nin(
            filter.field.resolve(schema, QueryCapability.EXACT_MATCH, scope),
            filter.values.map { it.nativeValue() },
        )
        is BetweenFilter -> Filters.and(
            Filters.gte(
                filter.field.resolve(schema, QueryCapability.RANGE, scope),
                filter.lowerBound.requiredNativeValue()
            ),
            Filters.lte(
                filter.field.resolve(schema, QueryCapability.RANGE, scope),
                filter.upperBound.requiredNativeValue()
            ),
        )
        is ContainsAllFilter -> Filters.all(
            filter.field.resolve(schema, QueryCapability.EXACT_MATCH, scope),
            filter.values.map { it.nativeValue() }
        )
        is IsEmptyFilter -> Filters.size(filter.field.resolve(schema, QueryCapability.PRESENCE, scope), 0)
        is IsNullFilter -> Filters.eq(filter.field.resolve(schema, QueryCapability.PRESENCE, scope), null)
        is IsNotNullFilter -> Filters.ne(filter.field.resolve(schema, QueryCapability.PRESENCE, scope), null)
        is ExistsFilter -> Filters.exists(filter.field.resolve(schema, QueryCapability.PRESENCE, scope))
        is NotExistsFilter -> Filters.exists(filter.field.resolve(schema, QueryCapability.PRESENCE, scope), false)
        is DeletionFilter -> when (filter.deletionState) {
            DeletionState.ACTIVE -> Filters.eq(StateAggregateRecords.DELETED, false)
            DeletionState.DELETED -> Filters.eq(StateAggregateRecords.DELETED, true)
            DeletionState.ALL -> Filters.empty()
        }
        is ElementMatchFilter -> filter.field.resolvePhysical(
            schema,
            QueryCapability.ELEMENT_SCOPE,
            scope,
        ).let { physicalField ->
            Filters.elemMatch(
                physicalField.path,
                compile(
                    filter.predicate,
                    schema,
                    FilterScope(
                        filter.field.absoluteTo(scope.logicalParent),
                        physicalField.absoluteTo(scope.physicalParent),
                        relativeToParent = true,
                    ),
                ),
            )
        }
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

    private fun QueryModelSchema.identityField(): QueryField = field(
        if (model == QueryModel.EVENT_STREAM) MessageRecords.ID else MessageRecords.AGGREGATE_ID,
    )

    private fun QueryModelSchema.field(field: String): QueryField =
        resolvePhysicalField(QueryField(field), QueryCapability.EXACT_MATCH)

    private fun QueryField.resolve(
        schema: QueryModelSchema,
        capability: QueryCapability,
        scope: FilterScope,
    ): String = resolvePhysical(schema, capability, scope).path

    private fun QueryField.resolvePhysical(
        schema: QueryModelSchema,
        capability: QueryCapability,
        scope: FilterScope,
    ): QueryField {
        val logicalField = absoluteTo(scope.logicalParent)
        if (!scope.relativeToParent) {
            return schema.field(logicalField)?.binding(capability)?.physicalField
                ?: schema.resolvePhysicalField(logicalField, capability)
        }
        if (scope.logicalParent != null && schema.field(logicalField)?.binding(capability) == null) {
            return relativeTo(scope.logicalParent) ?: this
        }
        return schema.resolvePhysicalField(this, capability, scope.logicalParent, physicalParent = scope.physicalParent)
    }

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
