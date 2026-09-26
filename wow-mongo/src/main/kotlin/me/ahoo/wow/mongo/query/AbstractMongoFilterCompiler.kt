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
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsEmptyStringFilter
import me.ahoo.wow.api.query.IsNotEmptyStringFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RelativeTimeFilter
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.SearchMode
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.mongo.query.aggregation.toMongoExpression
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.QueryExecutionException
import me.ahoo.wow.query.schema.QueryViolation
import org.bson.Document
import org.bson.conversions.Bson

abstract class AbstractMongoFilterCompiler {
    companion object {
        private val ESCAPE_CHARS = setOf('\\', '^', '$', '.', '|', '?', '*', '+', '(', ')', '[', ']', '{', '}')
    }

    /** Compiles an admitted count filter. */
    fun compile(admitted: AdmittedQuery<FilterExpression>): Bson = compile(admitted.query, admitted)

    /**
     * Compiles [filter], a filter of [admitted], reading each field's resolution: absolute physical paths, so the
     * filter also applies inside unwound element scopes.
     */
    fun compile(filter: FilterExpression, admitted: AdmittedQuery<*>): Bson =
        compile(filter.also(::validateNativeText), admitted, relative = false)

    /** Compiles an element-scope or metric filter of an aggregation, whose paths stay absolute after `$unwind`. */
    internal fun compileScoped(filter: FilterExpression, admitted: AdmittedQuery<*>): Bson =
        compile(filter, admitted, relative = false)

    private fun validateNativeText(filter: FilterExpression) {
        var count = 0
        fun visit(expression: FilterExpression, underNor: Boolean = false) {
            when (expression) {
                is SearchFilter -> {
                    if (underNor || ++count > 1) {
                        throw QueryViolation.StorageUnsupported(
                            "more than one search filter, or a search filter beneath NOR"
                        ).rejection()
                    }
                }
                is AndFilter -> expression.operands.forEach { visit(it, underNor) }
                is OrFilter -> expression.operands.forEach { visit(it, underNor) }
                is NorFilter -> expression.operands.forEach { visit(it, true) }
                else -> Unit
            }
        }
        visit(filter)
    }

    /**
     * [relative]: inside `$elemMatch`, where paths are relative to the matched element; the resolution's physical
     * parent is that element's container.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun compile(filter: FilterExpression, admitted: AdmittedQuery<*>, relative: Boolean): Bson = when (filter) {
        MatchAllFilter -> Filters.empty()
        MatchNoneFilter -> MATCH_NONE_FILTER
        is IdFilter -> Filters.eq(admitted.systemPath(filter), filter.value)
        is IdsFilter -> Filters.`in`(admitted.systemPath(filter), filter.values)
        is AggregateIdFilter -> Filters.eq(admitted.systemPath(filter), filter.value)
        is AggregateIdsFilter -> Filters.`in`(admitted.systemPath(filter), filter.values)
        is TenantIdFilter -> Filters.eq(admitted.systemPath(filter), filter.value)
        is OwnerIdFilter -> Filters.eq(admitted.systemPath(filter), filter.value)
        is SpaceIdFilter -> Filters.eq(admitted.systemPath(filter), filter.value)
        is AndFilter -> Filters.and(filter.operands.map { compile(it, admitted, relative) })
        is OrFilter -> Filters.or(filter.operands.map { compile(it, admitted, relative) })
        is NorFilter -> Filters.nor(filter.operands.map { compile(it, admitted, relative) })
        is EqualFilter -> Filters.eq(
            filter.field.resolve(admitted, relative),
            filter.value.nativeValue()
        )
        is NotEqualFilter -> Filters.ne(
            filter.field.resolve(admitted, relative),
            filter.value.nativeValue()
        )
        is GreaterThanFilter -> Filters.gt(
            filter.field.resolve(admitted, relative),
            filter.value.requiredNativeValue()
        )
        is GreaterThanOrEqualFilter -> Filters.gte(
            filter.field.resolve(admitted, relative),
            filter.value.requiredNativeValue()
        )
        is LessThanFilter -> Filters.lt(
            filter.field.resolve(admitted, relative),
            filter.value.requiredNativeValue()
        )
        is LessThanOrEqualFilter -> Filters.lte(
            filter.field.resolve(admitted, relative),
            filter.value.requiredNativeValue()
        )
        is ContainsFilter -> regex(
            filter.field.resolve(admitted, relative),
            filter.value.escapeRegex(),
            filter.stringComparison.ignoreCase
        )
        is StartsWithFilter -> regex(
            filter.field.resolve(admitted, relative),
            "^${filter.value.escapeRegex()}",
            filter.stringComparison.ignoreCase
        )
        is EndsWithFilter -> regex(
            filter.field.resolve(admitted, relative),
            "${filter.value.escapeRegex()}$",
            filter.stringComparison.ignoreCase
        )
        is InFilter -> Filters.`in`(
            filter.field.resolve(admitted, relative),
            filter.values.map { it.nativeValue() },
        )
        is NotInFilter -> Filters.nin(
            filter.field.resolve(admitted, relative),
            filter.values.map { it.nativeValue() },
        )
        is BetweenFilter -> Filters.and(
            Filters.gte(
                filter.field.resolve(admitted, relative),
                filter.lowerBound.requiredNativeValue()
            ),
            Filters.lte(
                filter.field.resolve(admitted, relative),
                filter.upperBound.requiredNativeValue()
            ),
        )
        is ContainsAllFilter -> Filters.all(
            filter.field.resolve(admitted, relative),
            filter.values.map { it.nativeValue() }
        )
        is IsEmptyFilter -> Filters.size(filter.field.resolve(admitted, relative), 0)
        is IsNullFilter -> Filters.eq(filter.field.resolve(admitted, relative), null)
        is IsNotNullFilter -> Filters.ne(filter.field.resolve(admitted, relative), null)
        is ExistsFilter -> Filters.exists(filter.field.resolve(admitted, relative))
        is NotExistsFilter -> Filters.exists(filter.field.resolve(admitted, relative), false)
        is DeletionFilter -> when (filter.deletionState) {
            DeletionState.ACTIVE -> Filters.eq(admitted.systemPath(filter), false)
            DeletionState.DELETED -> Filters.eq(admitted.systemPath(filter), true)
            DeletionState.ALL -> Filters.empty()
        }
        is ElementMatchFilter -> Filters.elemMatch(
            filter.field.resolve(admitted, relative),
            compile(filter.predicate, admitted, relative = true),
        )
        is SearchFilter -> Filters.text(
            if (filter.mode == SearchMode.PHRASE) {
                if ('"' in filter.query) {
                    throw QueryViolation.StorageUnsupported("double quotes in a PHRASE search query").rejection()
                }
                "\"${filter.query}\""
            } else {
                filter.query
            },
        )
        is ExpressionFilter -> Filters.expr(expressionComparison(filter, admitted))
        is IsEmptyStringFilter, is IsNotEmptyStringFilter, is RelativeTimeFilter ->
            throw QueryExecutionException("Filter [${filter.operator}] must be normalized before compilation.")
    }

    /** The comparison of a computed value that exists; `null` would order before every number. */
    private fun expressionComparison(filter: ExpressionFilter, admitted: AdmittedQuery<*>): Document {
        val operator = when (filter.comparison) {
            ComparisonOperator.EQ -> "\$eq"
            ComparisonOperator.NE -> "\$ne"
            ComparisonOperator.GT -> "\$gt"
            ComparisonOperator.GTE -> "\$gte"
            ComparisonOperator.LT -> "\$lt"
            ComparisonOperator.LTE -> "\$lte"
        }
        return Document(
            "\$let",
            Document("vars", Document("value", filter.expression.toMongoExpression(admitted))).append(
                "in",
                Document(
                    "\$and",
                    listOf(
                        Document("\$ne", listOf("\$\$value", null)),
                        Document(operator, listOf("\$\$value", filter.value)),
                    ),
                ),
            ),
        )
    }

    private fun AdmittedQuery<*>.systemPath(filter: FilterExpression): String = systemField(filter).physicalField.path

    private fun QueryField.resolve(admitted: AdmittedQuery<*>, relative: Boolean): String =
        admitted.field(this).let { if (relative) it.relativePhysicalField else it.physicalField }.path

    private val StringComparison.ignoreCase: Boolean
        get() = this == StringComparison.CASE_INSENSITIVE

    private fun tools.jackson.databind.JsonNode.nativeValue(): Any? = when {
        isNull -> null
        isString -> asString()
        isNumber -> numberValue()
        isBoolean -> booleanValue()
        isPojo -> (this as tools.jackson.databind.node.POJONode).pojo
        isArray -> asSequence().map { it.nativeValue() }.toList()
        else -> throw QueryExecutionException("Filter value must be a scalar, scalar array, or runtime POJO.")
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
