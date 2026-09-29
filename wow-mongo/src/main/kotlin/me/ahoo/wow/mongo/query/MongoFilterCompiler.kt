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
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.mongo.query.aggregation.toMongoExpression
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.PojoOperands
import me.ahoo.wow.query.QueryExecutionException
import me.ahoo.wow.query.ResolvedField
import me.ahoo.wow.query.ignoreCase
import me.ahoo.wow.query.operandValue
import me.ahoo.wow.query.requiredOperandValue
import me.ahoo.wow.query.schema.QueryViolation
import org.bson.Document
import org.bson.conversions.Bson
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Date
import kotlin.math.floor

/**
 * Compiles an admitted filter to a MongoDB match document. One compiler serves both query models: admission has
 * already resolved every field to its physical path, so nothing here depends on the model.
 */
object MongoFilterCompiler {
    private val ESCAPE_CHARS = setOf('\\', '^', '$', '.', '|', '?', '*', '+', '(', ')', '[', ']', '{', '}')

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
            filter.value.operand(filter.field, admitted)
        )
        is NotEqualFilter -> Filters.ne(
            filter.field.resolve(admitted, relative),
            filter.value.operand(filter.field, admitted)
        )
        is GreaterThanFilter -> Filters.gt(
            filter.field.resolve(admitted, relative),
            filter.value.requiredOperand(filter.field, admitted)
        )
        is GreaterThanOrEqualFilter -> Filters.gte(
            filter.field.resolve(admitted, relative),
            filter.value.requiredOperand(filter.field, admitted)
        )
        is LessThanFilter -> Filters.lt(
            filter.field.resolve(admitted, relative),
            filter.value.requiredOperand(filter.field, admitted)
        )
        is LessThanOrEqualFilter -> Filters.lte(
            filter.field.resolve(admitted, relative),
            filter.value.requiredOperand(filter.field, admitted)
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
            filter.values.map { it.operand(filter.field, admitted) },
        )
        is NotInFilter -> Filters.nin(
            filter.field.resolve(admitted, relative),
            filter.values.map { it.operand(filter.field, admitted) },
        )
        is BetweenFilter -> Filters.and(
            Filters.gte(
                filter.field.resolve(admitted, relative),
                filter.lowerBound.requiredOperand(filter.field, admitted)
            ),
            Filters.lte(
                filter.field.resolve(admitted, relative),
                filter.upperBound.requiredOperand(filter.field, admitted)
            ),
        )
        is ContainsAllFilter -> Filters.all(
            filter.field.resolve(admitted, relative),
            filter.values.map { it.operand(filter.field, admitted) }
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
        return Document(
            "\$let",
            Document("vars", Document("value", filter.expression.toMongoExpression(admitted))).append(
                "in",
                Document(
                    "\$and",
                    listOf(
                        Document("\$ne", listOf("\$\$value", null)),
                        Document(filter.comparison.mongoOperator, listOf("\$\$value", filter.value)),
                    ),
                ),
            ),
        )
    }

    private fun QueryField.resolve(admitted: AdmittedQuery<*>, relative: Boolean): String =
        admitted.field(this).let { if (relative) it.relativePhysicalField else it.physicalField }.path

    /** An operand [field] is compared with, as the driver value it is stored as. */
    private fun JsonNode.operand(field: QueryField, admitted: AdmittedQuery<*>): Any? =
        operandValue(PojoOperands.NATIVE).storedAs(admitted.field(field))

    /** A non-null scalar operand [field] is compared with (a range bound or a term), as the value it is stored as. */
    private fun JsonNode.requiredOperand(field: QueryField, admitted: AdmittedQuery<*>): Any =
        requiredOperandValue(PojoOperands.NATIVE).let { checkNotNull(it.storedAs(admitted.field(field))) }

    /**
     * A [Temporal.Date] field stores BSON dates, which never equal the JSON string or number a client sends, so its
     * operands become dates: an ISO-8601 date-time (UTC when it has no offset), an ISO-8601 date (its UTC midnight)
     * or epoch milliseconds, as Elasticsearch reads a date operand. Any other field keeps the operand as it is.
     */
    private fun Any?.storedAs(field: ResolvedField): Any? = when {
        this == null || field.temporal != Temporal.Date -> this
        this is List<*> -> map { it.storedAs(field) }
        else -> toBsonDate(field.logicalField)
    }

    private fun Any.toBsonDate(field: QueryField): Any = when (this) {
        is Date -> this
        is Instant -> Date.from(this)
        is String -> Date.from(parseInstant(field))
        is Number -> Date(epochMillis())
        // A native POJO other than a date (deprecated Condition operands) reaches the driver as it is.
        is Boolean -> throw QueryViolation.StorageUnsupported("a boolean operand for a date field", field).rejection()
        else -> this
    }

    private fun String.parseInstant(field: QueryField): Instant = try {
        when (val parsed = DateTimeFormatter.ISO_DATE_TIME.parseBest(this, ZonedDateTime::from, LocalDateTime::from)) {
            is ZonedDateTime -> parsed.toInstant()
            else -> (parsed as LocalDateTime).toInstant(ZoneOffset.UTC)
        }
    } catch (_: DateTimeParseException) {
        try {
            LocalDate.parse(this).atStartOfDay(ZoneOffset.UTC).toInstant()
        } catch (_: DateTimeParseException) {
            throw QueryViolation.StorageUnsupported("a date operand that is not an ISO-8601 date or date-time", field)
                .rejection()
        }
    }

    /** Whole milliseconds; a fraction is floored, as a BSON date holds milliseconds. */
    private fun Number.epochMillis(): Long = when (this) {
        is Long, is Int, is Short, is Byte -> toLong()
        is BigInteger -> longValueExact()
        is BigDecimal -> setScale(0, RoundingMode.FLOOR).longValueExact()
        else -> floor(toDouble()).toLong()
    }

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
