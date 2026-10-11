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

import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.query.schema.AbsentValues
import me.ahoo.wow.query.schema.AggregationSupport
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.PagingSupport
import me.ahoo.wow.query.schema.QueryDataset
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QuerySchemaException
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryStorageFacts
import me.ahoo.wow.query.schema.QueryStorageType
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.StorageSupport
import me.ahoo.wow.query.schema.SupportMode
import reactor.core.publisher.Mono

/**
 * What a dataset's table proves about its declaration (design §4.2, §5.2). The declaration is the contract and the
 * table only confirms it: every declared field must be a root column of a supported type whose family matches the
 * declared types, a non-null declaration needs a non-`Nullable` column, and the scope and identity columns must not
 * be `Nullable`. `FINAL` needs an engine that merges rows. Any mismatch fails the compile, so the Catalog keeps the
 * previous schema and reports it; a column the declaration does not name is never exposed.
 */
class ClickHouseQuerySchemaAdapter(
    private val dataset: QueryDataset,
    private val storage: ClickHouseDatasetStorage,
    private val table: () -> Mono<ClickHouseTable>,
) : QueryStorageAdapter {
    override fun facts(logicalSchema: LogicalQuerySchema): Mono<QueryStorageFacts> = table()
        .map { facts(logicalSchema, it) }
        .onErrorMap({ it !is QuerySchemaException }) {
            QuerySchemaUnavailableException("Failed to resolve ClickHouse dataset [${dataset.namedAggregate}].", it)
        }

    private fun facts(logicalSchema: LogicalQuerySchema, table: ClickHouseTable): QueryStorageFacts {
        if (storage.deduplicate == ClickHouseDatasetStorage.Deduplicate.FINAL && !table.supportsFinal) {
            invalid("deduplicates with FINAL, but table [${table.name}] has engine [${table.engine}]")
        }
        val keys = dataset.scope.fields + listOfNotNull(dataset.identity)
        val bindings = logicalSchema.values.filterKeys { it.segments.isNotEmpty() }.mapValues { (path, value) ->
            val name = (path.segments.singleOrNull() as? QueryPathSegment.Property)?.name
                ?: invalid("declares a nested field; a dataset declares root columns only")
            val column = table.columns[name] ?: invalid("declares [$name], which table [${table.name}] has no column for")
            column.requireDeclared(value)
            if (column.nullable && QueryField(name) in keys) invalid("keys on the Nullable column [$name]")
            val native = QueryFieldBindingTemplate(path, setOf(QueryStorageType(column.family.name)))
            QueryValueBindings(column.capabilities().associateWith { native }, path, path)
        }
        return QueryStorageFacts(
            bindings = bindings,
            approximateMetrics = setOf("PERCENTILE"),
            storage = STORAGE_SUPPORT,
        )
    }

    private fun ClickHouseColumn.requireDeclared(value: QueryValueSchema) {
        if (family == ClickHouseTypeFamily.UNSUPPORTED) invalid("declares [$name] of the unsupported type [$type]")
        if (value.kind != QueryValueKind.SCALAR) invalid("declares [$name] as ${value.kind}; columns are scalar")
        val expected = when (family) {
            ClickHouseTypeFamily.STRING, ClickHouseTypeFamily.ENUM -> QueryValueType.STRING
            ClickHouseTypeFamily.INTEGER -> QueryValueType.INTEGER
            ClickHouseTypeFamily.FLOAT, ClickHouseTypeFamily.DECIMAL -> QueryValueType.DECIMAL
            ClickHouseTypeFamily.BOOLEAN -> QueryValueType.BOOLEAN
            ClickHouseTypeFamily.DATE, ClickHouseTypeFamily.DATE_TIME -> QueryValueType.STRING
            ClickHouseTypeFamily.UNSUPPORTED -> error("unreachable")
        }
        if (value.valueTypes != setOf(expected)) {
            invalid("declares [$name] as ${value.valueTypes}, but column type [$type] holds $expected")
        }
        if (temporal && value.semanticType != Temporal.Date) {
            invalid("must declare the date column [$name] with the TEMPORAL_DATE semantic")
        }
        if (nullable && !value.nullable) invalid("declares [$name] non-null, but its column is Nullable")
    }

    private fun invalid(message: String): Nothing =
        throw QuerySchemaValidationException("Dataset [${dataset.namedAggregate}] $message.")

    companion object {
        /**
         * Every paging and aggregation feature runs natively except dense fill, which the core computes so it
         * shares one implementation with every storage. A `NULL` is a table's only missing value.
         */
        val STORAGE_SUPPORT = StorageSupport(
            paging = PagingSupport(),
            aggregation = AggregationSupport(denseFill = SupportMode.RESIDUAL),
            parallelArraySort = SupportMode.NONE,
            absentValues = AbsentValues.AS_MISSING,
            arrayEquality = SupportMode.NONE,
        )

        /**
         * A non-`Nullable` column stores a default for a missing value, so only a `Nullable` one proves presence;
         * only a non-`Nullable` one sorts a cursor. A date or epoch column groups by time (the Catalog keeps
         * temporal aggregation for fields whose declaration encodes an instant).
         */
        internal fun ClickHouseColumn.capabilities(): Set<QueryCapability> = buildSet {
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
                    add(QueryCapability.AGGREGATE_TEMPORAL)
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
    }
}
