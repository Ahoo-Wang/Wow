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

package me.ahoo.wow.benchmark.transfer.raw

import java.sql.PreparedStatement
import java.sql.Types

/**
 * Wow's PostgreSQL statement texts and parameter types, on the clone's tables (`raw_log`, `raw_snapshot`,
 * `raw_partition`): the columns Wow's append and save write, its transaction bound, its append and save texts, and
 * its binding of a parameter by its type. A text differs from Wow's only in its table names.
 */
object PostgresTexts {
    /** A column: its name and the type Wow binds and casts it as. */
    data class Column(val name: String, val type: String)

    /** Wow's server bounds at its default 30-second operation timeout: one second under it, and twice it. */
    const val SERVER_MILLIS = 29_000
    const val IDLE_MILLIS = 60_000

    const val JSONB = "jsonb"

    /** [Aggregates.idOf] in SQL, of the number `g`. */
    const val ID_SQL = "'${Aggregates.ID_PREFIX}' || lpad(g::text, ${Aggregates.ID_DIGITS}, '0')"
    const val UUID_SQL = "gen_random_uuid()::text"
    const val BYTEA = "bytea"

    /**
     * The columns of Wow's append (AggregateLogTable.COLUMNS), in its order, each with the type its text casts the
     * parameter to.
     */
    val LOG_COLUMNS = columns(
        "tenant_id text", "aggregate_id text", "version integer", "command_id text", "request_id text",
        "command_type text", "commit_time bigint", "events jsonb", "owner_id text", "space_id text",
        "correlation_id text", "causation_id text", "headers jsonb", "transport bytea", "operator_id text",
        "operator_tenant_id text", "operator_acted_elsewhere boolean", "access jsonb", "access_version integer",
    )

    /** The columns Wow's save writes, in its order, each with the type it binds (its text casts the jsonb ones). */
    val SNAPSHOT_COLUMNS = columns(
        "tenant_id text", "aggregate_id text", "version integer", "schema_fingerprint text", "state jsonb",
        "owner_id text", "space_id text", "deleted boolean", "first_event_time bigint", "last_event_time bigint",
        "access jsonb", "operator jsonb", "first_operator jsonb", "schedules jsonb", "state_revision integer",
    )

    private fun columns(vararg named: String): List<Column> =
        named.map { it.split(' ').let { (name, type) -> Column(name, type) } }

    /** Wow's save guard: a higher version, of the same or a higher state revision. */
    const val SAVE_GUARD = "excluded.version > stored.version AND excluded.state_revision >= stored.state_revision"

/**
     * Wow's bound of a transaction at its default 30-second operation timeout (PostgresTimeouts), as the first
     * statement's prefix: `synchronous_commit` off unless [synchronous], the statement and lock timeouts one second
     * under it, idle in transaction twice it.
     */
    fun bound(synchronous: Boolean): String = buildList {
        if (!synchronous) add("SET LOCAL synchronous_commit = off")
        add("SET LOCAL statement_timeout = $SERVER_MILLIS")
        add("SET LOCAL lock_timeout = $SERVER_MILLIS")
        add("SET LOCAL idle_in_transaction_session_timeout = $IDLE_MILLIS")
    }.joinToString("") { "$it; " }

/**
     * Wow's group commit across partitions (PostgresEventLogTables.appendGroupAcross) for one table, on the clone: its
     * text with `raw_log` and `raw_partition` for Wow's tables and branch 0 for its one table.
     */
    fun appendText(streams: Int): String = synchronized(appends) {
        appends.getOrPut(streams) {
            val columns = LOG_COLUMNS.joinToString { it.name }
            val values = (0 until streams).joinToString(", ") { ord ->
                "($ord, 0, ?::integer, " + LOG_COLUMNS.joinToString { "?::${it.type}" } + ")"
            }
            """
        WITH input (ord, branch, part, $columns) AS (VALUES $values),
        current AS (SELECT input.ord, coalesce((SELECT max(log.version) FROM raw_log AS log WHERE log.aggregate_id = input.aggregate_id AND log.tenant_id = input.tenant_id), 0) AS version FROM input WHERE input.branch = 0),
        links AS (
            SELECT input.ord, row_number() OVER (
                PARTITION BY input.branch, input.tenant_id, input.aggregate_id ORDER BY input.version
            ) AS link
            FROM input
        ),
        fits AS (
            SELECT input.*, row_number() OVER (PARTITION BY input.part ORDER BY input.ord) AS rank,
                count(*) OVER (PARTITION BY input.part) AS total
            FROM input JOIN current ON current.ord = input.ord JOIN links ON links.ord = input.ord
            WHERE input.version - current.version = links.link
        ), counts AS (
            SELECT part, count(*) AS taken FROM fits GROUP BY part
        ), locked AS (
            SELECT partition FROM raw_partition WHERE partition IN (SELECT part FROM counts)
            ORDER BY partition FOR UPDATE
        ), bump AS (
            UPDATE raw_partition SET sequence = raw_partition.sequence + counts.taken
            FROM counts WHERE raw_partition.partition = counts.part
                AND raw_partition.partition IN (SELECT partition FROM locked)
            RETURNING raw_partition.partition, raw_partition.sequence
        ), appended_0 AS (INSERT INTO raw_log (partition, sequence, $columns) SELECT fits.part, bump.sequence - fits.total + fits.rank, ${LOG_COLUMNS.joinToString { "fits.${it.name}" }} FROM fits JOIN bump ON bump.partition = fits.part WHERE fits.branch = 0 ORDER BY fits.tenant_id, fits.request_id RETURNING sequence)
        SELECT current.ord, current.version, bump.sequence - fits.total + fits.rank AS sequence
        FROM current LEFT JOIN fits ON fits.ord = current.ord LEFT JOIN bump ON bump.partition = fits.part
        ORDER BY current.ord
            """.trimIndent()
        }
    }

/**
     * Wow's group commit of one partition's streams (PostgresEventLogTables.appendGroup) for one table, on the clone:
     * its text with `raw_log` and `raw_partition` for Wow's tables and branch 0 for its one table. Wow sends it for a
     * group whose streams are all of one partition: every one-stream call, and of a call of n streams spread over
     * the partitions about (1 / partitions)^(n - 1).
     */
    fun appendOneText(partition: Int, streams: Int): String = synchronized(appends) {
        appends.getOrPut(-(partition * MAX_STREAMS + streams) - 1) {
            val columns = LOG_COLUMNS.joinToString { it.name }
            val values = (0 until streams).joinToString(", ") { ord ->
                "($ord, 0, " + LOG_COLUMNS.joinToString { "?::${it.type}" } + ")"
            }
            """
            WITH input (ord, branch, $columns) AS (VALUES $values),
            current AS (SELECT input.ord, coalesce((SELECT max(log.version) FROM raw_log AS log WHERE log.aggregate_id = input.aggregate_id AND log.tenant_id = input.tenant_id), 0) AS version FROM input WHERE input.branch = 0),
            links AS (
                SELECT input.ord, row_number() OVER (
                    PARTITION BY input.branch, input.tenant_id, input.aggregate_id ORDER BY input.version
                ) AS link
                FROM input
            ),
            fits AS (
                SELECT input.*, row_number() OVER (ORDER BY input.ord) AS rank, count(*) OVER () AS total
                FROM input JOIN current ON current.ord = input.ord JOIN links ON links.ord = input.ord
                WHERE input.version - current.version = links.link
            ), bump AS (
                UPDATE raw_partition SET sequence = sequence + (SELECT count(*) FROM fits)
                WHERE partition = $partition AND EXISTS (SELECT 1 FROM fits)
                RETURNING sequence
            ), appended_0 AS (INSERT INTO raw_log (partition, sequence, $columns) SELECT $partition, bump.sequence - fits.total + fits.rank, ${LOG_COLUMNS.joinToString { "fits.${it.name}" }} FROM fits, bump WHERE fits.branch = 0 ORDER BY fits.tenant_id, fits.request_id RETURNING sequence)
            SELECT current.ord, current.version, bump.sequence - fits.total + fits.rank AS sequence
            FROM current LEFT JOIN fits ON fits.ord = current.ord LEFT JOIN bump ON true
            ORDER BY current.ord
            """.trimIndent()
        }
    }

    /** Wow's save of [rows] snapshots (PostgresSnapshotTables.saveRows) on the clone: returning the keys it wrote. */
    fun upsertText(rows: Int): String = synchronized(upserts) {
        upserts.getOrPut(rows) {
            val row = "(" + SNAPSHOT_COLUMNS.joinToString { if (it.type == JSONB) "?::jsonb" else "?" } + ")"
            val updates = SNAPSHOT_COLUMNS.filter { it.name != "tenant_id" && it.name != "aggregate_id" }
                .joinToString(",\n    ") { "${it.name} = excluded.${it.name}" }
            """
        INSERT INTO raw_snapshot AS stored (${SNAPSHOT_COLUMNS.joinToString { it.name }})
        VALUES ${List(rows) { row }.joinToString(", ")}
        ON CONFLICT (tenant_id, aggregate_id) DO UPDATE SET
            $updates,
            stale_version = CASE WHEN stored.stale_version > excluded.version THEN stored.stale_version END
        WHERE $SAVE_GUARD
        RETURNING tenant_id, aggregate_id
            """.trimIndent()
        }
    }

/** A snapshot read by its key. */
    val read: String get() = "SELECT ${SNAPSHOT_COLUMNS.joinToString { it.name }} FROM raw_snapshot " +
        "WHERE tenant_id = ? AND aggregate_id = ?"

    private val appends = HashMap<Int, String>()
    private val upserts = HashMap<Int, String>()
    private const val MAX_STREAMS = 100_000

    /** Binds [value] as Wow binds a parameter of [type]: by its type, a null as a null of its SQL type. */
    fun bindTyped(statement: PreparedStatement, index: Int, type: String, value: Any?) {
        when {
            value == null -> statement.setNull(index, sqlTypeOf(type))
            type == "integer" -> statement.setInt(index, (value as Number).toInt())
            type == "bigint" -> statement.setLong(index, (value as Number).toLong())
            type == "boolean" -> statement.setBoolean(index, value as Boolean)
            type == BYTEA -> statement.setBytes(index, value as ByteArray)
            else -> statement.setString(index, value.toString())
        }
    }

    private fun sqlTypeOf(type: String): Int = when (type) {
        "integer" -> Types.INTEGER
        "bigint" -> Types.BIGINT
        "boolean" -> Types.BOOLEAN
        BYTEA -> Types.BINARY
        else -> Types.VARCHAR
    }
}
