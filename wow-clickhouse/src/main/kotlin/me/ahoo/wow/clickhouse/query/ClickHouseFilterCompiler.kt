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
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
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
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.QueryExecutionException
import me.ahoo.wow.query.ignoreCase
import me.ahoo.wow.query.operandValue
import me.ahoo.wow.query.schema.QueryViolation
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

/**
 * Translates an admitted filter into a `WHERE` predicate. Columns come from each node's resolution, system-field
 * filters included (a dataset's id, tenant, owner and space are its own columns); values are bound as typed
 * parameters.
 *
 * Null semantics: the AST leaves them to the storage (`filter-expression.md`). As in MongoDB, NE and NOT_IN also
 * match a NULL and comparisons never do. A NULL is the table's only missing value (`AbsentValues.AS_MISSING`), so
 * EXISTS reads it as missing.
 */
internal class ClickHouseFilterCompiler(
    private val admitted: AdmittedQuery<*>,
    private val table: ClickHouseTable,
    private val parameters: SqlParameters,
) {
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun compile(filter: FilterExpression): String = when (filter) {
        MatchAllFilter -> "1"
        MatchNoneFilter -> "0"
        is AndFilter -> filter.operands.joinToString(" AND ", "(", ")") { compile(it) }
        is OrFilter -> filter.operands.joinToString(" OR ", "(", ")") { compile(it) }
        is NorFilter -> filter.operands.joinToString(" OR ", "NOT (", ")") { compile(it) }
        is IdFilter -> systemEqual(filter, filter.value)
        is IdsFilter -> {
            val column = systemColumn(filter)
            filter.values.joinToString(", ", "${quoteIdentifier(column.name)} IN (", ")") {
                bind(column, TEXT.stringNode(it))
            }
        }
        is TenantIdFilter -> systemEqual(filter, filter.value)
        is OwnerIdFilter -> systemEqual(filter, filter.value)
        is SpaceIdFilter -> systemEqual(filter, filter.value)
        // Admission normalizes EQ and NE with a null operand into IS_NULL and IS_NOT_NULL.
        is EqualFilter -> "${column(filter.field)} = ${bind(filter.field, filter.value)}"
        is NotEqualFilter -> orNull(filter.field, "${column(filter.field)} != ${bind(filter.field, filter.value)}")
        is GreaterThanFilter -> "${column(filter.field)} > ${bind(filter.field, filter.value)}"
        is GreaterThanOrEqualFilter -> "${column(filter.field)} >= ${bind(filter.field, filter.value)}"
        is LessThanFilter -> "${column(filter.field)} < ${bind(filter.field, filter.value)}"
        is LessThanOrEqualFilter -> "${column(filter.field)} <= ${bind(filter.field, filter.value)}"
        is BetweenFilter -> "${column(filter.field)} BETWEEN ${bind(filter.field, filter.lowerBound)} AND " +
            bind(filter.field, filter.upperBound)
        is InFilter -> filter.values.joinToString(", ", "${column(filter.field)} IN (", ")") { bind(filter.field, it) }
        is NotInFilter -> orNull(
            filter.field,
            filter.values.joinToString(", ", "${column(filter.field)} NOT IN (", ")") { bind(filter.field, it) },
        )
        is ContainsFilter -> if (filter.stringComparison.ignoreCase) {
            "positionCaseInsensitiveUTF8(${column(filter.field)}, ${text(filter.value)}) > 0"
        } else {
            "position(${column(filter.field)}, ${text(filter.value)}) > 0"
        }
        is StartsWithFilter -> "startsWith(${folded(filter.field, filter.stringComparison.ignoreCase)}, " +
            "${foldedText(filter.value, filter.stringComparison.ignoreCase)})"
        is EndsWithFilter -> "endsWith(${folded(filter.field, filter.stringComparison.ignoreCase)}, " +
            "${foldedText(filter.value, filter.stringComparison.ignoreCase)})"
        is IsNullFilter -> "isNull(${column(filter.field)})"
        is NotExistsFilter -> "isNull(${column(filter.field)})"
        is IsNotNullFilter -> "isNotNull(${column(filter.field)})"
        is ExistsFilter -> "isNotNull(${column(filter.field)})"
        is ExpressionFilter -> expressionComparison(filter)
        // Admission never lets these through: the dataset storage grants no capability they need.
        else -> throw QueryViolation.StorageUnsupported("filter [${filter.operator}]").rejection()
    }

    /** The quoted column [field] resolved to. */
    fun column(field: QueryField): String = quoteIdentifier(admitted.physicalPath(field))

    fun columnOf(field: QueryField): ClickHouseColumn = table.column(admitted.physicalPath(field))

    /**
     * [field] as a `DateTime64`: a native date column itself, an epoch column converted from its unit. Admission
     * admits a date group or date difference only on a field with one of these encodings.
     */
    fun instant(field: QueryField): String {
        val column = column(field)
        if (columnOf(field).temporal) return "toDateTime64($column, 3, 'UTC')"
        val epoch = admitted.field(field).temporal as? Temporal.Epoch
            ?: throw QueryExecutionException("Field [${field.path}] stores no instant.")
        val millis = when (epoch.timeUnit) {
            TimeUnit.SECONDS -> "toInt64($column) * 1000"
            TimeUnit.MILLISECONDS -> "toInt64($column)"
            else -> throw QueryExecutionException("Field [${field.path}] stores epochs in an unsupported unit.")
        }
        return "fromUnixTimestamp64Milli($millis, 'UTC')"
    }

    private fun systemColumn(filter: FilterExpression): ClickHouseColumn = table.column(admitted.systemPath(filter))

    private fun systemEqual(filter: FilterExpression, value: String): String {
        val column = systemColumn(filter)
        return "${quoteIdentifier(column.name)} = ${bind(column, TEXT.stringNode(value))}"
    }

    private fun orNull(field: QueryField, predicate: String): String =
        if (columnOf(field).nullable) "($predicate OR isNull(${column(field)}))" else predicate

    private fun folded(field: QueryField, ignoreCase: Boolean): String =
        if (ignoreCase) "lowerUTF8(${column(field)})" else column(field)

    private fun foldedText(value: String, ignoreCase: Boolean): String =
        if (ignoreCase) "lowerUTF8(${text(value)})" else text(value)

    private fun text(value: String): String = parameters.bind(value, "String")

    private fun bind(field: QueryField, value: JsonNode): String = bind(columnOf(field), value, field)

    /** An operand of [column], bound as its parameter type; a date operand as epoch milliseconds. */
    fun bind(column: ClickHouseColumn, value: JsonNode, field: QueryField = QueryField(column.name)): String {
        val operand = value.operandValue() ?: throw QueryExecutionException("A null operand cannot be bound.")
        if (column.temporal) {
            return "fromUnixTimestamp64Milli(${parameters.bind(operand.epochMillis(field).toString(), "Int64")}, 'UTC')"
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
        else -> throw QueryViolation.StorageUnsupported("a [${javaClass.simpleName}] operand for a date field", field)
            .rejection()
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
     * A numeric expression as `Nullable(Float64)`: a field without a value, a division by zero or a non-finite result
     * contributes nothing, as the spec says. Admission takes only numeric fields as an operand; dates enter through
     * a date difference.
     */
    fun expression(expression: AggregationExpression): String = when (expression) {
        is AggregationExpression.Field -> "toFloat64(${column(expression.field)})"
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
            val from = "toUnixTimestamp64Milli(${instant(expression.from)})"
            val to = "toUnixTimestamp64Milli(${instant(expression.to)})"
            "(toFloat64($to - $from) / ${expression.unit.millis})"
        }
    }

    private companion object {
        val TEXT: JsonNodeFactory = JsonNodeFactory.instance
    }
}
