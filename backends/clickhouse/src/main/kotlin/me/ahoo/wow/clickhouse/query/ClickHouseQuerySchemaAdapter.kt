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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.schema.AbsentValues
import me.ahoo.wow.query.schema.AggregationSupport
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QuerySchemaException
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryStorageFacts
import me.ahoo.wow.query.schema.QueryStorageType
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.schema.QueryValueKind
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.StorageSupport
import me.ahoo.wow.query.schema.SupportMode
import me.ahoo.wow.query.schema.Temporal
import java.time.Duration

/** A table's metadata, read from `system.tables` and `system.columns` at most once per [ttl]. */
internal class ClickHouseTableMetadata(
    private val executor: ClickHouseExecutor,
    private val storage: ClickHouseDatasetStorage,
    private val ttl: Duration,
    private val clock: () -> Long = System::nanoTime,
) {
    private val mutex = Mutex()

    @Volatile
    private var cached: Pair<ClickHouseTable, Long>? = null

    /** The table, read again once [ttl] has passed since the last read. */
    suspend fun table(): ClickHouseTable {
        cached?.let { (table, readAt) -> if (clock() - readAt < ttl.toNanos()) return table }
        return refresh()
    }

    /** The table, read now. */
    suspend fun refresh(): ClickHouseTable = mutex.withLock {
        executor.table(storage.database, storage.table).also { cached = it to clock() }
    }
}

/**
 * What a dataset's table proves about its declaration (decision D75; the 9.x dataset platform design §4.2, §5.2),
 * from `system.columns`. The declaration is the contract and the table only confirms it: every declared field must be
 * a root column of a supported type whose family matches the declared types, a non-null declaration needs a
 * non-`Nullable` column, and the scope and identity columns must be neither `Nullable` nor arrays (an ABAC tag column
 * may be an array). `FINAL` needs an engine that merges rows. Any mismatch fails the compile, so the Catalog keeps
 * the previous schema and reports it; a column the declaration does not name is never exposed.
 */
internal class ClickHouseQuerySchemaAdapter(
    private val dataset: QueryDataset,
    private val storage: ClickHouseDatasetStorage,
    private val metadata: ClickHouseTableMetadata,
) : QueryStorageAdapter {
    override suspend fun facts(logicalSchema: LogicalQuerySchema): QueryStorageFacts =
        resolved { facts(logicalSchema, metadata.table()) }

    override suspend fun refresh(logicalSchema: LogicalQuerySchema): QueryStorageFacts =
        resolved { facts(logicalSchema, metadata.refresh()) }

    @Suppress("TooGenericExceptionCaught") // Any failure to read the table leaves the dataset unavailable.
    private suspend fun resolved(block: suspend () -> QueryStorageFacts): QueryStorageFacts = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: QuerySchemaException) {
        throw error
    } catch (error: Exception) {
        throw QuerySchemaUnavailableException("Failed to resolve ClickHouse dataset [${dataset.name}].", error)
    }

    @Suppress("ThrowsCount") // Each check states one way the table can contradict the declaration.
    private fun facts(logicalSchema: LogicalQuerySchema, table: ClickHouseTable): QueryStorageFacts {
        if (storage.deduplicate == ClickHouseDatasetStorage.Deduplicate.FINAL && !table.supportsFinal) {
            throw invalid("deduplicates with FINAL, but table [${table.name}] has engine [${table.engine}]")
        }
        val keys = dataset.scope.fields + listOfNotNull(dataset.identity)
        val records = (dataset.scope.fields - dataset.scope.tags.values.toSet()) + listOfNotNull(dataset.identity)
        // An array column's elements are part of the column: their path ends in an item segment.
        val paths = logicalSchema.values.filterKeys { it.segments.isNotEmpty() && it.segments.last() != ITEM }
        if (paths.keys.any { it.segments.size > 1 }) throw invalid("declares a nested field; a dataset declares root columns only")
        val bindings = paths.mapValues { (path, value) ->
            val name = (path.segments.single() as QueryPathSegment.Property).name
            val column = table.columns[name]
                ?: throw invalid("declares [$name], which table [${table.name}] has no column for")
            column.requireDeclared(value)
            if (column.nullable && QueryField(name) in keys) throw invalid("keys on the Nullable column [$name]")
            if (column.array && QueryField(name) in records) throw invalid("keys on the array column [$name]")
            val native = QueryFieldBindingTemplate(path, setOf(QueryStorageType(column.family.name)))
            QueryValueBindings(column.capabilities().associateWith { native }, path, path)
        }
        return QueryStorageFacts(
            bindings = bindings,
            approximateMetrics = setOf("PERCENTILE"),
            storage = STORAGE_SUPPORT,
        )
    }

    @Suppress("ThrowsCount")
    private fun TypedColumn.requireDeclared(value: QueryValueSchema) {
        if (family == ClickHouseTypeFamily.UNSUPPORTED) throw invalid("declares [$name] of the unsupported type [$type]")
        if (array) return requireDeclaredArray(value)
        if (value.kind != QueryValueKind.SCALAR) throw invalid("declares [$name] as ${value.kind}; column [$type] is scalar")
        val expected = when (family) {
            ClickHouseTypeFamily.STRING, ClickHouseTypeFamily.ENUM -> QueryValueType.STRING
            ClickHouseTypeFamily.INTEGER -> QueryValueType.INTEGER
            ClickHouseTypeFamily.FLOAT, ClickHouseTypeFamily.DECIMAL -> QueryValueType.DECIMAL
            ClickHouseTypeFamily.BOOLEAN -> QueryValueType.BOOLEAN
            // Dates; arrays and unsupported types were handled above.
            else -> QueryValueType.STRING
        }
        if (value.valueTypes != setOf(expected)) {
            throw invalid("declares [$name] as ${value.valueTypes}, but column type [$type] holds $expected")
        }
        if (temporal && value.semanticType != Temporal.Date) {
            throw invalid("must declare the date column [$name] with the TEMPORAL_DATE semantic")
        }
        if (nullable && !value.nullable) throw invalid("declares [$name] non-null, but its column is Nullable")
    }

    private fun TypedColumn.requireDeclaredArray(value: QueryValueSchema) {
        val strings = value.items?.takeIf { it.kind == QueryValueKind.SCALAR }?.valueTypes == setOf(QueryValueType.STRING)
        if (value.kind != QueryValueKind.ARRAY || !strings) {
            throw invalid("must declare the column [$name] of type [$type] as an array of strings")
        }
    }

    private fun invalid(message: String): QuerySchemaValidationException =
        QuerySchemaValidationException("Dataset [${dataset.name}] $message.")

    companion object {
        private val ITEM = QueryPathSegment.Item

        /**
         * Paging, HAVING, top-N, percentiles, distinct counts and FIRST / LAST run natively; dense fill and every
         * window (TOTAL, RANK, RUNNING, PREVIOUS) are the core's, so they share one implementation with every
         * storage. A `NULL` is a table's only missing value, and a dataset's rows have no elements to expand.
         */
        val STORAGE_SUPPORT = StorageSupport(
            aggregation = AggregationSupport(
                denseFill = SupportMode.RESIDUAL,
                rootGroups = SupportMode.NONE,
            ),
            parallelArraySort = SupportMode.NONE,
            absentValues = AbsentValues.AS_MISSING,
            arrayEquality = SupportMode.NONE,
        )

        /**
         * A non-`Nullable` column stores a default for a missing value, so only a `Nullable` one proves presence (and
         * an array, whose missing value is an empty one); only a non-`Nullable` scalar one sorts a cursor. A date or
         * integer column groups by time (the Catalog keeps temporal aggregation for fields whose declaration encodes
         * an instant).
         */
        internal fun TypedColumn.capabilities(): Set<QueryCapability> = buildSet {
            add(QueryCapability.EXACT_MATCH)
            // An array matches by element and is empty or not; it never sorts.
            if (array) {
                add(QueryCapability.PRESENCE)
                return@buildSet
            }
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
                ClickHouseTypeFamily.STRING_ARRAY, ClickHouseTypeFamily.UNSUPPORTED -> Unit
            }
        }
    }
}
