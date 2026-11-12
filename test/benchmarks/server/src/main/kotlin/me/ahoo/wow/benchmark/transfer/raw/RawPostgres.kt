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

import me.ahoo.wow.benchmark.transfer.TransferContext.ACCOUNT_TYPE
import me.ahoo.wow.spi.id.GlobalIdGenerator
import me.ahoo.wow.spi.log.Partitioner
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.concurrent.atomic.AtomicInteger

/**
 * The raw run on PostgreSQL: Wow's tables cloned (`LIKE … INCLUDING ALL`: its columns, keys and indexes) as
 * `raw_log`, `raw_snapshot` and `raw_partition`, written with rows of the domain's own commits as templates, by Wow's
 * own statement texts and parameter types:
 *
 * - the append, Wow's group commit across partitions for one table ([appendText]): each stream's version read by the
 *   version key, linked to the streams before it, the ones that fit taken, the partitions' counters locked in order
 *   and bumped, the rows inserted in key order, each stream's sequence returned; one autocommitted statement;
 * - the save, Wow's version-guarded multi-row upsert of rows in key order, returning the keys it wrote ([upsertText]),
 *   one transaction a flush whose bound ([bound]: `synchronous_commit` off when asynchronous, and the timeouts Wow
 *   sets at its default 30-second operation timeout) is sent with it in one round trip, as Wow sends it.
 *
 * The texts differ from Wow's only in the clones' table names and, for the append, in naming one table (branch 0) of
 * the several Wow's text can name; the account type's group is that one table in Wow's run too. A stream's partition
 * is Wow's own ([Partitioner]); its command and request ids are Wow's UUIDv7s. A unit counts only when the store
 * wrote it: an append's returned sequence, an upsert's returned key; the rest are refused.
 */
class RawPostgres(
    private val url: String,
    private val user: String,
    private val password: String,
) {
    private var partitions = 0
    private val logColumns = LOG_COLUMNS
    private val snapshotColumns = SNAPSHOT_COLUMNS
    private lateinit var logTemplates: List<List<Any?>>
    private lateinit var snapshotTemplates: List<List<Any?>>
    private val template = AtomicInteger()

    /** A column: its name and the type Wow binds and casts it as. */
    data class Column(val name: String, val type: String)

    /** A case of this store, the snapshot table's [fillfactor] it runs at, and its flush on a connection. */
    class Case(val case: RawCase, val fillfactor: Int, val flush: (Connection, Aggregates) -> Flushed)

    fun connect(): Connection = DriverManager.getConnection(url, user, password)

    /** The tenant of Wow's rows. */
    val tenant: String get() = logTemplates.first()[logColumns.indexOfFirst { it.name == "tenant_id" }] as String

    /** Up to [count] rows of each of Wow's tables as templates, each value of its own type, and its partition count. */
    fun loadTemplates(count: Int) = connect().use { connection ->
        for ((table, columns) in listOf(LOG to logColumns, SNAPSHOT to snapshotColumns)) {
            val missing = columns.map { it.name } - columnsOf(connection, table).toSet()
            check(missing.isEmpty()) { "$table has no columns $missing: Wow's layout changed." }
        }
        partitions = connection.createStatement().use { s ->
            s.executeQuery("SELECT count(*) FROM $PARTITIONS").use { r -> r.next().let { r.getInt(1) } }
        }
        logTemplates = rows(connection, LOG, logColumns, "ORDER BY sequence DESC LIMIT $count")
        snapshotTemplates = rows(connection, SNAPSHOT, snapshotColumns, "LIMIT $count")
        check(logTemplates.isNotEmpty() && snapshotTemplates.isNotEmpty() && partitions > 0) {
            "Wow's tables hold no rows to copy."
        }
    }

    /**
     * The cases: [WOW_TODAY] (today's shape: [logBatch] streams an append, their snapshots [snapshotBatch] a
     * transaction, asynchronously), the log alone, P0 and P1 (the snapshot upsert alone at each of [rows] rows a
     * statement, synchronous and asynchronous, at fillfactor 100 and 80), P2 (the append and its snapshots in one
     * synchronous transaction against today's split) and a point read.
     */
    fun cases(logBatch: Int, snapshotBatch: Int, rows: List<Int>): List<Case> = buildList {
        add(
            Case(RawCase(WOW_TODAY, COMMAND, logBatch, "today: the calibration"), FILLFACTOR) { c, a ->
                wowToday(c, a, logBatch, snapshotBatch)
            }
        )
        add(
            Case(RawCase("log-only", COMMAND, logBatch, "reference: the append alone"), FILLFACTOR) { c, a ->
                append(c, a.take(logBatch))
            }
        )
        for (fillfactor in listOf(FILLFACTOR, LOW_FILLFACTOR)) {
            for (synchronous in listOf(false, true)) {
                for (n in rows) {
                    val name = "snapshot-$n-${if (synchronous) "sync" else "async"}-ff$fillfactor"
                    val today = n == snapshotBatch && !synchronous && fillfactor == FILLFACTOR
                    add(
                        Case(RawCase(name, "row", n, if (today) "P0: today" else "P1"), fillfactor) { c, a ->
                            snapshots(c, a.take(n), synchronous)
                        }
                    )
                }
            }
        }
        add(
            Case(RawCase(P2_SEPARATE, COMMAND, logBatch, "P2: today's split"), FILLFACTOR) { c, a ->
                val commands = a.take(logBatch)
                val appended = append(c, commands)
                appended + snapshots(c, commands, synchronous = false)
            }
        )
        add(
            Case(RawCase(P2_COMBINED, COMMAND, logBatch, "P2: one synchronous transaction"), FILLFACTOR) { c, a ->
                val commands = a.take(logBatch)
                transaction(c) { append(c, commands, bound(synchronous = true)) + upsert(c, commands) }
            }
        )
        add(
            Case(RawCase("point-read", "read", 1, "reference: a snapshot by its key"), FILLFACTOR) { c, a ->
                c.prepareStatement(read).use { s ->
                    s.setString(1, tenant)
                    s.setString(2, a.next().id)
                    s.executeQuery().use { r -> if (r.next()) Flushed(1) else Flushed(0, refused = 1) }
                }
            }
        )
    }

    /**
     * Today's flush of [logBatch] commands: their append, then their snapshots [snapshotBatch] a transaction, async.
     * A command counts once its append and its snapshot were both written.
     */
    fun wowToday(connection: Connection, owned: Aggregates, logBatch: Int, snapshotBatch: Int): Flushed {
        val commands = owned.take(logBatch)
        val appended = append(connection, commands)
        var saved = Flushed(0)
        var from = 0
        for (n in groupsOf(logBatch, snapshotBatch)) {
            saved += snapshots(connection, commands.subList(from, from + n), synchronous = false)
            from += n
        }
        return Flushed(minOf(appended.written, saved.written), appended.refused + saved.refused)
    }

    /**
     * Fresh `raw_*` tables: cloned from Wow's, the snapshot table at [fillfactor], and each of [aggregates] aggregates
     * opened as Wow's runs open them before they measure: its first stream in the log and its snapshot at version 1,
     * so every write goes on from version 2. The opening streams are history no case measures: they take partitions in
     * turn rather than by [Partitioner], and ids of their own. Then analysed, and a checkpoint so each case starts
     * from one.
     */
    fun prepare(fillfactor: Int, aggregates: Int) = connect().use { connection ->
        connection.createStatement().use { s ->
            s.execute("DROP TABLE IF EXISTS raw_log, raw_snapshot, raw_partition")
            s.execute("CREATE TABLE raw_log (LIKE $LOG INCLUDING ALL)")
            s.execute("CREATE TABLE raw_snapshot (LIKE $SNAPSHOT INCLUDING ALL) WITH (fillfactor = $fillfactor)")
            s.execute("CREATE TABLE raw_partition (LIKE $PARTITIONS INCLUDING ALL)")
        }
        opening(connection, "raw_log (partition, sequence, ", logColumns, logTemplates.first(), aggregates) {
            "g % $partitions, g / $partitions + 1, "
        }
        opening(connection, "raw_snapshot (", snapshotColumns, snapshotTemplates.first(), aggregates) { "" }
        connection.createStatement().use { s ->
            s.execute(
                "INSERT INTO raw_partition (partition, sequence) SELECT p, (SELECT count(*) FROM raw_log " +
                    "WHERE raw_log.partition = p) FROM generate_series(0, ${partitions - 1}) p",
            )
            s.execute("ANALYZE raw_log, raw_snapshot, raw_partition")
            s.execute("CHECKPOINT")
        }
    }

    /** [aggregates] rows of [columns] into [into] at version 1, [template]'s values but the key and the ids. */
    private fun opening(
        connection: Connection,
        into: String,
        columns: List<Column>,
        template: List<Any?>,
        aggregates: Int,
        position: () -> String,
    ) {
        val own = mapOf("aggregate_id" to ID_SQL, "version" to "1", "command_id" to UUID_SQL, "request_id" to UUID_SQL)
        val values = columns.joinToString { own[it.name] ?: "?::${it.type}" }
        connection.prepareStatement(
            "INSERT INTO $into${columns.joinToString { it.name }}) SELECT ${position()}$values " +
                "FROM generate_series(0, ${aggregates - 1}) g",
        ).use { s ->
            var at = 1
            for ((i, column) in columns.withIndex()) {
                if (column.name in own) continue
                bindTyped(s, at++, column.type, template[i])
            }
            s.execute()
        }
    }

    /** A checkpoint, so what follows writes the first images of the pages it touches. */
    fun checkpoint() = connect().use { connection -> connection.createStatement().use { it.execute("CHECKPOINT") } }

    /** [commands]' snapshots as one upsert in a transaction of their own, committed [synchronous]ly or not. */
    private fun snapshots(connection: Connection, commands: List<Aggregates.Command>, synchronous: Boolean): Flushed =
        transaction(connection) { upsert(connection, commands, bound(synchronous)) }

    /** Runs [block] in a transaction on [connection]: committed, or rolled back when it fails. */
    private fun <T> transaction(connection: Connection, block: () -> T): T {
        connection.autoCommit = false
        try {
            return block().also { connection.commit() }
        } catch (failure: Exception) {
            runCatching { connection.rollback() }
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    /** [commands]' append, Wow's one statement (prefixed by [prefix], a transaction's bound): its streams written. */
    private fun append(connection: Connection, commands: List<Aggregates.Command>, prefix: String = ""): Flushed =
        connection.prepareStatement(prefix + appendText(commands.size)).use { s ->
            var at = 1
            for (command in commands) {
                s.setInt(at++, Partitioner.partitionOf(ACCOUNT_TYPE.ref(tenant, command.id), partitions))
                val row = logTemplates[Math.floorMod(template.getAndIncrement(), logTemplates.size)]
                logColumns.forEachIndexed { i, column ->
                    val value: Any? = when (column.name) {
                        "aggregate_id" -> command.id
                        "version" -> command.version
                        "command_id", "request_id" -> GlobalIdGenerator.UUID_V7.next()
                        "commit_time" -> System.currentTimeMillis()
                        else -> row[i]
                    }
                    bindTyped(s, at++, column.type, value)
                }
            }
            s.execute()
            val written = lastResult(s) { r ->
                var n = 0
                while (r.next()) if (r.getObject(SEQUENCE) != null) n++
                n
            }
            Flushed(written, commands.size - written)
        }

    /** [commands]' snapshots as Wow's one multi-row upsert, rows in key order: the keys it wrote. */
    private fun upsert(connection: Connection, commands: List<Aggregates.Command>, prefix: String = ""): Flushed =
        connection.prepareStatement(prefix + upsertText(commands.size)).use { s ->
            var at = 1
            val row = snapshotTemplates[Math.floorMod(template.get(), snapshotTemplates.size)]
            for (command in commands.sortedBy { it.id }) {
                snapshotColumns.forEachIndexed { i, column ->
                    val value: Any? = when (column.name) {
                        "aggregate_id" -> command.id
                        "version" -> command.version
                        "last_event_time" -> System.currentTimeMillis()
                        else -> row[i]
                    }
                    bindTyped(s, at++, column.type, value)
                }
            }
            s.execute()
            val written = lastResult(s) { r ->
                var n = 0
                while (r.next()) n++
                n
            }
            Flushed(written, commands.size - written)
        }

    /** The rows of the last result of [statement]'s results (a bound's settings answer first), read by [read]. */
    private fun lastResult(statement: PreparedStatement, read: (ResultSet) -> Int): Int {
        var rows = 0
        while (true) {
            statement.resultSet?.use { rows = read(it) }
            if (!statement.moreResults && statement.updateCount == -1) return rows
        }
    }

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
            val columns = logColumns.joinToString { it.name }
            val values = (0 until streams).joinToString(", ") { ord ->
                "($ord, 0, ?::integer, " + logColumns.joinToString { "?::${it.type}" } + ")"
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
            ), appended_0 AS (INSERT INTO raw_log (partition, sequence, $columns) SELECT fits.part, bump.sequence - fits.total + fits.rank, ${logColumns.joinToString { "fits.${it.name}" }} FROM fits JOIN bump ON bump.partition = fits.part WHERE fits.branch = 0 ORDER BY fits.tenant_id, fits.request_id RETURNING sequence)
            SELECT current.ord, current.version, bump.sequence - fits.total + fits.rank AS sequence
            FROM current LEFT JOIN fits ON fits.ord = current.ord LEFT JOIN bump ON bump.partition = fits.part
            ORDER BY current.ord
            """.trimIndent()
        }
    }

    /** Wow's save of [rows] snapshots (PostgresSnapshotTables.saveRows) on the clone: returning the keys it wrote. */
    fun upsertText(rows: Int): String = synchronized(upserts) {
        upserts.getOrPut(rows) {
            val row = "(" + snapshotColumns.joinToString { if (it.type == JSONB) "?::jsonb" else "?" } + ")"
            val updates = snapshotColumns.filter { it.name != "tenant_id" && it.name != "aggregate_id" }
                .joinToString(",\n    ") { "${it.name} = excluded.${it.name}" }
            """
            INSERT INTO raw_snapshot AS stored (${snapshotColumns.joinToString { it.name }})
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
    val read: String get() = "SELECT ${snapshotColumns.joinToString { it.name }} FROM raw_snapshot " +
        "WHERE tenant_id = ? AND aggregate_id = ?"

    /**
     * PostgreSQL's cumulative counters: WAL bytes, records, full-page images, syncs and their time, commits, and the
     * clone's snapshot table's updates and HOT updates.
     */
    fun counters(): Map<String, Double> = connect().use { connection ->
        connection.createStatement().use { s ->
            s.executeQuery(
                "SELECT w.wal_bytes, w.wal_records, w.wal_fpi, w.wal_sync, w.wal_sync_time, d.xact_commit, " +
                    "coalesce(t.n_tup_upd, 0), coalesce(t.n_tup_hot_upd, 0) " +
                    "FROM pg_stat_wal w CROSS JOIN pg_stat_database d " +
                    "LEFT JOIN pg_stat_user_tables t " +
                    "ON t.relname = 'raw_snapshot' AND t.schemaname = current_schema() " +
                    "WHERE d.datname = current_database()",
            ).use { r ->
                r.next()
                mapOf(
                    "walBytes" to r.getDouble(1),
                    "walRecords" to r.getDouble(2),
                    "walFpi" to r.getDouble(3),
                    "walSyncs" to r.getDouble(4),
                    "walSyncMs" to r.getDouble(5),
                    "commits" to r.getDouble(6),
                    "snapshotUpdates" to r.getDouble(7),
                    "snapshotHotUpdates" to r.getDouble(8),
                )
            }
        }
    }

    /** Each of Wow's tables' rows inserted, updated (HOT among them) and deleted: where a calibration's gap is. */
    fun tables(): Map<String, Double> = connect().use { connection ->
        connection.createStatement().use { s ->
            s.executeQuery(
                "SELECT relname, n_tup_ins, n_tup_upd, n_tup_hot_upd, n_tup_del FROM pg_stat_user_tables " +
                    "WHERE schemaname = current_schema() AND relname LIKE 'wow\\_%'",
            ).use { r ->
                buildMap {
                    while (r.next()) {
                        val table = r.getString(1)
                        put("$table inserts", r.getDouble(2))
                        put("$table updates", r.getDouble(3))
                        put("$table hot updates", r.getDouble(4))
                        put("$table deletes", r.getDouble(5))
                    }
                }
            }
        }
    }

    /** The share of the snapshot table's updates that were HOT, from the counters' growth. */
    fun ratios(growth: Map<String, Double>): Map<String, Double> {
        val updates = growth["snapshotUpdates"] ?: 0.0
        return if (updates > 0) mapOf("hotShare" to (growth["snapshotHotUpdates"] ?: 0.0) / updates) else emptyMap()
    }

    /** The server's version and the settings a ceiling is quoted with. */
    fun settings(): Map<String, Any?> = connect().use { connection ->
        connection.createStatement().use { s ->
            (listOf("server_version") + SETTINGS).associateWith<String, Any?> { setting ->
                s.executeQuery("SHOW $setting").use { r ->
                    r.next()
                    r.getString(1)
                }
            } + ("partitions" to partitions)
        }
    }

    private val appends = HashMap<Int, String>()
    private val upserts = HashMap<Int, String>()

    private fun columnsOf(connection: Connection, table: String): List<String> =
        connection.prepareStatement(
            "SELECT column_name FROM information_schema.columns " +
                "WHERE table_schema = current_schema() AND table_name = ? ORDER BY ordinal_position",
        ).use { s ->
            s.setString(1, table)
            s.executeQuery().use { r -> buildList { while (r.next()) add(r.getString(1)) } }
        }.also { check(it.isNotEmpty()) { "No table $table: the domain's run did not create it." } }

    /** Up to the rows [tail] selects of [table]'s [columns], each value of its own type (jsonb as its text). */
    private fun rows(connection: Connection, table: String, columns: List<Column>, tail: String): List<List<Any?>> =
        connection.createStatement().use { s ->
            val select = columns.joinToString { if (it.type == JSONB) "${it.name}::text" else it.name }
            s.executeQuery("SELECT $select FROM $table $tail").use { r ->
                buildList {
                    while (r.next()) {
                        add(
                            columns.mapIndexed { i, column ->
                                if (column.type == BYTEA) r.getBytes(i + 1) else r.getObject(i + 1)
                            },
                        )
                    }
                }
            }
        }

    companion object {
        const val LOG = "wow_log__transfer__account"
        const val SNAPSHOT = "wow_snapshot__transfer__account"
        const val PARTITIONS = "wow_log__transfer__partition"
        const val WOW_TODAY = "wow-today"
        const val P2_SEPARATE = "p2-separate"
        const val P2_COMBINED = "p2-combined"
        private const val COMMAND = "command"
        private const val SEQUENCE = "sequence"
        private const val FILLFACTOR = 100
        private const val LOW_FILLFACTOR = 80

        /** Wow's server bounds at its default 30-second operation timeout: one second under it, and twice it. */
        private const val SERVER_MILLIS = 29_000
        private const val IDLE_MILLIS = 60_000

        private const val JSONB = "jsonb"

        /** [Aggregates.idOf] in SQL, of the number `g`. */
        private const val ID_SQL = "'${Aggregates.ID_PREFIX}' || lpad(g::text, ${Aggregates.ID_DIGITS}, '0')"
        private const val UUID_SQL = "gen_random_uuid()::text"
        private const val BYTEA = "bytea"

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

        /** The durability and storage settings a ceiling is quoted with. */
        val SETTINGS = listOf(
            "synchronous_commit",
            "fsync",
            "full_page_writes",
            "wal_compression",
            "wal_level",
            "shared_buffers",
            "max_wal_size",
            "checkpoint_timeout",
        )

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
}

/** Two flushes' units, added. */
operator fun Flushed.plus(other: Flushed): Flushed = Flushed(written + other.written, refused + other.refused)
