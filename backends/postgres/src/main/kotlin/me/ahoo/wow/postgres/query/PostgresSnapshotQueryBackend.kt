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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ICountQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.postgres.PostgresFailures
import me.ahoo.wow.postgres.query.PostgresSnapshotSqlCompiler.Companion.keepsState
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotTables
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.BackendPage
import me.ahoo.wow.query.CursorPosition
import me.ahoo.wow.query.CursorPositionCodec
import me.ahoo.wow.query.GroupWindow
import me.ahoo.wow.query.PageWindow
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.QueryExecutionException
import me.ahoo.wow.query.QueryTimeoutException
import me.ahoo.wow.query.schema.SnapshotFields
import me.ahoo.wow.spi.json.WowJson
import org.postgresql.util.PGobject
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Duration
import javax.sql.DataSource

/**
 * The snapshot query backend of one aggregate type on PostgreSQL (decision D131): the four primitives over the
 * aggregate type's own snapshot table (decision D161), compiled to parameterized SQL ([PostgresSnapshotSqlCompiler],
 * [PostgresAggregationCompiler]) on the bindings of [PostgresSnapshotStorageAdapter]. Records have the shape of
 * `SnapshotRecord.toQueryRecord()`; a projection is applied to each record as it is read, and a row without any
 * kept state field does not read the state at all.
 *
 * Every statement runs in its own read transaction under `SET LOCAL statement_timeout` of the admitted timeout
 * (decision D150); PostgreSQL cancelling it there is a [QueryTimeoutException]. JDBC calls run on [dispatcher],
 * virtual threads by default (decision D29). A table not created yet (no snapshot of the aggregate type was saved)
 * holds no records: every primitive answers as over an empty table, as the provisional facts of
 * [PostgresQuerySchemaAdapter] describe it.
 */
@InternalWowApi
class PostgresSnapshotQueryBackend internal constructor(
    override val aggregateType: AggregateType,
    private val dataSource: DataSource,
    tables: PostgresSnapshotTables,
    private val options: PostgresQueryOptions,
    private val dispatcher: CoroutineDispatcher,
) : QueryBackend {
    override val cursorPositions: CursorPositionCodec = PostgresCursorPositionCodec

    private val records = PostgresSnapshotSqlCompiler(tables.snapshot)
    private val aggregations = PostgresAggregationCompiler(tables.snapshot)

    /**
     * The records of [query], read in full in their transaction, which then ends and gives its connection back, and
     * only then emitted (finding A2-9): a consumer as slow as an SSE client's never holds a connection of the pool the
     * log appends through. The admission bounds the list (`maxListSize`); a budget without that bound
     * (`maxListSize = 0`) holds a whole unbounded list in memory.
     */
    override fun stream(query: AdmittedQuery<IListQuery>): Flow<ObjectNode> = flow {
        val statement = records.list(query)
        val state = query.query.projection.keepsState()
        val read = transaction(query.timeout, { emptyList() }) { connection ->
            statement.prepare(connection).use { prepared ->
                prepared.fetchSize = options.fetchSize
                prepared.executeQuery().use { rows ->
                    val result = ArrayList<ObjectNode>()
                    while (rows.next()) result += project(query, record(rows, state))
                    result
                }
            }
        }
        read.forEach { emit(it) }
    }.flowOn(dispatcher)

    override suspend fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): BackendPage =
        withContext(dispatcher) {
            when (window) {
                is PageWindow.Offset -> offset(query, window)
                is PageWindow.Keyset -> keyset(query, window)
            }
        }

    private fun offset(query: AdmittedQuery<Queryable<*>>, window: PageWindow.Offset): BackendPage {
        val statement = records.page(query, window)
        val count = if (window.withTotal) {
            records.count(
                query,
                query.query.filter,
                window.totalLimit?.let { it + 1L }
            )
        } else {
            null
        }
        val state = query.query.projection.keepsState()
        return transaction(query.timeout, { BackendPage(emptyList(), count?.let { 0L }) }) { connection ->
            val rows = read(connection, statement) { project(query, record(it, state)) }
            BackendPage(rows, count?.let { count(connection, it) })
        }
    }

    private fun keyset(query: AdmittedQuery<Queryable<*>>, window: PageWindow.Keyset): BackendPage {
        val statement = records.keyset(query, window)
        val state = query.query.projection.keepsState()
        val keys = query.query.sort.size
        return transaction(query.timeout, { BackendPage(emptyList(), positions = emptyList()) }) { connection ->
            val positions = ArrayList<CursorPosition>()
            val rows = read(connection, statement) { row ->
                positions += CursorPosition((0 until keys).map { position(row.getObject(PostgresSnapshotSqlCompiler.key(it))) })
                project(query, record(row, state))
            }
            BackendPage(rows, positions = positions)
        }
    }

    override suspend fun count(query: AdmittedQuery<ICountQuery>): Long = withContext(dispatcher) {
        val statement = records.count(query, query.query.filter, query.query.limit?.let { it + 1L })
        transaction(query.timeout, { 0L }) { connection -> count(connection, statement) }
    }

    override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flow<ObjectNode> = flow {
        val statement = aggregations.compile(query, window)
        val groups = query.query.groupBy.map { it.alias }
        val metrics = query.query.metrics.map { it.alias }
        // Read in full before any is emitted, as stream reads (finding A2-9): the groups are bounded by the window.
        val read = transaction(query.timeout, { emptyList() }) { connection ->
            read(connection, statement) { group(it, groups, metrics) }
        }
        read.forEach { emit(it) }
    }.flowOn(dispatcher)

    /** One group's row: its keys under their aliases, then each metric, in the query's order. */
    private fun group(
        row: ResultSet,
        groups: List<String>,
        metrics: List<String>
    ): ObjectNode = nodes.objectNode().apply {
        groups.forEachIndexed { index, alias ->
            set(
                alias,
                row.getObject(PostgresAggregationCompiler.group(index)).node()
            )
        }
        metrics.forEachIndexed { index, alias ->
            set(
                alias,
                row.getObject(PostgresAggregationCompiler.metric(index)).node()
            )
        }
    }

    /**
     * Runs [block] in a transaction of its own under [timeout]: a statement PostgreSQL cancels for it fails as the
     * core's [QueryTimeoutException]. The transaction only reads; it commits when [block] returns and rolls back
     * when it throws. A table that does not exist yet is empty: the answer is [missing]'s.
     */
    private inline fun <T> transaction(timeout: Duration?, missing: () -> T, block: (Connection) -> T): T = try {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            var committed = false
            try {
                timeout?.let { limit ->
                    connection.createStatement().use {
                        it.execute(
                            "SET LOCAL statement_timeout = ${statementTimeout(limit)}"
                        )
                    }
                }
                block(connection).also {
                    connection.commit()
                    committed = true
                }
            } finally {
                if (!committed) connection.rollback()
                connection.autoCommit = true
            }
        }
    } catch (error: SQLException) {
        when {
            error.sqlState == UNDEFINED_TABLE -> missing()
            timeout != null && error.sqlState == QUERY_CANCELED -> throw QueryTimeoutException(timeout, error)
            else -> throw PostgresFailures.translate(error)
        }
    }

    private fun <T> read(connection: Connection, statement: SqlStatement, map: (ResultSet) -> T): List<T> =
        statement.prepare(connection).use { prepared ->
            prepared.executeQuery().use { rows ->
                val result = ArrayList<T>()
                while (rows.next()) result += map(rows)
                result
            }
        }

    private fun count(connection: Connection, statement: SqlStatement): Long =
        statement.prepare(connection).use { prepared ->
            prepared.executeQuery().use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }

    internal companion object {
        /** PostgreSQL's SQLSTATE of a statement cancelled, by `statement_timeout` among others. */
        const val QUERY_CANCELED = "57014"

        /** PostgreSQL's SQLSTATE of a table that does not exist. */
        const val UNDEFINED_TABLE = "42P01"
        private val nodes: JsonNodeFactory = JsonNodeFactory.instance

        /** [timeout] in whole milliseconds, rounded up and at least 1, since `0` would disable the limit. */
        fun statementTimeout(timeout: Duration): Long {
            val millis = timeout.toMillis()
            val rounded = if (timeout > Duration.ofMillis(millis)) millis + 1 else millis
            return rounded.coerceIn(1, Int.MAX_VALUE.toLong())
        }

        /** The snapshot record of [row], the shape of `SnapshotRecord.toQueryRecord()`; the state only when read. */
        fun record(row: ResultSet, state: Boolean): ObjectNode = nodes.objectNode().apply {
            put(SnapshotFields.TENANT_ID, row.getString("tenant_id"))
            put(SnapshotFields.AGGREGATE_ID, row.getString("aggregate_id"))
            put(SnapshotFields.VERSION, row.getInt("version"))
            put(SnapshotFields.OWNER_ID, row.getString("owner_id"))
            put(SnapshotFields.SPACE_ID, row.getString("space_id"))
            put(SnapshotFields.DELETED, row.getBoolean("deleted"))
            set(SnapshotFields.TAGS, WowJson.mapper.readTree(row.getString("tags")))
            put(SnapshotFields.FIRST_EVENT_TIME, row.getLong("first_event_time"))
            put(SnapshotFields.LAST_EVENT_TIME, row.getLong("last_event_time"))
            set(SnapshotFields.OPERATOR, row.getString("operator")?.let(WowJson.mapper::readTree) ?: nodes.nullNode())
            set(
                SnapshotFields.FIRST_OPERATOR,
                row.getString("first_operator")?.let(WowJson.mapper::readTree) ?: nodes.nullNode(),
            )
            // An aggregate without schedules keeps none (decision D198): the column is null, the record's object empty.
            set(
                SnapshotFields.SCHEDULES,
                row.getString("schedules")?.let(WowJson.mapper::readTree) ?: nodes.objectNode(),
            )
            if (state) set(SnapshotFields.STATE, WowJson.mapper.readTree(row.getString("state")))
        }

        /** A sort value as a position value: a JSON scalar, a number as a [BigDecimal]. */
        fun position(value: Any?): Any? = when (value) {
            null, is String, is Boolean, is BigDecimal -> value
            is Int, is Long, is Short -> BigDecimal.valueOf((value as Number).toLong())
            else -> throw QueryExecutionException(
                "A sort value of type [${value.javaClass.simpleName}] has no position."
            )
        }

        /** A column value as JSON: `jsonb` as its tree, a number exactly. */
        fun Any?.node(): JsonNode = when (this) {
            null -> nodes.nullNode()
            is String -> nodes.stringNode(this)
            is Boolean -> nodes.booleanNode(this)
            is BigDecimal -> nodes.numberNode(this)
            is Int, is Long, is Short -> nodes.numberNode((this as Number).toLong())
            is Double -> nodes.numberNode(this)
            is PGobject -> json(this)
            else -> throw QueryExecutionException("A column of type [${javaClass.simpleName}] has no JSON value.")
        }

        private fun json(value: PGobject): JsonNode {
            val text: String = value.value ?: return nodes.nullNode()
            return WowJson.mapper.readTree(text)
        }

        /** [record] with only what [query]'s projection keeps: its includes (all when none), less its excludes. */
        fun project(query: AdmittedQuery<Queryable<*>>, record: ObjectNode): ObjectNode {
            val projection = query.query.projection
            if (projection.include.isEmpty() && projection.exclude.isEmpty()) return record
            val projected = if (projection.include.isEmpty()) {
                record
            } else {
                nodes.objectNode().also { target ->
                    projection.include.forEach {
                        include(
                            record,
                            target,
                            query.physicalPath(it).split('.')
                        )
                    }
                }
            }
            projection.exclude.forEach { exclude(projected, query.physicalPath(it).split('.')) }
            return projected
        }

        fun include(source: JsonNode, target: ObjectNode, path: List<String>) {
            val name = path.first()
            val value = source.get(name) ?: return
            if (path.size == 1) {
                target.set(name, value.deepCopy())
                return
            }
            when {
                value.isObject -> include(
                    value,
                    (target.get(name) as? ObjectNode) ?: target.putObject(name),
                    path.drop(1)
                )
                value.isArray -> {
                    val items = target.get(name) as? ArrayNode ?: target.putArray(name)
                    value.forEachIndexed { index, item ->
                        if (!item.isObject) return@forEachIndexed
                        while (items.size() <= index) items.addObject()
                        include(item, items.get(index) as ObjectNode, path.drop(1))
                    }
                }
            }
        }

        fun exclude(node: JsonNode, path: List<String>) {
            when {
                node is ObjectNode && path.size == 1 -> node.remove(path.first())
                node is ObjectNode -> node.get(path.first())?.let { exclude(it, path.drop(1)) }
                node.isArray -> node.forEach { exclude(it, path) }
            }
        }
    }
}
