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
import me.ahoo.wow.clickhouse.sink.ClickHouseSinkSchema
import me.ahoo.wow.query.admission.AdmittedQuery
import me.ahoo.wow.query.dataset.DatasetAccess
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasetScope
import me.ahoo.wow.query.schema.QueryViolation

/**
 * The access restriction of a dataset bound to an aggregate ([DatasetAccess]) on ClickHouse: one semi-join of the
 * dataset's rows on the current access records of their aggregate, which the Sink keeps in that aggregate's
 * `<ctx>_<agg>_state` in [database] (`wow.clickhouse.database`). That table holds one row per commit, replaced by
 * version, so after `FINAL` each aggregate's row is its newest commit's, whose `access` column holds the attributes
 * after it as JSON text ([me.ahoo.wow.spi.access.AccessValues]), empty when it recorded none:
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
 * - Each comparison reads its attribute's JSON value as the in-memory storage reads a field's values: a missing
 *   attribute and a `null` one hold no value, an array each of its elements, a scalar itself, so `EQ`, `IN`,
 *   `STARTS_WITH` and `CONTAINS_ALL` hold of a value or an element of the operand's type, `IS_EMPTY` of an empty
 *   array, and `IS_NULL` of an attribute holding no value.
 */
internal class ClickHouseDatasetAccess(private val dataset: QueryDataset, database: String) {
    private val bound = requireNotNull(dataset.scope.access) { "Dataset [${dataset.name}] is bound to no aggregate." }
    private val source =
        "${quoteIdentifier(database)}.${quoteIdentifier(ClickHouseSinkSchema.stateOf(bound.aggregate))} FINAL"

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
            add(Predicates(parameters).compile(predicate))
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

    /** An access restriction's predicate on the `access` JSON text, every operand bound as a typed parameter. */
    private class Predicates(private val parameters: SqlParameters) {
        @Suppress("CyclomaticComplexMethod") // One branch per node of the restriction's vocabulary.
        fun compile(filter: FilterExpression): String = when (filter) {
            MatchAllFilter -> "1"
            MatchNoneFilter -> "0"
            is AndFilter -> filter.operands.joinToString(" AND ", "(", ")") { compile(it) }
            is OrFilter -> filter.operands.joinToString(" OR ", "(", ")") { compile(it) }
            is NorFilter -> ClickHouseFilterCompiler.nor(filter.operands.map(::compile))
            is EqualFilter -> holds(filter.field, listOf(filter.value))
            is InFilter -> holds(filter.field, filter.values)
            is ContainsAllFilter -> filter.values.joinToString(" AND ", "(", ")") { holds(filter.field, listOf(it)) }
            is StartsWithFilter -> {
                val key = key(filter.field)
                val prefix = parameters.bind(filter.value, STRING)
                "((JSONType(access, $key) = 'String' AND startsWith(JSONExtractString(access, $key), $prefix)) OR " +
                    "(JSONType(access, $key) = 'Array' AND " +
                    "arrayExists(x -> startsWith(x, $prefix), JSONExtract(access, $key, 'Array(String)'))))"
            }
            is IsEmptyFilter -> {
                val key = key(filter.field)
                "(JSONType(access, $key) = 'Array' AND JSONLength(access, $key) = 0)"
            }
            // A missing attribute's type reads as Null, as an explicit null's does.
            is IsNullFilter -> "JSONType(access, ${key(filter.field)}) = 'Null'"
            is IsNotNullFilter -> "JSONType(access, ${key(filter.field)}) != 'Null'"
            else -> throw QueryViolation.StorageUnsupported("an access restriction's [${filter.operator}]").rejection()
        }

        /** Whether [field] holds one of [values], as a scalar or as an element of an array, by their types. */
        private fun holds(field: QueryField<*>, values: List<Any?>): String {
            val key = key(field)
            return values.groupBy { TypeOf.of(it) }.entries.joinToString(" OR ", "(", ")") { (type, operands) ->
                val bound = operands.map { parameters.bind(it.toString(), type.parameter) }
                val scalar = type.scalars.joinToString(", ", "JSONType(access, $key) IN (", ")") { "'$it'" }
                "($scalar AND ${type.extract("access", key)} IN (${bound.joinToString(", ")})) OR " +
                    "(JSONType(access, $key) = 'Array' AND " +
                    "hasAny(JSONExtract(access, $key, 'Array(${type.parameter})'), [${bound.joinToString(", ")}]))"
            }
        }

        private fun key(field: QueryField<*>): String = parameters.bind(field.path, STRING)
    }

    /** How a JSON value of an operand's type is read: its JSON types, its extraction and its parameter type. */
    private enum class TypeOf(val parameter: String, val scalars: List<String>) {
        STRING("String", listOf("String")),
        INTEGER("Int64", listOf("Int64", "UInt64")),
        BOOLEAN("Bool", listOf("Bool")),
        ;

        fun extract(json: String, key: String): String = when (this) {
            STRING -> "JSONExtractString($json, $key)"
            INTEGER -> "JSONExtractInt($json, $key)"
            BOOLEAN -> "JSONExtractBool($json, $key)"
        }

        companion object {
            fun of(value: Any?): TypeOf = when (value) {
                is String -> STRING
                is Long -> INTEGER
                is Boolean -> BOOLEAN
                else -> throw QueryViolation.StorageUnsupported("an access restriction's operand [$value]").rejection()
            }
        }
    }

    private companion object {
        const val STRING = "String"
    }
}
