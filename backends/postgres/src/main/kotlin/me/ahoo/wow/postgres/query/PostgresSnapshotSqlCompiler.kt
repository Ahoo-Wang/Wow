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

import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.admission.AdmittedQuery
import me.ahoo.wow.query.backend.CursorPosition
import me.ahoo.wow.query.backend.PageWindow
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.spi.json.query.SnapshotFields
import java.math.BigDecimal

/**
 * Compiles the record primitives of one aggregate type over one of its own tables (`table`, quoted), read as [layout]
 * says: its snapshot table or its log table: a list, an offset page, a keyset page and a
 * bounded count. The table holds that aggregate type alone, so no statement names it; admission has already added the
 * tenant, the caller's scope and the deletion state to the filter.
 *
 * Sorts read the typed value of each field, ascending with `NULLS FIRST` and descending with `NULLS LAST`: a missing
 * field and `null` sort lowest, as in MongoDB. A list and an offset page end with the layout's tie-breakers (the
 * aggregate ID and the tenant of a snapshot), so their order is total; a keyset page orders by the cursor's sort alone,
 * which admission ended with the model's unique key.
 */
internal class PostgresSnapshotSqlCompiler(
    private val table: String,
    private val layout: PostgresQueryLayout = PostgresQueryLayout.SNAPSHOT,
) {
    /** The rows of a list: all of them when its limit is `0`. */
    fun list(query: AdmittedQuery<ListQuery>): SqlStatement {
        val parameters = SqlParameters()
        val compiler = PostgresFilterCompiler(query, parameters, layout.expressions)
        val sql = StringBuilder(layout.select(query.query.projection))
            .append(" FROM ").append(table)
            .append(" WHERE ").append(compiler.filter(query.query.filter))
            .append(" ORDER BY ").append(order(query, compiler, query.query.sort, total = true))
        query.query.limit?.let { limit ->
            sql.append(" LIMIT ").append(parameters.bind(SqlParameter.Int8(limit.toLong())))
        }
        return parameters.statement(sql.toString())
    }

    /** The rows of an offset window. */
    fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow.Offset): SqlStatement {
        val parameters = SqlParameters()
        val compiler = PostgresFilterCompiler(query, parameters, layout.expressions)
        val sql = StringBuilder(layout.select(query.query.projection))
            .append(" FROM ").append(table)
            .append(" WHERE ").append(compiler.filter(query.query.filter))
            .append(" ORDER BY ").append(order(query, compiler, query.query.sort, total = true))
            .append(" LIMIT ").append(parameters.bind(SqlParameter.Int8(window.limit.toLong())))
            .append(" OFFSET ").append(parameters.bind(SqlParameter.Int8(window.offset.toLong())))
        return parameters.statement(sql.toString())
    }

    /**
     * The rows of a keyset window, each with its position: the sort values as the columns `k0`, `k1`, ... The rows
     * after a position are those that sort strictly after it, the expanded form of a row comparison that honors each
     * field's direction and its nulls.
     */
    fun keyset(query: AdmittedQuery<Queryable<*>>, window: PageWindow.Keyset): SqlStatement {
        val parameters = SqlParameters()
        val compiler = PostgresFilterCompiler(query, parameters, layout.expressions)
        val keys = query.query.sort.map { compiler.typed(query.field(it.field)) }
        val sql = StringBuilder(layout.select(query.query.projection))
        keys.forEachIndexed { index, (value, _) -> sql.append(", ").append(value).append(" AS ").append(key(index)) }
        sql.append(" FROM ").append(table)
            .append(" WHERE ").append(compiler.filter(query.query.filter))
        window.after?.let { after -> sql.append(" AND ").append(after(parameters, query.query.sort, keys, after)) }
        sql.append(" ORDER BY ").append(order(query, compiler, query.query.sort, total = false))
            .append(" LIMIT ").append(parameters.bind(SqlParameter.Int8(window.limit.toLong())))
        return parameters.statement(sql.toString())
    }

    /**
     * The number of records matching [filter]; with a [limit], at most `limit` of them are counted, as
     * `count(*)` over a `LIMIT` subquery. [limit] is the core's limit plus one.
     */
    fun count(query: AdmittedQuery<*>, filter: FilterExpression, limit: Long?): SqlStatement {
        val parameters = SqlParameters()
        val compiler = PostgresFilterCompiler(query, parameters, layout.expressions)
        val where = compiler.filter(filter)
        if (limit == null) return parameters.statement("SELECT count(*) FROM $table WHERE $where")
        val bound = parameters.bind(SqlParameter.Int8(limit))
        return parameters.statement("SELECT count(*) FROM (SELECT 1 FROM $table WHERE $where LIMIT $bound) AS bounded")
    }

    private fun order(query: AdmittedQuery<*>, compiler: PostgresFilterCompiler, sort: List<Sort>, total: Boolean): String {
        val terms = sort.mapTo(
            ArrayList()
        ) { order -> direction(compiler.typed(query.field(order.field)).first, order.direction) }
        if (total) {
            layout.tieBreakers.forEach { (field, column) ->
                if (sort.none { query.physicalPath(it.field) == field }) terms += "$column ASC"
            }
        }
        return terms.joinToString(", ")
    }

    private fun after(
        parameters: SqlParameters,
        sort: List<Sort>,
        keys: List<Pair<String, PostgresQueryType>>,
        after: CursorPosition,
    ): String {
        val values = after.values
        // Nothing sorts after a NULL descending (nulls last), so that field contributes no term.
        val terms = sort.indices.filterNot { sort[it].direction == Sort.Direction.DESC && values[it] == null }
            .map { index ->
                val ties = (0 until index).map { tie ->
                    val (tied, tiedType) = keys[tie]
                    values[tie]?.let { "$tied = ${parameters.bind(position(tiedType, it))}" } ?: "$tied IS NULL"
                }
                val (value, type) = keys[index]
                (ties + later(parameters, value, type, sort[index].direction, values[index]))
                    .joinToString(" AND ", "(", ")")
            }
        return if (terms.isEmpty()) PostgresFilterCompiler.FALSE else terms.joinToString(" OR ", "(", ")")
    }

    /** The values of [value] after [position] in [direction]; a `NULL` sorts first ascending. */
    private fun later(
        parameters: SqlParameters,
        value: String,
        type: PostgresQueryType,
        direction: Sort.Direction,
        position: Any?,
    ): String = when {
        position == null -> "$value IS NOT NULL"
        direction == Sort.Direction.ASC -> "$value > ${parameters.bind(position(type, position))}"
        else -> "($value < ${parameters.bind(position(type, position))} OR $value IS NULL)"
    }

    companion object {
        /** The columns of a record, without the state. */
        private const val RECORD_COLUMNS =
            "tenant_id, aggregate_id, version, owner_id, space_id, deleted, tags, first_event_time, last_event_time, " +
                "operator, first_operator, schedules"

        fun key(index: Int): String = "k$index"

        /** The columns a row needs for [projection]: the state only when the projection keeps some of it. */
        fun select(projection: Projection): String =
            "SELECT $RECORD_COLUMNS" + if (projection.keepsState()) ", state" else ""

        fun Projection.keepsState(): Boolean = if (include.isEmpty()) {
            exclude.none { it.path == SnapshotFields.STATE }
        } else {
            include.any { it.path == SnapshotFields.STATE || it.path.startsWith(SnapshotFields.STATE + ".") }
        }

        fun direction(value: String, direction: Sort.Direction): String =
            if (direction == Sort.Direction.ASC) "$value ASC NULLS FIRST" else "$value DESC NULLS LAST"

        /** A decoded position value bound as [type]; a value of another type is not a position this backend wrote. */
        fun position(type: PostgresQueryType, value: Any): SqlParameter = when {
            type == PostgresQueryType.TEXT && value is String -> SqlParameter.Text(value)
            type == PostgresQueryType.BOOLEAN && value is Boolean -> SqlParameter.Bool(value)
            type.numeric && value is BigDecimal -> integral(type, value) ?: SqlParameter.Numeric(value)
            else -> throw QueryViolation.InvalidCursor.rejection()
        }

        private fun integral(type: PostgresQueryType, value: BigDecimal): SqlParameter? {
            if (type == PostgresQueryType.NUMERIC) return null
            return runCatching { SqlParameter.Int8(value.longValueExact()) }.getOrNull()
        }
    }
}
