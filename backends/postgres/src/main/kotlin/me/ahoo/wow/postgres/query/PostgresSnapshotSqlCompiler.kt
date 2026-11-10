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
import me.ahoo.wow.query.backend.tieBreakerDirection
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
     * field's direction and its nulls. A window whose first key descends and may hold `NULL`, after a position that
     * holds a value, is read in two parts ([splitsNulls]): these are its rows that hold a value, and [keysetNulls]
     * the rest.
     */
    fun keyset(query: AdmittedQuery<Queryable<*>>, window: PageWindow.Keyset): SqlStatement {
        val after = window.after
        return window(query, window.limit) { parameters, keys -> after?.let { after(parameters, query, keys, it) } }
    }

    /**
     * The second part of a window [keyset] reads in two, at most [limit] rows: those whose first key is `NULL`, which
     * sort after every value descending, all of them after the position. `null` for a window read in one part. The
     * backend reads it only when the first part does not fill the window.
     */
    fun keysetNulls(query: AdmittedQuery<Queryable<*>>, window: PageWindow.Keyset, limit: Int): SqlStatement? {
        val after = window.after?.takeIf { splitsNulls(query, it) } ?: return null
        return window(query, limit) { _, keys -> "${keys.first().first} IS NULL" }
    }

    /**
     * Whether a window after [after] is read in two parts: its first key descends, may hold `NULL` and holds a value at
     * the position. Its rows that hold a value then compare as one row with the position, which a `NULL` never
     * satisfies, so an index seeks to the position instead of filtering every row before it; the `NULL`s follow.
     */
    private fun splitsNulls(query: AdmittedQuery<Queryable<*>>, after: CursorPosition): Boolean {
        // A cursor's sort ends with the record's key, so it has a first key.
        val first = query.query.sort.first()
        return first.direction == Sort.Direction.DESC && after.values.firstOrNull() != null &&
            !layout.expressions.notNull(query.field(first.field).physicalField)
    }

    private fun window(
        query: AdmittedQuery<Queryable<*>>,
        limit: Int,
        condition: (SqlParameters, List<Pair<String, PostgresQueryType>>) -> String?,
    ): SqlStatement {
        val parameters = SqlParameters()
        val compiler = PostgresFilterCompiler(query, parameters, layout.expressions)
        val keys = query.query.sort.map { compiler.typed(query.field(it.field)) }
        val sql = StringBuilder(layout.select(query.query.projection))
        keys.forEachIndexed { index, (value, _) -> sql.append(", ").append(value).append(" AS ").append(key(index)) }
        sql.append(" FROM ").append(table)
            .append(" WHERE ").append(compiler.filter(query.query.filter))
        condition(parameters, keys)?.let { sql.append(" AND ").append(it) }
        sql.append(" ORDER BY ").append(order(query, compiler, query.query.sort, total = false))
            .append(" LIMIT ").append(parameters.bind(SqlParameter.Int8(limit.toLong())))
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
        val terms = sort.mapTo(ArrayList()) { order ->
            val field = query.field(order.field)
            direction(compiler.typed(field).first, order.direction, layout.expressions.notNull(field.physicalField))
        }
        if (total) {
            // NOT NULL columns, ordered plainly, in the direction the core ends a sort with its key.
            val direction = sort.tieBreakerDirection()
            layout.tieBreakers.forEach { (field, column) ->
                if (sort.none { query.physicalPath(it.field) == field }) terms += "$column ${direction.name}"
            }
        }
        return terms.joinToString(", ")
    }

    /**
     * The rows after the position [after] in [query]'s sort, in a form a B-tree index can seek to. The leading keys of
     * the first key's direction whose position values are not `NULL` (descending, only columns that hold no `NULL`,
     * since a `NULL` sorts last there, after every value) compare as one row, `(k0, …, kn) > (v0, …, vn)` (`<`
     * descending), which an index on those columns seeks to. When they are every key, that comparison is the whole
     * condition; otherwise it bounds the expanded `OR` ([expanded]) with `>=` (`<=`), redundantly, since the `OR` alone
     * is no index condition and a page would scan every row before its position. Descending, the first key leads the
     * row even when it may hold `NULL`: such a window reads its `NULL`s in a second part ([splitsNulls]), so this one
     * is its rows that hold a value, which the row comparison keeps.
     */
    private fun after(
        parameters: SqlParameters,
        query: AdmittedQuery<Queryable<*>>,
        keys: List<Pair<String, PostgresQueryType>>,
        after: CursorPosition,
    ): String {
        val sort = query.query.sort
        val notNull = sort.map { layout.expressions.notNull(query.field(it.field).physicalField) }
        val direction = sort.first().direction
        val leading = sort.indices.takeWhile { index ->
            sort[index].direction == direction && after.values.getOrNull(index) != null &&
                (direction == Sort.Direction.ASC || notNull[index] || index == 0)
        }
        if (leading.isEmpty()) return expanded(parameters, sort, keys, notNull, after)
        val whole = leading.size == sort.size
        val operator = when (direction) {
            Sort.Direction.ASC -> if (whole) ">" else ">="
            Sort.Direction.DESC -> if (whole) "<" else "<="
        }
        val columns = leading.map { keys[it].first }
        val values = leading.map { parameters.bind(position(keys[it].second, checkNotNull(after.values[it]))) }
        val bound = columns.singleOrNull()?.let { "$it $operator ${values.single()}" }
            ?: (columns.joinToString(", ", "(", ")") + " $operator " + values.joinToString(", ", "(", ")"))
        return if (whole) bound else "$bound AND " + expanded(parameters, sort, keys, notNull, after)
    }

    /** The rows after [after] in [sort], the expanded row comparison that honors each key's direction and nulls. */
    private fun expanded(
        parameters: SqlParameters,
        sort: List<Sort>,
        keys: List<Pair<String, PostgresQueryType>>,
        notNull: List<Boolean>,
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
                (ties + later(parameters, value, type, sort[index].direction, notNull[index], values[index]))
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
        notNull: Boolean,
        position: Any?,
    ): String = when {
        position == null -> "$value IS NOT NULL"
        direction == Sort.Direction.ASC -> "$value > ${parameters.bind(position(type, position))}"
        // A NULL sorts last descending, after every value, unless the column holds none.
        notNull -> "$value < ${parameters.bind(position(type, position))}"
        else -> "($value < ${parameters.bind(position(type, position))} OR $value IS NULL)"
    }

    companion object {
        /** The columns of a record, without the state. */
        private const val RECORD_COLUMNS =
            "tenant_id, aggregate_id, version, owner_id, space_id, deleted, access, first_event_time, last_event_time, " +
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

        /**
         * [value] ordered in [direction], a `NULL` first ascending and last descending. A column that holds no `NULL`
         * ([notNull]) is ordered plainly, the same order, as a B-tree index on it reads by default
         * (`ASC NULLS LAST`), so the index gives the order: the table's key gives the default one.
         */
        fun direction(value: String, direction: Sort.Direction, notNull: Boolean = false): String = when {
            notNull -> "$value ${direction.name}"
            direction == Sort.Direction.ASC -> "$value ASC NULLS FIRST"
            else -> "$value DESC NULLS LAST"
        }

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
