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
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.BackendPage
import me.ahoo.wow.query.CursorPositionCodec
import me.ahoo.wow.query.GroupWindow
import me.ahoo.wow.query.PageWindow
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.QueryExecutionException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode

/**
 * The four query primitives over one ClickHouse table. S0 spike: [namedAggregate] is the pseudo aggregate that names
 * the dataset until the core has a `QueryTarget`; [fields] are the declared columns a record returns.
 */
class ClickHouseDatasetQueryBackend(
    override val namedAggregate: NamedAggregate,
    private val executor: ClickHouseExecutor,
    private val table: () -> Mono<ClickHouseTable>,
    private val fields: List<String>,
) : QueryBackend {
    override val cursorPositions: CursorPositionCodec = CursorPositionCodec.JSON

    override fun stream(query: AdmittedQuery<IListQuery>): Flux<ObjectNode> = table().flatMapMany { table ->
        executor.rows(select(query, table, query.query.limit.takeIf { it > 0 }, 0))
    }

    override fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): Mono<BackendPage> =
        table().flatMap { table ->
            val offset = window as? PageWindow.Offset
                ?: throw QueryExecutionException("ClickHouse datasets do not page by keyset yet.")
            val rows = executor.rows(select(query, table, offset.limit, offset.offset)).collectList()
            if (!offset.withTotal) return@flatMap rows.map { BackendPage(it) }
            val total = count(query.query.filter, query, table)
            Mono.zip(rows, total).map { BackendPage(it.t1, it.t2) }
        }

    override fun count(query: AdmittedQuery<FilterExpression>): Mono<Long> =
        table().flatMap { count(query.query, query, it) }

    override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flux<ObjectNode> =
        table().flatMapMany { table -> executor.rows(ClickHouseAggregationCompiler(query, table).compile(window)) }

    private fun count(filter: FilterExpression, admitted: AdmittedQuery<*>, table: ClickHouseTable): Mono<Long> {
        val parameters = SqlParameters()
        val where = ClickHouseFilterCompiler(admitted, table, parameters).compile(filter)
        return executor.rows(parameters.statement("SELECT count() AS total FROM ${table.qualifiedName} WHERE $where"))
            .single()
            .map { it.get("total").asLong() }
    }

    private fun select(admitted: AdmittedQuery<*>, table: ClickHouseTable, limit: Int?, offset: Int): ClickHouseStatement {
        val query = admitted.query as Queryable<*>
        val parameters = SqlParameters()
        val filters = ClickHouseFilterCompiler(admitted, table, parameters)
        val sql = buildString {
            append("SELECT ").append(columns(admitted, query.projection).joinToString(", ", transform = ::quoteIdentifier))
            append(" FROM ").append(table.qualifiedName)
            append(" WHERE ").append(filters.compile(query.filter))
            if (query.sort.isNotEmpty()) {
                append(" ORDER BY ").append(
                    query.sort.joinToString(", ") {
                        "${filters.column(it.field)} ${if (it.direction == Sort.Direction.ASC) "ASC" else "DESC"}"
                    },
                )
            }
            limit?.let { append(" LIMIT ").append(it) }
            if (offset > 0) append(" OFFSET ").append(offset)
        }
        return parameters.statement(sql)
    }

    private fun columns(admitted: AdmittedQuery<*>, projection: Projection): List<String> = when {
        projection.include.isNotEmpty() -> projection.include.map(admitted::physicalPath)
        projection.exclude.isNotEmpty() -> fields - projection.exclude.map(admitted::physicalPath).toSet()
        else -> fields
    }
}
