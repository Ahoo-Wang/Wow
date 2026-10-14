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

import com.clickhouse.client.api.Client
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICountQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.BackendPage
import me.ahoo.wow.query.CursorPosition
import me.ahoo.wow.query.CursorPositionCodec
import me.ahoo.wow.query.GroupWindow
import me.ahoo.wow.query.PageWindow
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.QueryExecutionException
import me.ahoo.wow.query.dataset.AbstractDatasetQueryBackendFactory
import me.ahoo.wow.query.dataset.DatasetQueryBackend
import me.ahoo.wow.query.dataset.QueryDataset
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * The ClickHouse dataset backend (work package 6.4a, decisions D142, D145): binds each declared dataset to the table
 * or view its storage binding names ([ClickHouseDatasetStorage]), with the facts [ClickHouseQuerySchemaAdapter] reads
 * from `system.columns`. A host builds one over the client-v2 [client] the Sink writes with.
 */
@ExperimentalWowApi
class ClickHouseDatasetQueryBackendFactory @JvmOverloads constructor(
    private val client: Client,
    private val options: ClickHouseDatasetOptions = ClickHouseDatasetOptions(),
) : AbstractDatasetQueryBackendFactory() {
    private val executor = ClickHouseExecutor(client, options.settings)

    override fun createBinding(dataset: QueryDataset): QueryBackendBinding<DatasetQueryBackend> {
        val storage = ClickHouseDatasetStorage.of(dataset, options.database)
        val metadata = ClickHouseTableMetadata(options.metadataTtl) { executor.table(storage.database, storage.table) }
        return QueryBackendBinding(
            ClickHouseDatasetQueryBackend(dataset, storage, executor, metadata),
            ClickHouseQuerySchemaAdapter(dataset, storage, metadata),
        )
    }
}

/**
 * The four query primitives over one dataset's table, compiled to parameterized SQL ([ClickHouseFilterCompiler],
 * [ClickHouseAggregationCompiler]); a dataset that deduplicates reads `FINAL`. Every statement runs under the
 * query's timeout as `max_execution_time` (decision D150), and a count stops after `limit + 1` matches.
 *
 * A record carries the dataset's declared fields at its root, decimals exact (decision D122) and dates as ISO-8601
 * instants. A keyset page orders by the cursor sort, whose fields are non-`Nullable` scalar columns (the only ones
 * granted cursor sort), and reads each row's position from extra columns: a date as epoch milliseconds, a decimal as
 * its exact text, anything else as its JSON value.
 */
@ExperimentalWowApi
internal class ClickHouseDatasetQueryBackend internal constructor(
    val dataset: QueryDataset,
    private val storage: ClickHouseDatasetStorage,
    private val executor: ClickHouseExecutor,
    private val metadata: ClickHouseTableMetadata,
) : DatasetQueryBackend {
    override val aggregateType: AggregateType = dataset.name
    override val cursorPositions: CursorPositionCodec = CursorPositionCodec.JSON
    private val fields: List<String> = dataset.declaration.fields.keys.map(QueryField::path)

    override fun stream(query: AdmittedQuery<IListQuery>): Flow<ObjectNode> = flow {
        val select = Select(query, metadata.table())
        emitAll(executor.rows(select.records(query.query.limit.takeIf { it > 0 }, 0), query.timeout))
    }

    override suspend fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): BackendPage {
        val table = metadata.table()
        val select = Select(query, table)
        return when (window) {
            is PageWindow.Offset -> coroutineScope {
                val total = if (window.withTotal) {
                    async { count(query, query.query.filter, window.totalLimit, table) }
                } else {
                    null
                }
                val rows = executor.all(select.records(window.limit, window.offset), query.timeout)
                BackendPage(rows, total?.await())
            }
            is PageWindow.Keyset -> {
                val rows = executor.all(select.keyset(window), query.timeout)
                BackendPage(rows.map(select::record), positions = rows.map(select::position))
            }
        }
    }

    override suspend fun count(query: AdmittedQuery<ICountQuery>): Long =
        count(query, query.query.filter, query.query.limit, metadata.table())

    override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flow<ObjectNode> = flow {
        val table = metadata.table()
        emitAll(
            executor.rows(ClickHouseAggregationCompiler(query, table, source(table)).compile(window), query.timeout)
        )
    }

    /** The matches of [filter], counting no more than `limit + 1` of them when [limit] is set (decision D150). */
    private suspend fun count(
        admitted: AdmittedQuery<*>,
        filter: FilterExpression,
        limit: Int?,
        table: ClickHouseTable
    ): Long {
        val parameters = SqlParameters()
        val where = ClickHouseFilterCompiler(admitted, table, parameters).compile(filter)
        val matches = "SELECT 1 FROM ${source(table)} WHERE $where"
        val sql = if (limit == null) {
            "SELECT count() AS total FROM ${source(table)} WHERE $where"
        } else {
            "SELECT count() AS total FROM ($matches LIMIT ${limit.toLong() + 1})"
        }
        val row = executor.all(parameters.statement(sql), admitted.timeout).single()
        return row.get("total").longValue()
    }

    private fun source(table: ClickHouseTable): String = when (storage.deduplicate) {
        ClickHouseDatasetStorage.Deduplicate.FINAL -> "${table.qualifiedName} FINAL"
        ClickHouseDatasetStorage.Deduplicate.NONE -> table.qualifiedName
    }

    override fun toString(): String = "ClickHouseDatasetQueryBackend(${dataset.name} on ${storage.database}.${storage.table})"

    /** One record query: its columns, filter and sort, and for a keyset page the position columns. */
    private inner class Select(private val admitted: AdmittedQuery<Queryable<*>>, private val table: ClickHouseTable) {
        private val query = admitted.query
        private val parameters = SqlParameters()
        private val filters = ClickHouseFilterCompiler(admitted, table, parameters)
        private val columns = columns(query.projection)
        private val sort: List<Pair<TypedColumn, Sort.Direction>> = query.sort.map {
            filters.columnOf(it.field) to it.direction
        }

        fun records(limit: Int?, offset: Int): ClickHouseStatement =
            statement(filters.compile(query.filter), limit, offset)

        fun keyset(window: PageWindow.Keyset): ClickHouseStatement {
            val after = window.after?.let { position ->
                if (position.values.size != sort.size) throw QueryExecutionException("Cursor does not match its sort.")
                after(position.values)
            }
            val where = listOfNotNull(filters.compile(query.filter), after).joinToString(" AND ") { "($it)" }
            return statement(where, window.limit, 0, positions = true)
        }

        /** The row as the dataset returns it: the projected columns, without the position columns. */
        fun record(row: ObjectNode): ObjectNode {
            val record = row.deepCopy()
            sort.indices.forEach { record.remove(positionAlias(it)) }
            return record
        }

        fun position(row: ObjectNode): CursorPosition =
            CursorPosition(sort.indices.map { index -> row.get(positionAlias(index)).positionValue() })

        private fun statement(where: String, limit: Int?, offset: Int, positions: Boolean = false): ClickHouseStatement {
            val selected = columns.map(::quoteIdentifier) + if (positions) {
                sort.mapIndexed { index, (column, _) -> "${positionExpression(column)} AS ${positionAlias(index)}" }
            } else {
                emptyList()
            }
            val sql = buildString {
                append("SELECT ").append(selected.joinToString(", "))
                append(" FROM ").append(source(table))
                append(" WHERE ").append(where)
                if (sort.isNotEmpty()) {
                    val order = sort.map { (column, direction) -> ordered(quoteIdentifier(column.name), direction) }
                    append(" ORDER BY ").append(order.joinToString(", "))
                }
                limit?.let { append(" LIMIT ").append(it) }
                if (offset > 0) append(" OFFSET ").append(offset)
            }
            return parameters.statement(sql)
        }

        /** Rows strictly after [values] in the sort: the first differing field decides, as a tuple comparison would. */
        private fun after(values: List<Any?>): String = sort.indices.joinToString(" OR ") { index ->
            buildList {
                repeat(index) { equal ->
                    add("${quoteIdentifier(sort[equal].first.name)} = ${bound(equal, values[equal])}")
                }
                val (column, direction) = sort[index]
                val operator = if (direction == Sort.Direction.ASC) ">" else "<"
                add("${quoteIdentifier(column.name)} $operator ${bound(index, values[index])}")
            }.joinToString(" AND ", "(", ")")
        }

        private fun bound(index: Int, value: Any?): String {
            if (value == null) throw QueryExecutionException("Cursor position holds a null value.")
            return filters.bind(sort[index].first, value)
        }

        private fun columns(projection: Projection): List<String> = when {
            projection.include.isNotEmpty() -> projection.include.map(admitted::physicalPath)
            projection.exclude.isNotEmpty() -> fields - projection.exclude.map(admitted::physicalPath).toSet()
            else -> fields
        }
    }

    private companion object {
        fun positionAlias(index: Int): String = "__position$index"

        fun positionExpression(column: TypedColumn): String {
            val quoted = quoteIdentifier(column.name)
            return when (column.family) {
                ClickHouseTypeFamily.DATE, ClickHouseTypeFamily.DATE_TIME ->
                    "toUnixTimestamp64Milli(toDateTime64($quoted, 3, 'UTC'))"
                // Exact, as the JSON codec keeps no decimals: bound back as the column's Decimal type.
                ClickHouseTypeFamily.DECIMAL -> "toString($quoted)"
                else -> quoted
            }
        }

        /** A position value as the JSON codec keeps it: strings, booleans, longs and finite doubles. */
        fun JsonNode?.positionValue(): Any? = when {
            this == null || isNull -> null
            isString -> stringValue()
            isBoolean -> booleanValue()
            isIntegralNumber && canConvertToLong() -> longValue()
            // A UInt64 beyond a long: its exact text, bound back as the column's type.
            isIntegralNumber -> bigIntegerValue().toString()
            isNumber -> doubleValue()
            else -> throw QueryExecutionException("Cursor position must be a scalar.")
        }
    }
}
