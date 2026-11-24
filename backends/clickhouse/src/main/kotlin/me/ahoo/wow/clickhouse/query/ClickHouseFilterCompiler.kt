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
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ContainsFilter
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
import me.ahoo.wow.query.admission.AdmittedQuery
import me.ahoo.wow.query.backend.CaseFolding
import me.ahoo.wow.query.dataset.DatasetAccess
import me.ahoo.wow.query.error.QueryExecutionException
import me.ahoo.wow.query.filter.predicateField
import me.ahoo.wow.query.resolve.ignoreCase
import me.ahoo.wow.query.resolve.operandValue
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.Temporal
import java.math.BigDecimal
import java.math.BigInteger
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
 * filters included (a dataset's identity, tenant, owner and space are its own columns); values are bound as typed
 * parameters.
 *
 * A dataset bound to an aggregate reads an access policy's restriction as one semi-join on the aggregate's current
 * access records ([ClickHouseDatasetAccess]).
 *
 * Null semantics follow the canonical matrix (`FilterSemantics`, MongoDB's): NE and NOT_IN also match a NULL, and
 * comparisons never do. A NULL is the table's only missing value (`AbsentValues.AS_MISSING`), so EXISTS reads it as
 * missing, and an array column stores no NULL, so an empty array is its missing value.
 */
internal class ClickHouseFilterCompiler(
    private val admitted: AdmittedQuery<*>,
    private val table: ClickHouseTable,
    private val parameters: SqlParameters,
    /** The current access records of a dataset bound to an aggregate; null for a dataset bound to none. */
    private val access: ClickHouseDatasetAccess? = null,
) {
    @Suppress("CyclomaticComplexMethod")
    fun compile(filter: FilterExpression): String = when (filter) {
        MatchAllFilter -> "1"
        MatchNoneFilter -> "0"
        is AndFilter -> filter.operands.joinToString(" AND ", "(", ")") { compile(it) }
        is OrFilter -> filter.operands.joinToString(" OR ", "(", ")") { compile(it) }
        is NorFilter -> nor(filter.operands.map(::compile))
        is IdFilter -> systemEqual(filter, filter.value)
        is IdsFilter -> {
            val column = systemColumn(filter)
            filter.values.joinToString(", ", "${column.equated(filter.values)} IN (", ")") { bind(column, it) }
        }
        is TenantIdFilter -> systemEqual(filter, filter.value)
        is OwnerIdFilter -> systemEqual(filter, filter.value)
        is SpaceIdFilter -> systemEqual(filter, filter.value)
        // An array column matches by element: a scalar operand is one of its values.
        is EqualFilter, is NotEqualFilter, is InFilter, is NotInFilter, is ContainsAllFilter, is IsEmptyFilter,
        is IsNullFilter, is IsNotNullFilter, is ExistsFilter, is NotExistsFilter,
        -> if (columnOf(checkNotNull(filter.predicateField())).array) array(filter) else scalar(filter)
        is GreaterThanFilter -> "${compared(filter.field)} > ${bind(filter.field, filter.value)}"
        is GreaterThanOrEqualFilter -> "${compared(filter.field)} >= ${bind(filter.field, filter.value)}"
        is LessThanFilter -> "${compared(filter.field)} < ${bind(filter.field, filter.value)}"
        is LessThanOrEqualFilter -> "${compared(filter.field)} <= ${bind(filter.field, filter.value)}"
        is BetweenFilter -> "${compared(filter.field)} BETWEEN ${bind(filter.field, filter.lowerBound)} AND " +
            bind(filter.field, filter.upperBound)
        is ContainsFilter -> if (filter.stringComparison.ignoreCase) {
            folded(filter.field, filter.value)
        } else {
            "position(${compared(filter.field)}, ${text(filter.value)}) > 0"
        }
        is StartsWithFilter -> if (filter.stringComparison.ignoreCase) {
            folded(filter.field, filter.value, before = "^")
        } else {
            "startsWith(${compared(filter.field)}, ${text(filter.value)})"
        }
        is EndsWithFilter -> if (filter.stringComparison.ignoreCase) {
            folded(filter.field, filter.value, after = "$")
        } else {
            "endsWith(${compared(filter.field)}, ${text(filter.value)})"
        }
        is ExpressionFilter -> expressionComparison(filter)
        // The one ELEMENT_MATCH a dataset's storage reads: an access policy's restriction (DatasetAccess).
        is ElementMatchFilter -> {
            val predicate = DatasetAccess.predicateOf(filter)
            if (predicate == null || access == null) throw unsupported(filter)
            access.compile(predicate, admitted, table, parameters)
        }
        // Admission never lets the others through: a dataset's storage grants no capability they need.
        else -> throw unsupported(filter)
    }

    /** A predicate on a scalar column. Admission normalizes EQ and NE with a null operand into IS_NULL and IS_NOT_NULL. */
    private fun scalar(filter: FilterExpression): String = when (filter) {
        is EqualFilter -> "${equated(filter.field, filter.value)} = ${bind(filter.field, filter.value)}"
        is NotEqualFilter ->
            orNull(filter.field, "${equated(filter.field, filter.value)} != ${bind(filter.field, filter.value)}")
        is InFilter -> "${equated(filter.field, filter.values)} IN ${terms(filter.field, filter.values)}"
        is NotInFilter ->
            orNull(filter.field, "${equated(filter.field, filter.values)} NOT IN ${terms(filter.field, filter.values)}")
        is IsNullFilter, is NotExistsFilter -> "isNull(${column(checkNotNull(filter.predicateField()))})"
        is IsNotNullFilter, is ExistsFilter -> "isNotNull(${column(checkNotNull(filter.predicateField()))})"
        // IS_EMPTY and CONTAINS_ALL need a collection, and the only collections are array columns.
        else -> throw unsupported(filter)
    }

    /**
     * A predicate on an array column: a scalar operand matches any element, and an empty array is the record without
     * values (`AbsentValues.AS_MISSING`), so presence reads emptiness. Exact array equality is not supported, so
     * admission never passes an array operand.
     */
    private fun array(filter: FilterExpression): String {
        val field = checkNotNull(filter.predicateField())
        val column = column(field)
        fun elements(values: List<Any?>) = values.joinToString(", ", "[", "]") { bind(field, it) }
        return when (filter) {
            is EqualFilter -> "has($column, ${bind(field, filter.value)})"
            is NotEqualFilter -> "NOT has($column, ${bind(field, filter.value)})"
            is InFilter -> "hasAny($column, ${elements(filter.values)})"
            is NotInFilter -> "NOT hasAny($column, ${elements(filter.values)})"
            is ContainsAllFilter -> "hasAll($column, ${elements(filter.values)})"
            is IsEmptyFilter, is IsNullFilter, is NotExistsFilter -> "empty($column)"
            is IsNotNullFilter, is ExistsFilter -> "notEmpty($column)"
            else -> throw unsupported(filter)
        }
    }

    private fun unsupported(filter: FilterExpression) =
        QueryViolation.StorageUnsupported("filter [${filter.operator}]").rejection()

    /** The quoted column [field] resolved to. */
    fun column(field: QueryField<*>): String = quoteIdentifier(admitted.physicalPath(field))

    fun columnOf(field: QueryField<*>): TypedColumn = table.column(admitted.physicalPath(field))

    /** [values] bound for [field], as an `IN` list. */
    private fun terms(field: QueryField<*>, values: List<Any?>): String =
        values.joinToString(", ", "(", ")") { bind(field, it) }

    /** [field]'s column as an order or a literal match compares it ([TypedColumn.compared]). */
    private fun compared(field: QueryField<*>): String = columnOf(field).compared

    /** [field]'s column as an equality with [value], or with each of [value]'s values, compares it ([equated]). */
    private fun equated(field: QueryField<*>, value: Any?): String =
        columnOf(field).equated(value as? List<*> ?: listOf(value))

    /**
     * The column as an equality with [values] compares it: natively while each is a value it prints, so a lookup keeps
     * its key's index, else as its text ([TypedColumn.prints]).
     */
    private fun TypedColumn.equated(values: List<*>): String =
        if (values.all { it.operandValue().let { value -> value !is String || prints(value) } }) {
            quoteIdentifier(name)
        } else {
            compared
        }

    /**
     * [field] as a `DateTime64`: a native date column at its own precision, an epoch column converted from its unit.
     * Admission admits a date group or date difference only on a field with one of these encodings.
     */
    fun instant(field: QueryField<*>): String {
        val column = column(field)
        val typed = columnOf(field)
        if (typed.temporal) return "toDateTime64($column, ${typed.scale}, 'UTC')"
        val epoch = admitted.field(field).temporal as? Temporal.Epoch
            ?: throw QueryExecutionException("Field [${field.path}] stores no instant.")
        val millis = when (epoch.timeUnit) {
            TimeUnit.SECONDS -> "toInt64($column) * 1000"
            TimeUnit.MILLISECONDS -> "toInt64($column)"
            else -> throw QueryViolation.StorageUnsupported("epochs in ${epoch.timeUnit}", field).rejection()
        }
        return "fromUnixTimestamp64Milli($millis, 'UTC')"
    }

    private fun systemColumn(filter: FilterExpression): TypedColumn = table.column(admitted.systemPath(filter))

    private fun systemEqual(filter: FilterExpression, value: String): String {
        val column = systemColumn(filter)
        return "${column.equated(listOf(value))} = ${bind(column, value)}"
    }

    private fun orNull(field: QueryField<*>, predicate: String): String =
        if (columnOf(field).nullable) "($predicate OR isNull(${column(field)}))" else predicate

    /**
     * An ignore-case literal match of [operand], between [before] and [after]: `match`, RE2 over the text's UTF-8,
     * case-sensitive, of the core's pattern of the texts that fold equal to it ([CaseFolding.pattern]). Not
     * `lowerUTF8` or `positionCaseInsensitiveUTF8`, whose folding is ClickHouse's own: `ς` is not `σ` to them. RE2's
     * `$` is the end of the text, never before a trailing newline. Neither form uses an index.
     */
    private fun folded(field: QueryField<*>, operand: String, before: String = "", after: String = ""): String =
        "match(${compared(field)}, ${text(before + CaseFolding.pattern(operand) + after)})"

    private fun text(value: String): String = parameters.bind(value, "String")

    private fun bind(field: QueryField<*>, value: Any?): String = bind(columnOf(field), value, field)

    /**
     * An operand of [column], bound in a type that holds it exactly, never narrowed to the column's own: the comparison
     * decides, not the bind. A date-time is a `DateTime64` at every digit it has (#6304), an epoch number its
     * milliseconds; a whole number of an integer column is an `Int256`, and any other number a `Decimal(76, s)` at its
     * own scale, whatever the column's width or scale (#6321); anything else is bound as its column's parameter type.
     */
    fun bind(column: TypedColumn, value: Any?, field: QueryField<*> = QueryField(column.name)): String {
        val operand = value.operandValue() ?: throw QueryExecutionException("A null operand cannot be bound.")
        if (operand is List<*>) {
            throw QueryViolation.StorageUnsupported("an array operand", field).rejection()
        }
        if (column.temporal) return instant(operand, column, field)
        if (column.family == ClickHouseTypeFamily.INTEGER || column.family == ClickHouseTypeFamily.DECIMAL) {
            return number(operand, column, field)
        }
        val text = when (operand) {
            is BigDecimal -> operand.toPlainString()
            else -> operand.toString()
        }
        return parameters.bind(text, column.parameterType)
    }

    private fun instant(operand: Any, column: TypedColumn, field: QueryField<*>): String = when (operand) {
        is Number -> "fromUnixTimestamp64Milli(${parameters.bind(operand.toLong().toString(), "Int64")}, 'UTC')"
        is String -> {
            val instant = operand.parseInstant(field)
            val digits = instant.nano.toString().padStart(NANO_DIGITS, '0').trimEnd('0').length
            val scale = maxOf(MILLI_DIGITS, column.scale, digits)
            "toDateTime64(${parameters.bind(DATE_TIME_TEXT.format(instant), "String")}, $scale, 'UTC')"
        }
        else -> throw QueryViolation.StorageUnsupported(
            "a [${operand.javaClass.simpleName}] operand for a date field",
            field
        )
            .rejection()
    }

    private fun number(operand: Any, column: TypedColumn, field: QueryField<*>): String {
        val exact = when (operand) {
            is BigDecimal -> operand
            is BigInteger -> BigDecimal(operand)
            is Double, is Float -> BigDecimal(operand.toString())
            is Number -> BigDecimal.valueOf(operand.toLong())
            // A cursor position's exact text: a decimal, or an integer past a long.
            is String -> operand.toBigDecimalOrNull()
            else -> null
        }?.stripTrailingZeros() ?: throw QueryViolation.StorageUnsupported(
            "a [${operand.javaClass.simpleName}] operand for a numeric field",
            field,
        ).rejection()
        val scale = maxOf(exact.scale(), 0)
        if (integral(exact) + scale > MAX_DECIMAL_DIGITS) {
            throw QueryViolation.StorageUnsupported(
                "a number of more than $MAX_DECIMAL_DIGITS digits",
                field
            ).rejection()
        }
        // A decimal column compares with a decimal, in its own type while that holds the operand (a Decimal256 against
        // its key reads every granule); an integer column with an Int256 while the operand is whole, which keeps it.
        val whole = scale == 0 && column.family == ClickHouseTypeFamily.INTEGER
        val own = column.decimal?.takeIf { (precision, digits) -> scale <= digits && integral(exact) <= precision - digits }
        val type = when {
            whole -> "Int256"
            own != null -> column.baseType
            else -> "Decimal($MAX_DECIMAL_DIGITS, $scale)"
        }
        return parameters.bind(exact.setScale(scale).toPlainString(), type)
    }

    private fun String.parseInstant(field: QueryField<*>): Instant = try {
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
        val value = expression(filter.expression)
        return "(isNotNull($value) AND $value ${comparison(filter.comparison)} ${filter.value.sqlLiteral()})"
    }

    /**
     * A numeric expression as `Nullable(Float64)`: a field without a value, a division by zero or a non-finite result
     * contributes nothing, as the protocol says. Admission takes only numeric fields as an operand; dates enter
     * through a date difference.
     */
    fun expression(expression: AggregationExpression): String = when (expression) {
        is AggregationExpression.Field -> "toFloat64(${column(expression.field)})"
        is AggregationExpression.Constant -> expression.value.sqlLiteral()
        is AggregationExpression.Binary -> {
            val left = expression(expression.left)
            val right = expression(expression.right)
            arithmetic(expression.operator, left, right)
        }
        is AggregationExpression.DateDiff -> {
            val from = "toUnixTimestamp64Milli(${instant(expression.from)})"
            val to = "toUnixTimestamp64Milli(${instant(expression.to)})"
            "(toFloat64($to - $from) / ${expression.unit.millis})"
        }
    }

    /** The digits of [value]'s whole part. */
    private fun integral(value: BigDecimal): Int = maxOf(value.precision() - value.scale(), 0)

    companion object {
        private const val NANO_DIGITS = 9
        private const val MILLI_DIGITS = 3

        /** The digits a `Decimal256` holds, and so an `Int256`'s too (76 of its 77). */
        private const val MAX_DECIMAL_DIGITS = 76

        /** An instant as `toDateTime64` parses it, every digit to the nanosecond, in UTC. */
        private val DATE_TIME_TEXT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSSSSS").withZone(ZoneOffset.UTC)

        /**
         * `left op right` as the protocol computes it, a finite double: a division by zero, and a result past the
         * double range (`inf`, `nan`), is NULL.
         */
        fun arithmetic(operator: AggregationExpressionOperator, left: String, right: String): String {
            val result = when (operator) {
                AggregationExpressionOperator.ADD -> "($left + $right)"
                AggregationExpressionOperator.SUBTRACT -> "($left - $right)"
                AggregationExpressionOperator.MULTIPLY -> "($left * $right)"
                AggregationExpressionOperator.DIVIDE -> "if($right = 0, NULL, $left / $right)"
            }
            return "ifNotFinite($result, NULL)"
        }

        /**
         * None of [operands] holds. An operand that is NULL (a comparison with a NULL, in ClickHouse's three-valued
         * logic) does not hold, so NOR holds for it, as the canonical semantics say: `NOT NULL` alone would be NULL.
         */
        fun nor(operands: List<String>): String = operands.joinToString(" OR ", "NOT ifNull((", "), 0)")

        fun comparison(operator: ComparisonOperator): String = when (operator) {
            ComparisonOperator.EQ -> "="
            ComparisonOperator.NE -> "!="
            ComparisonOperator.GT -> ">"
            ComparisonOperator.GTE -> ">="
            ComparisonOperator.LT -> "<"
            ComparisonOperator.LTE -> "<="
        }
    }
}
