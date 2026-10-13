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

package me.ahoo.wow.postgres.query

import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
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
import me.ahoo.wow.api.query.FieldPredicate
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
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.QueryExecutionException
import me.ahoo.wow.query.ResolvedField
import me.ahoo.wow.query.schema.QueryCardinality
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

/**
 * Compiles the admitted filters and expressions of one statement to SQL over the snapshot table, with the canonical
 * semantics of the filter matrix (`FilterSemantics`, MongoDB's): a missing field and a JSON `null` are both null, a
 * negation matches the records without the field, a range or a literal match never does, and on a multi-valued
 * field a scalar operand matches any element while an array operand of `EQ` / `NE` is the whole array.
 *
 * A single-valued field compares its typed value ([PostgresSnapshotQueryExpressions.value]), the expression its
 * index is created on; a multi-valued one compares its `jsonb` node: containment (`@>`) for equality, `EXISTS` over its
 * elements for a range or a literal match. Every operand is bound ([SqlParameters]); an operand whose JSON type is not
 * the field's binds as a typed `NULL`, so it matches nothing, as a value of another type never equals it.
 *
 * Fields inside an `ELEMENT_MATCH` or an expanded aggregation element read the element: [enter] maps the element's
 * physical container to the SQL alias of the element row.
 */
@Suppress("LargeClass")
internal class PostgresFilterCompiler(
    private val query: AdmittedQuery<*>,
    private val parameters: SqlParameters,
) {
    private val scopes = HashMap<QueryField, String>()
    private var aliases = 0

    /** The resolution of [reference], a field node of the admitted query. */
    fun field(reference: QueryField): ResolvedField = query.field(reference)

    /** A fresh alias for an element row. */
    fun nextAlias(): String = "e${++aliases}"

    /**
     * Reads the fields inside [container] from [element], a `jsonb` element expression, while [block] runs. Element
     * scopes nest by path, so a container is never its own descendant and no outer scope of it is hidden.
     */
    fun <T> within(container: QueryField, element: String, block: () -> T): T {
        scopes[container] = element
        return try {
            block()
        } finally {
            scopes.remove(container)
        }
    }

    /** Reads the fields inside [container] from [element] from now on: an aggregation's expanded element. */
    fun enter(container: QueryField, element: String) {
        scopes[container] = element
    }

    /** The SQL predicate of [filter]; it is never NULL where a negation reads it ([NorFilter]). */
    @Suppress("CyclomaticComplexMethod")
    fun filter(filter: FilterExpression): String = when (filter) {
        MatchAllFilter -> TRUE
        MatchNoneFilter -> FALSE
        is AndFilter -> filter.operands.joinToString(" AND ", "(", ")") { filter(it) }
        is OrFilter -> filter.operands.joinToString(" OR ", "(", ")") { filter(it) }
        is NorFilter -> "NOT coalesce(" + filter.operands.joinToString(" OR ", "(", ")") { filter(it) } + ", false)"
        is IdFilter -> system(filter, filter.value)
        is IdsFilter -> systemIn(filter, filter.values)
        is AggregateIdFilter -> system(filter, filter.value)
        is AggregateIdsFilter -> systemIn(filter, filter.values)
        is TenantIdFilter -> system(filter, filter.value)
        is OwnerIdFilter -> system(filter, filter.value)
        is SpaceIdFilter -> system(filter, filter.value)
        is DeletionFilter -> deletion(filter)
        is ElementMatchFilter -> elementMatch(filter)
        is ExpressionFilter -> "(${numeric(filter.expression)} ${comparison(filter.comparison)} " +
            parameters.bind(SqlParameter.Numeric(BigDecimal.valueOf(filter.value))) + ")"
        is FieldPredicate -> predicate(filter)
        else -> throw QueryExecutionException("Filter [${filter.operator}] does not reach a PostgreSQL backend.")
    }

    private fun systemColumn(filter: FilterExpression): String =
        PostgresSnapshotQueryExpressions.value(query.systemField(filter).physicalField, PostgresQueryType.TEXT)

    private fun system(filter: FilterExpression, value: String): String =
        "${systemColumn(filter)} = ${parameters.bind(SqlParameter.Text(value))}"

    private fun systemIn(filter: FilterExpression, values: List<String>): String =
        "${systemColumn(filter)} = ANY(${parameters.bind(SqlParameter.Array(TEXT_ARRAY, values))})"

    private fun deletion(filter: DeletionFilter): String {
        val column = PostgresSnapshotQueryExpressions.value(
            query.systemField(filter).physicalField,
            PostgresQueryType.BOOLEAN
        )
        return when (filter.deletionState) {
            DeletionState.ACTIVE -> "NOT $column"
            DeletionState.DELETED -> column
            DeletionState.ALL -> TRUE
        }
    }

    private fun elementMatch(filter: ElementMatchFilter): String {
        val container = query.field(filter.field)
        val alias = nextAlias()
        val elements = objectElements(node(container))
        val predicate = within(container.physicalField, "$alias.v") { filter(filter.predicate) }
        return "EXISTS (SELECT 1 FROM jsonb_array_elements($elements) AS $alias(v) " +
            "WHERE jsonb_typeof($alias.v) = 'object' AND $predicate)"
    }

    @Suppress("CyclomaticComplexMethod")
    private fun predicate(filter: FieldPredicate): String {
        val field = query.field(filter.field)
        return when (filter) {
            is EqualFilter -> equal(field, filter.value.json())
            is NotEqualFilter -> notEqual(field, filter.value.json())
            is InFilter -> member(field, filter.values.map { it.json() })
            is NotInFilter -> "(${member(field, filter.values.map { it.json() })}) IS NOT TRUE"
            is ContainsAllFilter -> "(${asArray(
                node(field)
            )} @> ${json(nodes.arrayNode().addAll(filter.values.map { it.json() }))})"
            is GreaterThanFilter -> range(field, ">", filter.value.json())
            is GreaterThanOrEqualFilter -> range(field, ">=", filter.value.json())
            is LessThanFilter -> range(field, "<", filter.value.json())
            is LessThanOrEqualFilter -> range(field, "<=", filter.value.json())
            is BetweenFilter -> "(" + range(field, ">=", filter.lowerBound.json()) + " AND " +
                range(field, "<=", filter.upperBound.json()) + ")"
            is ContainsFilter -> like(field, "%" + filter.value.escapeLike() + "%", filter.stringComparison)
            is StartsWithFilter -> like(field, filter.value.escapeLike() + "%", filter.stringComparison)
            is EndsWithFilter -> like(field, "%" + filter.value.escapeLike(), filter.stringComparison)
            is IsNullFilter -> isNull(field)
            is IsNotNullFilter -> "coalesce(jsonb_typeof(${node(field)}), 'null') <> 'null'"
            is ExistsFilter -> "(${node(field)} IS NOT NULL)"
            is NotExistsFilter -> "(${node(field)} IS NULL)"
            is IsEmptyFilter -> "(${node(field)} = '[]'::jsonb)"
            // Admission lowers IS_EMPTY_STRING, IS_NOT_EMPTY_STRING and relative time; SEARCH is never granted.
            else -> throw QueryExecutionException("Filter [${filter.operator}] does not reach a PostgreSQL backend.")
        }
    }

    private fun isNull(field: ResolvedField): String = "coalesce(jsonb_typeof(${node(field)}), 'null') = 'null'"

    /** Admission lowers an `EQ` or `NE` of `null` to `IS_NULL` / `IS_NOT_NULL`, so [operand] is never null here. */
    private fun equal(field: ResolvedField, operand: JsonNode): String = when {
        operand.isArray -> "(${node(field)} = ${json(operand)})"
        field.multiValued -> "(${node(field)} @> ${json(operand)})"
        else -> {
            val (value, type) = typed(field)
            "($value = ${scalar(type, operand)})"
        }
    }

    private fun notEqual(field: ResolvedField, operand: JsonNode): String = when {
        operand.isArray -> "(${node(field)} IS DISTINCT FROM ${json(operand)})"
        else -> "(${equal(field, operand)}) IS NOT TRUE"
    }

    private fun member(field: ResolvedField, operands: List<JsonNode>): String {
        if (field.multiValued) {
            val texts = operands.map { WowJson.mapper.writeValueAsString(it) }
            return "(${node(field)} @> ANY(${parameters.bind(SqlParameter.Array(TEXT_ARRAY, texts))}::jsonb[]))"
        }
        val (value, type) = typed(field)
        return "($value = ANY(${array(type, operands)}))"
    }

    private fun range(field: ResolvedField, operator: String, operand: JsonNode): String =
        anyValue(field) { value, type -> "$value $operator ${scalar(type, operand)}" }

    private fun like(field: ResolvedField, pattern: String, comparison: StringComparison): String =
        anyValue(field) { value, _ ->
            val bound = parameters.bind(SqlParameter.Text(pattern))
            if (comparison == StringComparison.CASE_INSENSITIVE) {
                "lower($value COLLATE \"default\") LIKE lower($bound) ESCAPE '\\'"
            } else {
                "$value LIKE $bound ESCAPE '\\'"
            }
        }

    /**
     * [predicate] over the typed value of a single-valued field, or `EXISTS` of an element of a multi-valued one
     * satisfying it: a scalar stored in a multi-valued field counts as its one element.
     */
    private fun anyValue(field: ResolvedField, predicate: (String, PostgresQueryType) -> String): String {
        val type = type(field)
        if (!field.multiValued) return "(${predicate(typed(field).first, type)})"
        val alias = nextAlias()
        val element = PostgresSnapshotQueryExpressions.value("$alias.v", emptyList(), type)
        return "EXISTS (SELECT 1 FROM jsonb_array_elements(${asArray(node(field))}) AS $alias(v) " +
            "WHERE ${predicate(element, type)})"
    }

    /** The typed value of [field] and its type, read where [field] lies: the record, or its element. */
    fun typed(field: ResolvedField): Pair<String, PostgresQueryType> {
        val type = type(field)
        val value = locate(field) { base, segments ->
            if (base == null) {
                PostgresSnapshotQueryExpressions.value(field.physicalField, type)
            } else {
                PostgresSnapshotQueryExpressions.value(base, segments, type)
            }
        }
        return value to type
    }

    /** The `jsonb` node of [field], read where [field] lies. */
    fun node(field: ResolvedField): String = locate(field) { base, segments ->
        if (base == null) {
            PostgresSnapshotQueryExpressions.node(field.physicalField)
        } else {
            PostgresSnapshotQueryExpressions.node(base, segments)
        }
    }

    private fun <T> locate(field: ResolvedField, read: (String?, List<String>) -> T): T {
        val parent = field.physicalParent ?: return read(null, emptyList())
        // Admission resolves a field inside an element scope only within that scope, which the caller entered.
        return read(scopes.getValue(parent), field.relativePhysicalField.path.split('.'))
    }

    fun type(field: ResolvedField): PostgresQueryType = PostgresQueryType.require(
        field.storageTypes,
        field.logicalField
    )

    /** The numeric SQL value of an aggregation [expression]; `NULL` where a field holds no number or a divisor is 0. */
    fun numeric(expression: AggregationExpression): String = when (expression) {
        is AggregationExpression.Field -> {
            val field = query.field(expression.field)
            val (value, type) = typed(field)
            requireExecution(type.numeric) { "Field [${field.logicalField}] is not numeric." }
            value
        }
        is AggregationExpression.Constant -> "${parameters.bind(
            SqlParameter.Numeric(BigDecimal.valueOf(expression.value))
        )}::numeric"
        is AggregationExpression.Binary -> {
            val left = numeric(expression.left)
            val right = numeric(expression.right)
            when (expression.operator) {
                AggregationExpressionOperator.ADD -> "($left + $right)"
                AggregationExpressionOperator.SUBTRACT -> "($left - $right)"
                AggregationExpressionOperator.MULTIPLY -> "($left * $right)"
                AggregationExpressionOperator.DIVIDE -> "($left / NULLIF($right, 0))"
            }
        }
        is AggregationExpression.DateDiff -> {
            val from = epochMillis(expression.from)
            val to = epochMillis(expression.to)
            "(($to - $from) / ${expression.unit.millis}.0)"
        }
    }

    /** The instant [reference] holds, in epoch milliseconds; admission granted it a temporal capability, an epoch. */
    fun epochMillis(reference: QueryField): String {
        val field = query.field(reference)
        val value = typed(field).first
        // Admission grants temporal operations only to an epoch: this storage does not support date strings.
        val unit = (field.temporal as Temporal.Epoch).timeUnit
        val perUnit = unit.toNanos(1)
        return when {
            unit == TimeUnit.MILLISECONDS -> value
            perUnit > NANOS_PER_MILLI -> "($value * ${perUnit / NANOS_PER_MILLI})"
            else -> "floor($value / ${NANOS_PER_MILLI / perUnit}.0)"
        }
    }

    private fun scalar(type: PostgresQueryType, operand: JsonNode): String = parameters.bind(
        scalarParameter(type, operand)
    )

    private fun array(type: PostgresQueryType, operands: List<JsonNode>): String {
        val values = operands.map { scalarParameter(type, it) }
        val parameter = when (type) {
            PostgresQueryType.TEXT -> SqlParameter.Array(
                TEXT_ARRAY,
                values.mapNotNull { (it as SqlParameter.Text).value }
            )
            PostgresQueryType.BOOLEAN -> SqlParameter.Array(
                "bool",
                values.mapNotNull { (it as SqlParameter.Bool).value }
            )
            else -> SqlParameter.Array("numeric", operands.mapNotNull { it.decimal() })
        }
        return parameters.bind(parameter)
    }

    private fun json(node: JsonNode): String =
        parameters.bind(SqlParameter.Json(WowJson.mapper.writeValueAsString(node))) + "::jsonb"

    private val ResolvedField.multiValued: Boolean
        get() = cardinality != QueryCardinality.SINGLE

    companion object {
        const val TRUE = "TRUE"
        const val FALSE = "FALSE"
        private const val TEXT_ARRAY = "text"
        private const val NANOS_PER_MILLI = 1_000_000L
        val nodes: JsonNodeFactory = JsonNodeFactory.instance

        fun comparison(operator: ComparisonOperator): String = when (operator) {
            ComparisonOperator.EQ -> "="
            ComparisonOperator.NE -> "<>"
            ComparisonOperator.GT -> ">"
            ComparisonOperator.GTE -> ">="
            ComparisonOperator.LT -> "<"
            ComparisonOperator.LTE -> "<="
        }

        /** A filter operand as the JSON a record stores it as. */
        fun Any?.json(): JsonNode = when (this) {
            null -> nodes.nullNode()
            is JsonNode -> this
            else -> WowJson.mapper.valueToTree(this)
        }

        /** [operand] bound as [type]: an operand of another JSON type is a typed `NULL`, which matches nothing. */
        fun scalarParameter(type: PostgresQueryType, operand: JsonNode): SqlParameter = when (type) {
            PostgresQueryType.TEXT -> SqlParameter.Text(operand.takeIf { it.isString }?.stringValue())
            PostgresQueryType.BOOLEAN -> SqlParameter.Bool(operand.takeIf { it.isBoolean }?.booleanValue())
            PostgresQueryType.INTEGER, PostgresQueryType.BIGINT -> integral(operand)
            PostgresQueryType.NUMERIC -> SqlParameter.Numeric(operand.decimal())
            PostgresQueryType.JSONB -> throw QueryExecutionException("A jsonb value has no scalar operand.")
        }

        /** An integral operand of an integer column as `int8`, which keeps the column's index; any other as numeric. */
        private fun integral(operand: JsonNode): SqlParameter =
            if (operand.isIntegralNumber && operand.canConvertToLong()) {
                SqlParameter.Int8(operand.longValue())
            } else {
                SqlParameter.Numeric(operand.decimal())
            }

        fun JsonNode.decimal(): BigDecimal? = when {
            isIntegralNumber -> BigDecimal(bigIntegerValue())
            isNumber -> decimalValue()
            else -> null
        }

        /** `jsonb` as an array: itself when it is one, else an array of it (a scalar, `null`, or SQL `NULL`). */
        fun asArray(node: String): String =
            "(CASE WHEN jsonb_typeof($node) = 'array' THEN $node ELSE jsonb_build_array($node) END)"

        /** The objects `jsonb` holds: an array's elements, an object itself, or none. */
        fun objectElements(node: String): String =
            "(CASE WHEN jsonb_typeof($node) = 'array' THEN $node " +
                "WHEN jsonb_typeof($node) = 'object' THEN jsonb_build_array($node) ELSE '[]'::jsonb END)"

        /** [this] with the LIKE wildcards and the escape character escaped, so it matches literally. */
        fun String.escapeLike(): String = replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}
