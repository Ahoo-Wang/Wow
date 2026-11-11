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

import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterCapable
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
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.clickhouse.sink.AccessColumns
import me.ahoo.wow.clickhouse.sink.ClickHouseSinkSchema
import me.ahoo.wow.query.admission.AdmittedQuery
import me.ahoo.wow.query.dataset.DatasetAccess
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasetScope
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.spi.access.AccessValues

/**
 * The access restriction of a dataset bound to an aggregate ([DatasetAccess]) on ClickHouse: one semi-join of the
 * dataset's rows on the current access records of their aggregate, which the Sink keeps in that aggregate's
 * `<ctx>_<agg>_state` in [database] (`wow.clickhouse.database`). That table holds one row per commit, replaced by
 * version, so after `FINAL` each aggregate's row is its newest commit's: its `access` column holds the attributes after
 * it as JSON text ([me.ahoo.wow.spi.access.AccessValues]), empty when it recorded none, and the Sink computes a typed
 * column of each declared attribute from it, `access_<name>` (`Nullable(T)`, or `Array(T)` for a multiple one):
 *
 * ```sql
 * (<tenant column>, <id column>) IN (
 *   SELECT tenant_id, aggregate_id FROM <database>.<ctx>_<agg>_state FINAL
 *   WHERE tenant_id IN (<the query's tenants>) AND access != '' AND <predicate>)
 * ```
 *
 * - The pair is the aggregate's key: an id is unique within its tenant. A platform-level dataset's rows are of the
 *   aggregates of its tenant ([QueryDatasetScope.PLATFORM_TENANT]), so it joins on the id alone. The tenants the query
 *   restricts its rows to at its root (the caller's scope) also restrict the records read, which the table's key
 *   orders by; a query restricted to none reads every tenant's.
 * - The predicate is read on the newest record only: `FINAL` applies the `WHERE` after it merges the rows, so an
 *   older record never grants. An aggregate without a record, or with one of no attributes, is never read.
 * - Each comparison reads its attribute's column ([state], `_state`'s columns): `EQ` and `IN` hold of a value, or of
 *   an element of a multiple one, `CONTAINS_ALL` of all of them, `STARTS_WITH` of a string, each only of an operand of
 *   the column's type; `IS_NULL` of an attribute holding no value. The built-in `owner` and `space`, which have no
 *   column, are read from `access` as strings, as the attributes the record holds. A multiple attribute's `IS_NULL`, `IS_NOT_NULL` and `IS_EMPTY` read the type of
 *   its JSON value, since an empty array column cannot tell a missing attribute (a record written before it was
 *   declared) from an empty one. A `NULL` comparison holds of no row, under `NOR` too ([ClickHouseFilterCompiler.nor]).
 * - An attribute `_state` has no column of yet (the Sink has not added one a later version declared) is refused.
 */
internal class ClickHouseDatasetAccess(
    private val dataset: QueryDataset,
    database: String,
    /** The columns of the aggregate's `_state`, read as the dataset's table's are, and [load]ed before a query. */
    private val state: ClickHouseTableMetadata,
) {
    private val bound = requireNotNull(dataset.scope.access) { "Dataset [${dataset.name}] is bound to no aggregate." }
    private val source =
        "${quoteIdentifier(database)}.${quoteIdentifier(ClickHouseSinkSchema.stateOf(bound.aggregate))} FINAL"

    /** Reads `_state`'s columns, again once their time to live has passed, before a query compiles a restriction. */
    suspend fun load() {
        state.table()
    }

    /** The semi-join of [predicate], an access restriction's, over the rows of [table] that [admitted] reads. */
    fun compile(
        predicate: FilterExpression,
        admitted: AdmittedQuery<*>,
        table: ClickHouseTable,
        parameters: SqlParameters,
    ): String {
        // A String column, as the adapter checks: compared with `aggregate_id` as it is.
        val key = quoteIdentifier(table.column(bound.id.path).name)
        val tenant = dataset.scope.tenant
        val conditions = buildList {
            if (tenant == null) {
                add("tenant_id = ${parameters.bind(QueryDatasetScope.PLATFORM_TENANT, STRING)}")
            } else {
                tenantsOf(admitted)?.let { tenants ->
                    add(tenants.joinToString(", ", "tenant_id IN (", ")") { parameters.bind(it, STRING) })
                }
            }
            add("access != ''")
            add(Predicates(parameters, state.current()).compile(predicate))
        }
        val where = conditions.joinToString(" AND ")
        return if (tenant == null) {
            "$key IN (SELECT aggregate_id FROM $source WHERE $where)"
        } else {
            val tenantColumn = quoteIdentifier(table.column(tenant.path).name)
            "($tenantColumn, $key) IN (SELECT tenant_id, aggregate_id FROM $source WHERE $where)"
        }
    }

    /**
     * The tenants the root of [admitted]'s filter restricts every row to: a conjunct that pins one tenant, or one of
     * several; null when none does. Every row the query reads is of one of them, so the records of no other are read.
     */
    private fun tenantsOf(admitted: AdmittedQuery<*>): List<String>? {
        val root = (admitted.query as? FilterCapable<*>)?.filter ?: return null
        val conjuncts = if (root is AndFilter) root.operands else listOf(root)
        return conjuncts.firstNotNullOfOrNull { conjunct ->
            when (conjunct) {
                is TenantIdFilter -> listOf(conjunct.value)
                is OrFilter -> conjunct.operands.takeIf { all -> all.all { it is TenantIdFilter } }
                    ?.map { (it as TenantIdFilter).value }
                else -> null
            }
        }
    }

    /** What an attribute is read from: its access column, single or multiple, or a built-in one's JSON value. */
    private class Attribute(val column: String, val element: TypeOf, val multiple: Boolean, val key: String)

    /** An access restriction's predicate on `_state`'s columns ([state]), every operand bound as a typed parameter. */
    private class Predicates(private val parameters: SqlParameters, private val state: ClickHouseTable) {
        @Suppress("CyclomaticComplexMethod") // One branch per node of the restriction's vocabulary.
        fun compile(filter: FilterExpression): String = when (filter) {
            MatchAllFilter -> "1"
            MatchNoneFilter -> "0"
            is AndFilter -> filter.operands.joinToString(" AND ", "(", ")") { compile(it) }
            is OrFilter -> filter.operands.joinToString(" OR ", "(", ")") { compile(it) }
            is NorFilter -> ClickHouseFilterCompiler.nor(filter.operands.map(::compile))
            is EqualFilter -> holds(attributeOf(filter.field), listOf(filter.value))
            is InFilter -> holds(attributeOf(filter.field), filter.values)
            is ContainsAllFilter -> holdsAll(attributeOf(filter.field), filter.values)
            is StartsWithFilter -> startsWith(attributeOf(filter.field), filter.value)
            is IsEmptyFilter -> attributeOf(filter.field).let { attribute ->
                if (attribute.multiple) "(${jsonType(attribute)} = 'Array' AND empty(${attribute.column}))" else "0"
            }
            is IsNullFilter -> isNull(attributeOf(filter.field), true)
            is IsNotNullFilter -> isNull(attributeOf(filter.field), false)
            else -> throw QueryViolation.StorageUnsupported("an access restriction's [${filter.operator}]").rejection()
        }

        /** Whether [attribute] holds one of [values], an element of it for a multiple one; of its type only. */
        private fun holds(attribute: Attribute, values: List<Any?>): String {
            val bound = operands(attribute, values).ifEmpty { return "0" }
            return if (attribute.multiple) {
                "hasAny(${attribute.column}, [${bound.joinToString(", ")}])"
            } else {
                "${attribute.column} IN (${bound.joinToString(", ")})"
            }
        }

        /** Whether [attribute] holds every one of [values]: a single one only if they are one value. */
        private fun holdsAll(attribute: Attribute, values: List<Any?>): String {
            val bound = operands(attribute, values)
            return when {
                bound.size < values.size -> "0"
                bound.isEmpty() -> "1"
                attribute.multiple -> "hasAll(${attribute.column}, [${bound.joinToString(", ")}])"
                else -> bound.joinToString(" AND ", "(", ")") { "${attribute.column} = $it" }
            }
        }

        private fun startsWith(attribute: Attribute, prefix: String): String {
            if (attribute.element != TypeOf.STRING) {
                return "0"
            }
            val bound = parameters.bind(prefix, STRING)
            return if (attribute.multiple) {
                "arrayExists(x -> startsWith(x, $bound), ${attribute.column})"
            } else {
                "startsWith(${attribute.column}, $bound)"
            }
        }

        private fun isNull(attribute: Attribute, none: Boolean): String = if (attribute.multiple) {
            "${jsonType(attribute)} ${if (none) "=" else "!="} 'Null'"
        } else {
            "${attribute.column} ${if (none) "IS NULL" else "IS NOT NULL"}"
        }

        /** [values] of [attribute]'s type, bound; one of another type holds of no value of it. */
        private fun operands(attribute: Attribute, values: List<Any?>): List<String> = values
            .filter { TypeOf.of(it) == attribute.element }
            .map { parameters.bind(it.toString(), attribute.element.parameter) }

        private fun jsonType(attribute: Attribute): String = "JSONType(access, ${attribute.key})"

        private fun attributeOf(field: QueryField<*>): Attribute {
            if (field.path == AccessValues.OWNER || field.path == AccessValues.SPACE) {
                val key = parameters.bind(field.path, STRING)
                return Attribute("JSONExtract(access, $key, 'Nullable(String)')", TypeOf.STRING, false, key)
            }
            val name = AccessColumns.PREFIX + field.path
            val column = state.columns[name] ?: throw QueryViolation.StorageUnsupported(
                "the access attribute [${field.path}], which ${state.qualifiedName} has no column $name of yet",
            ).rejection()
            val multiple = column.type.startsWith(ARRAY)
            val element = if (multiple) column.type.removePrefix(ARRAY).removeSuffix(")") else column.baseType
            return Attribute(
                quoteIdentifier(name),
                TypeOf.ofColumn(element, column.type),
                multiple,
                parameters.bind(field.path, STRING),
            )
        }
    }

    /** The types of an attribute's values: the parameter type of an operand of it. */
    private enum class TypeOf(val parameter: String) {
        STRING("String"),
        INTEGER("Int64"),
        BOOLEAN("Bool"),
        ;

        companion object {
            fun of(value: Any?): TypeOf = when (value) {
                is String -> STRING
                is Long -> INTEGER
                is Boolean -> BOOLEAN
                else -> throw QueryViolation.StorageUnsupported("an access restriction's operand [$value]").rejection()
            }

            /** The type of an access column's [element]s, of the column's [type]. */
            fun ofColumn(element: String, type: String): TypeOf = entries.firstOrNull { it.parameter == element }
                ?: throw QueryViolation.StorageUnsupported("an access column of type [$type]").rejection()
        }
    }

    private companion object {
        const val STRING = "String"
        const val ARRAY = "Array("
    }
}
