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

import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.query.schema.AbsentValues
import me.ahoo.wow.query.schema.AggregationSupport
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.PagingSupport
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QueryPathTemplate
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryStorageFacts
import me.ahoo.wow.query.schema.QueryStorageType
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.schema.StorageSupport
import me.ahoo.wow.query.schema.SupportMode
import reactor.core.publisher.Mono

/**
 * What a table's columns prove about a dataset's declared fields. Only declared root fields bind; a declared field
 * with no column, or an unsupported column type, fails the compile, so the published schema never names a column
 * the table lacks.
 */
class ClickHouseQuerySchemaAdapter(private val table: () -> Mono<ClickHouseTable>) : QueryStorageAdapter {
    override fun facts(logicalSchema: LogicalQuerySchema): Mono<QueryStorageFacts> = table().map { table ->
        facts(logicalSchema, table)
    }.onErrorMap({ it !is QuerySchemaUnavailableException }) {
        QuerySchemaUnavailableException("Failed to resolve ClickHouse query schema: ${it.message}", it)
    }

    companion object {
        val STORAGE_SUPPORT = StorageSupport(
            // S0: no keyset cursor (a dataset declares no identity yet); HAVING and dense fill are left to the core.
            paging = PagingSupport(keyset = SupportMode.NONE),
            aggregation = AggregationSupport(having = SupportMode.RESIDUAL, denseFill = SupportMode.RESIDUAL),
            parallelArraySort = SupportMode.NONE,
            arrayEquality = SupportMode.NONE,
            // A NULL in a Nullable column is the table's only notion of a missing value.
            absentValues = AbsentValues.AS_MISSING,
        )

        fun facts(logicalSchema: LogicalQuerySchema, table: ClickHouseTable): QueryStorageFacts {
            val bindings = logicalSchema.values.keys
                .filter { it.segments.size == 1 && it.segments.single() is QueryPathSegment.Property }
                .associateWith { path ->
                    val name = (path.segments.single() as QueryPathSegment.Property).name
                    val column = table.columns[name]
                        ?: throw QuerySchemaUnavailableException("Declared field [$name] has no column in [${table.name}].")
                    if (column.family == ClickHouseTypeFamily.UNSUPPORTED) {
                        throw QuerySchemaUnavailableException("Column [$name] has unsupported type [${column.type}].")
                    }
                    val native = QueryFieldBindingTemplate(path, setOf(QueryStorageType(column.family.name)))
                    QueryValueBindings(
                        bindings = column.capabilities().associateWith { native },
                        projectionPath = path,
                        responsePath = path,
                    )
                }
            return QueryStorageFacts(
                bindings = bindings,
                approximateMetrics = setOf("PERCENTILE"),
                storage = STORAGE_SUPPORT,
            )
        }

        private fun ClickHouseColumn.capabilities(): Set<QueryCapability> = buildSet {
            add(QueryCapability.EXACT_MATCH)
            add(QueryCapability.SORT)
            if (nullable) add(QueryCapability.PRESENCE) else add(QueryCapability.CURSOR_SORT)
            when (family) {
                ClickHouseTypeFamily.STRING, ClickHouseTypeFamily.ENUM -> {
                    add(QueryCapability.LITERAL_MATCH)
                    add(QueryCapability.RANGE)
                    add(QueryCapability.AGGREGATE_TERMS)
                }
                ClickHouseTypeFamily.INTEGER -> {
                    add(QueryCapability.RANGE)
                    add(QueryCapability.AGGREGATE_TERMS)
                    add(QueryCapability.AGGREGATE_NUMERIC)
                }
                ClickHouseTypeFamily.FLOAT, ClickHouseTypeFamily.DECIMAL -> {
                    add(QueryCapability.RANGE)
                    add(QueryCapability.AGGREGATE_NUMERIC)
                }
                ClickHouseTypeFamily.BOOLEAN -> add(QueryCapability.AGGREGATE_TERMS)
                ClickHouseTypeFamily.DATE, ClickHouseTypeFamily.DATE_TIME -> {
                    add(QueryCapability.RANGE)
                    add(QueryCapability.AGGREGATE_TEMPORAL)
                }
                ClickHouseTypeFamily.UNSUPPORTED -> Unit
            }
        }

        /** The root path of one column. */
        fun rootPath(name: String): QueryPathTemplate = QueryPathTemplate(listOf(QueryPathSegment.Property(name)))
    }
}
