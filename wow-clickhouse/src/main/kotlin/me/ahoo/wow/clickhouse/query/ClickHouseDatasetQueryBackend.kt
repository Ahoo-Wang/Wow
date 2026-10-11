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

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
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
import me.ahoo.wow.query.schema.QueryDataset
import me.ahoo.wow.serialization.JsonSerializer
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.Duration

/** Creates each dataset's ClickHouse backend and storage adapter over one [executor]. */
class ClickHouseDatasetQueryBackendFactory(
    private val executor: ClickHouseExecutor,
    private val options: ClickHouseDatasetOptions = ClickHouseDatasetOptions(),
) : AbstractDatasetQueryBackendFactory() {
    override fun createBinding(dataset: QueryDataset): QueryBackendBinding<DatasetQueryBackend> {
        val storage = ClickHouseDatasetStorage.of(dataset, options.defaultDatabase)
        val table = executor.table(storage.database, storage.table).cache(
            { options.metadataTtl },
            { Duration.ZERO },
            { Duration.ZERO },
        )
        return QueryBackendBinding(
            ClickHouseDatasetQueryBackend(dataset, storage, executor, options) { table },
            ClickHouseQuerySchemaAdapter(dataset, storage) { table },
        )
    }
}

/**
 * The four query primitives over one dataset's table. Every statement runs under the limits of the query's entry
 * ([ClickHouseDatasetOptions.limits]) and reads `FINAL` when the dataset deduplicates.
 *
 * A record carries the dataset's declared fields only. A keyset page orders by the cursor sort, whose fields are
 * non-`Nullable` columns (the only ones granted cursor sort), and reads each row's position from extra columns: a
 * date as epoch milliseconds, a decimal as its exact text, anything else as its JSON value.
 */
class ClickHouseDatasetQueryBackend(
    val dataset: QueryDataset,
    private val storage: ClickHouseDatasetStorage,
    private val executor: ClickHouseExecutor,
    private val options: ClickHouseDatasetOptions,
    private val table: () -> Mono<ClickHouseTable>,
) : DatasetQueryBackend {
    override val namedAggregate: NamedAggregate = dataset.namedAggregate
    override val cursorPositions: CursorPositionCodec = CursorPositionCodec.JSON
    private val fields: List<String> = dataset.declaration.fields.keys.map(QueryField::path)

    override fun stream(query: AdmittedQuery<IListQuery>): Flux<ObjectNode> = table().flatMapMany { table ->
        val select = Select(query, table)
        executor.rows(select.records(query.query.limit.takeIf { it > 0 }, 0), settings(query))
    }

    override fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): Mono<BackendPage> =
        table().flatMap { table ->
            val select = Select(query, table)
            when (window) {
                is PageWindow.Offset -> {
                    val rows = executor.rows(select.records(window.limit, window.offset), settings(query)).collectList()
                    if (window.withTotal) {
                        Mono.zip(rows, count(query.query.filter, query, table)).map { BackendPage(it.t1, it.t2) }
                    } else {
                        rows.map(::BackendPage)
                    }
                }
                is PageWindow.Keyset -> executor.rows(select.keyset(window), settings(query)).collectList()
                    .map { rows ->
                        val positions = rows.map(select::position)
                        BackendPage(rows.map(select::record), positions = positions)
                    }
            }
        }

    override fun count(query: AdmittedQuery<FilterExpression>): Mono<Long> =
        table().flatMap { count(query.query, query, it) }

    override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flux<ObjectNode> =
        table().flatMapMany { table ->
            executor.rows(ClickHouseAggregationCompiler(query, table, source(table)).compile(window), settings(query))
        }

    private fun count(filter: FilterExpression, admitted: AdmittedQuery<*>, table: ClickHouseTable): Mono<Long> {
        val parameters = SqlParameters()
        val where = ClickHouseFilterCompiler(admitted, table, parameters).compile(filter)
        val statement = parameters.statement("SELECT count() AS total FROM ${source(table)} WHERE $where")
        return executor.rows(statement, settings(admitted)).single().map { it.get("total").asLong() }
    }

    private fun source(table: ClickHouseTable): String = when (storage.deduplicate) {
        ClickHouseDatasetStorage.Deduplicate.FINAL -> "${table.qualifiedName} FINAL"
        ClickHouseDatasetStorage.Deduplicate.NONE -> table.qualifiedName
    }

    private fun settings(admitted: AdmittedQuery<*>): Map<String, String> = options.limits(admitted.entry).settings

    /** One record query: its columns, filter and sort, and for a keyset page the position columns. */
    private inner class Select(private val admitted: AdmittedQuery<*>, private val table: ClickHouseTable) {
        private val query = admitted.query as Queryable<*>
        private val parameters = SqlParameters()
        private val filters = ClickHouseFilterCompiler(admitted, table, parameters)
        private val columns = columns(query.projection)
        private val sort: List<Pair<ClickHouseColumn, Sort.Direction>> = query.sort.map {
            filters.columnOf(it.field) to it.direction
        }

        fun records(limit: Int?, offset: Int): ClickHouseStatement = statement(
            filters.compile(query.filter),
            limit,
            offset
        )

        fun keyset(window: PageWindow.Keyset): ClickHouseStatement {
            val after = window.after?.let { position ->
                if (position.values.size != sort.size) throw QueryExecutionException("Cursor does not match its sort.")
                after(position.values)
            }
            val where = listOfNotNull(filters.compile(query.filter), after).joinToString(" AND ") { "($it)" }
            return statement(where, window.limit, 0, positions = true)
        }

        /** The row as the dataset returns it: the projected columns, without the position columns. */
        fun record(row: ObjectNode): ObjectNode = row.deepCopy().apply {
            sort.indices.forEach {
                remove(
                    positionAlias(it)
                )
            }
        }

        fun position(row: ObjectNode): CursorPosition = CursorPosition(
            sort.indices.map { index -> row.get(positionAlias(index)).positionValue() },
        )

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
                    append(" ORDER BY ").append(
                        sort.joinToString(", ") { (column, direction) ->
                            "${quoteIdentifier(column.name)} ${if (direction == Sort.Direction.ASC) "ASC" else "DESC"}"
                        },
                    )
                }
                limit?.let { append(" LIMIT ").append(it) }
                if (offset > 0) append(" OFFSET ").append(offset)
            }
            return parameters.statement(sql)
        }

        /** Rows strictly after [values] in the sort: the first differing field decides, as a tuple comparison would. */
        private fun after(values: List<Any?>): String = sort.indices.joinToString(" OR ") { index ->
            buildList {
                repeat(
                    index
                ) { equal -> add("${quoteIdentifier(sort[equal].first.name)} = ${bound(equal, values[equal])}") }
                val (column, direction) = sort[index]
                val operator = if (direction == Sort.Direction.ASC) ">" else "<"
                add("${quoteIdentifier(column.name)} $operator ${bound(index, values[index])}")
            }.joinToString(" AND ", "(", ")")
        }

        private fun bound(index: Int, value: Any?): String {
            val node = JsonSerializer.valueToTree<JsonNode>(value)
            if (node.isNull) throw QueryExecutionException("Cursor position holds a null value.")
            return filters.bind(sort[index].first, node)
        }

        private fun columns(projection: Projection): List<String> = when {
            projection.include.isNotEmpty() -> projection.include.map(admitted::physicalPath)
            projection.exclude.isNotEmpty() -> fields - projection.exclude.map(admitted::physicalPath).toSet()
            else -> fields
        }
    }

    private companion object {
        fun positionAlias(index: Int): String = "__position$index"

        fun positionExpression(column: ClickHouseColumn): String {
            val quoted = quoteIdentifier(column.name)
            return when (column.family) {
                ClickHouseTypeFamily.DATE, ClickHouseTypeFamily.DATE_TIME ->
                    "toUnixTimestamp64Milli(toDateTime64($quoted, 3, 'UTC'))"
                ClickHouseTypeFamily.DECIMAL -> "toString($quoted)"
                else -> quoted
            }
        }

        /** A position value as the JSON codec keeps it: strings, booleans, longs and finite doubles. */
        fun JsonNode.positionValue(): Any? = when {
            isNull -> null
            isString -> stringValue()
            isBoolean -> booleanValue()
            isIntegralNumber -> longValue()
            isNumber -> doubleValue()
            else -> throw QueryExecutionException("Cursor position must be a scalar.")
        }
    }
}
