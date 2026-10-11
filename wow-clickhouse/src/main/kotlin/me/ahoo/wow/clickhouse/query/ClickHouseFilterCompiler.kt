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

package me.ahoo.wow.clickhouse.query

import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.InFilter
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
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.QueryExecutionException
import me.ahoo.wow.query.ignoreCase
import me.ahoo.wow.query.operandValue
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Translates an admitted filter into a `WHERE` predicate. Columns come from each node's resolution; values are
 * bound as typed parameters.
 *
 * Null semantics follow the AST spec: NE and NOT_IN also match a NULL (as MongoDB's `$ne` and `$nin` do), comparisons
 * never match one, and EXISTS reads a NULL as missing (the storage declares `AbsentValues.AS_MISSING`).
 */
internal class ClickHouseFilterCompiler(
    private val admitted: AdmittedQuery<*>,
    private val table: ClickHouseTable,
    private val parameters: SqlParameters,
) {
    @Suppress("CyclomaticComplexMethod")
    fun compile(filter: FilterExpression): String = when (filter) {
        MatchAllFilter -> "1"
        MatchNoneFilter -> "0"
        is AndFilter -> filter.operands.joinToString(" AND ", "(", ")") { compile(it) }
        is OrFilter -> filter.operands.joinToString(" OR ", "(", ")") { compile(it) }
        is NorFilter -> filter.operands.joinToString(" OR ", "NOT (", ")") { compile(it) }
        is EqualFilter -> if (filter.value.isNull) {
            "isNull(${column(filter.field)})"
        } else {
            "${column(filter.field)} = ${bind(filter.field, filter.value)}"
        }
        is NotEqualFilter -> if (filter.value.isNull) {
            "isNotNull(${column(filter.field)})"
        } else {
            orNull(filter.field, "${column(filter.field)} != ${bind(filter.field, filter.value)}")
        }
        is GreaterThanFilter -> "${column(filter.field)} > ${bind(filter.field, filter.value)}"
        is GreaterThanOrEqualFilter -> "${column(filter.field)} >= ${bind(filter.field, filter.value)}"
        is LessThanFilter -> "${column(filter.field)} < ${bind(filter.field, filter.value)}"
        is LessThanOrEqualFilter -> "${column(filter.field)} <= ${bind(filter.field, filter.value)}"
        is BetweenFilter -> "${column(filter.field)} BETWEEN ${bind(filter.field, filter.lowerBound)} AND " +
            bind(filter.field, filter.upperBound)
        is InFilter -> inList(filter.field, filter.values)
        is NotInFilter -> orNull(filter.field, "NOT ${inList(filter.field, filter.values)}")
        is ContainsFilter -> if (filter.stringComparison.ignoreCase) {
            "positionCaseInsensitiveUTF8(${column(filter.field)}, ${text(filter.value)}) > 0"
        } else {
            "position(${column(filter.field)}, ${text(filter.value)}) > 0"
        }
        is StartsWithFilter -> "startsWith(${caseFolded(filter.field, filter.stringComparison.ignoreCase)}, " +
            "${caseFoldedText(filter.value, filter.stringComparison.ignoreCase)})"
        is EndsWithFilter -> "endsWith(${caseFolded(filter.field, filter.stringComparison.ignoreCase)}, " +
            "${caseFoldedText(filter.value, filter.stringComparison.ignoreCase)})"
        is IsNullFilter, is NotExistsFilter -> "isNull(${column((filter as me.ahoo.wow.api.query.FieldPredicate).field)})"
        is IsNotNullFilter, is ExistsFilter -> "isNotNull(${column((filter as me.ahoo.wow.api.query.FieldPredicate).field)})"
        is ExpressionFilter -> expressionComparison(filter)
        else -> throw QueryExecutionException("ClickHouse does not translate filter [${filter.operator}].")
    }

    fun column(field: QueryField): String = quoteIdentifier(admitted.physicalPath(field))

    fun isTemporal(field: QueryField): Boolean = columnOf(field).temporal

    private fun columnOf(field: QueryField): ClickHouseColumn = table.column(admitted.physicalPath(field))

    private fun orNull(field: QueryField, predicate: String): String =
        if (columnOf(field).nullable) "($predicate OR isNull(${column(field)}))" else predicate

    private fun inList(field: QueryField, values: List<JsonNode>): String {
        val (nulls, present) = values.partition(JsonNode::isNull)
        val members = present.joinToString(", ", "${column(field)} IN (", ")") { bind(field, it) }
        val terms = listOfNotNull(members.takeIf { present.isNotEmpty() }, "isNull(${column(field)})".takeIf { nulls.isNotEmpty() })
        return if (terms.isEmpty()) "0" else terms.joinToString(" OR ", "(", ")")
    }

    private fun caseFolded(field: QueryField, ignoreCase: Boolean): String =
        if (ignoreCase) "lowerUTF8(${column(field)})" else column(field)

    private fun caseFoldedText(value: String, ignoreCase: Boolean): String =
        if (ignoreCase) "lowerUTF8(${text(value)})" else text(value)

    private fun text(value: String): String = parameters.bind(value, "String")

    /** An operand of [field], bound as the column's parameter type; a date operand as epoch milliseconds. */
    private fun bind(field: QueryField, value: JsonNode): String {
        val column = columnOf(field)
        val operand = value.operandValue() ?: throw QueryExecutionException("A null operand cannot be bound.")
        if (column.temporal) {
            val millis = parameters.bind(operand.epochMillis(field).toString(), "Int64")
            return "fromUnixTimestamp64Milli($millis, 'UTC')"
        }
        val text = when (operand) {
            is BigDecimal -> operand.toPlainString()
            else -> operand.toString()
        }
        return parameters.bind(text, column.parameterType)
    }

    private fun Any.epochMillis(field: QueryField): Long = when (this) {
        is Instant -> toEpochMilli()
        is Number -> toLong()
        is String -> parseInstant(field).toEpochMilli()
        else -> throw QueryExecutionException("Field [${field.path}] cannot compare with a [${javaClass.simpleName}].")
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
            throw QueryExecutionException("Field [${field.path}] needs an ISO-8601 date or date-time operand.")
        }
    }

    private fun expressionComparison(filter: ExpressionFilter): String {
        val operator = when (filter.comparison) {
            ComparisonOperator.EQ -> "="
            ComparisonOperator.NE -> "!="
            ComparisonOperator.GT -> ">"
            ComparisonOperator.GTE -> ">="
            ComparisonOperator.LT -> "<"
            ComparisonOperator.LTE -> "<="
        }
        val value = expression(filter.expression)
        return "(isNotNull($value) AND $value $operator ${filter.value.sqlLiteral()})"
    }

    /**
     * A numeric expression as `Nullable(Float64)`: a field without a value, a division by zero or a non-finite
     * result contributes nothing, as the spec says.
     */
    fun expression(expression: AggregationExpression): String = when (expression) {
        is AggregationExpression.Field -> {
            val column = columnOf(expression.field)
            if (column.temporal) {
                "toFloat64(toUnixTimestamp64Milli(toDateTime64(${column(expression.field)}, 3)))"
            } else {
                "toFloat64(${column(expression.field)})"
            }
        }
        is AggregationExpression.Constant -> expression.value.sqlLiteral()
        is AggregationExpression.Binary -> {
            val left = expression(expression.left)
            val right = expression(expression.right)
            when (expression.operator) {
                AggregationExpressionOperator.ADD -> "($left + $right)"
                AggregationExpressionOperator.SUBTRACT -> "($left - $right)"
                AggregationExpressionOperator.MULTIPLY -> "($left * $right)"
                AggregationExpressionOperator.DIVIDE -> "if($right = 0, NULL, $left / $right)"
            }
        }
        is AggregationExpression.DateDiff -> {
            val from = "toUnixTimestamp64Milli(toDateTime64(${column(expression.from)}, 3))"
            val to = "toUnixTimestamp64Milli(toDateTime64(${column(expression.to)}, 3))"
            "(toFloat64($to - $from) / ${expression.unit.millis})"
        }
    }
}
