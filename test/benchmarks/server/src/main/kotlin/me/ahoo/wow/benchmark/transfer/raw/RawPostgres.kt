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

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The raw run on PostgreSQL: Wow's tables cloned (`LIKE … INCLUDING ALL`: its columns, keys and indexes) as `raw_log`,
 * `raw_snapshot` and `raw_partition`, written with rows of the domain's own commits as templates, by statements that
 * mirror Wow's: the append one autocommitted statement that reads each stream's version, locks and bumps its
 * partitions' counters and inserts ([appendText]); the save Wow's version-guarded multi-row upsert ([upsertText]), one
 * transaction a flush, asynchronous when the store covers the log and the checkpoints.
 */
class RawPostgres(
    private val url: String,
    private val user: String,
    private val password: String,
    private val partitions: Int,
) {
    private lateinit var logColumns: List<Column>
    private lateinit var snapshotColumns: List<Column>
    private lateinit var logTemplates: List<List<String?>>
    private lateinit var snapshotTemplates: List<List<String?>>
    private val template = AtomicInteger()

    /** A column: its name and the type a parameter of it is cast to. */
    data class Column(val name: String, val type: String)

    /** A case of this store, the snapshot table's [fillfactor] it runs at, and its flush on a connection. */
    class Case(val case: RawCase, val fillfactor: Int, val flush: (Connection, Aggregates) -> Int)

    fun connect(): Connection = DriverManager.getConnection(url, user, password)

    /** Reads Wow's tables' columns and up to [count] rows of each as templates, as text. */
    fun loadTemplates(count: Int) = connect().use { connection ->
        logColumns = columnsOf(connection, LOG).filter { it.name != "partition" && it.name != "sequence" }
        snapshotColumns = columnsOf(connection, SNAPSHOT).filter { it.name in SNAPSHOT_COLUMNS }
            .sortedBy { SNAPSHOT_COLUMNS.indexOf(it.name) }
        logTemplates = rows(connection, LOG, logColumns, "ORDER BY sequence DESC LIMIT $count")
        snapshotTemplates = rows(connection, SNAPSHOT, snapshotColumns, "LIMIT $count")
        check(logTemplates.isNotEmpty() && snapshotTemplates.isNotEmpty()) { "Wow's tables hold no rows to copy." }
    }

    /**
     * The cases: [WOW_TODAY] (today's shape, the calibration), the log alone, P0 and P1 (the snapshot upsert alone at
     * each of [rows] rows a statement, synchronous and asynchronous, at fillfactor 100 and 80), P2 (the append and its
     * snapshots in one synchronous transaction against today's split) and a point read.
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
                append(c, commands)
                snapshots(c, commands, synchronous = false)
            }
        )
        add(
            Case(RawCase(P2_COMBINED, COMMAND, logBatch, "P2: one synchronous transaction"), FILLFACTOR) { c, a ->
                val commands = a.take(logBatch)
                c.autoCommit = false
                append(c, commands)
                upsert(c, commands)
                c.commit()
                c.autoCommit = true
                logBatch
            }
        )
        add(
            Case(RawCase("point-read", "read", 1, "reference: a snapshot by its key"), FILLFACTOR) { c, a ->
                c.prepareStatement(read).use { s ->
                    s.setString(1, snapshotTemplates.first()[0])
                    s.setString(2, a.next().id)
                    s.executeQuery().use { it.next() }
                }
                1
            }
        )
    }

    /** Today's flush of [logBatch] commands: their append, then their snapshots [snapshotBatch] a transaction, async. */
    private fun wowToday(connection: Connection, owned: Aggregates, logBatch: Int, snapshotBatch: Int): Int {
        val commands = owned.take(logBatch)
        append(connection, commands)
        var from = 0
        for (n in groupsOf(logBatch, snapshotBatch)) {
            snapshots(connection, commands.subList(from, from + n), synchronous = false)
            from += n
        }
        return logBatch
    }

    /**
     * Fresh `raw_*` tables: cloned from Wow's, the snapshot table at [fillfactor], its [aggregates] snapshots written at
     * version 1 (Wow's runs update existing snapshots), analysed, and a checkpoint so every case starts from one.
     */
    fun prepare(fillfactor: Int, aggregates: Int) = connect().use { connection ->
        connection.createStatement().use { s ->
            s.execute("DROP TABLE IF EXISTS raw_log, raw_snapshot, raw_partition")
            s.execute("CREATE TABLE raw_log (LIKE $LOG INCLUDING ALL)")
            s.execute("CREATE TABLE raw_snapshot (LIKE $SNAPSHOT INCLUDING ALL) WITH (fillfactor = $fillfactor)")
            s.execute("CREATE TABLE raw_partition (LIKE $PARTITIONS INCLUDING ALL)")
            s.execute(
                "INSERT INTO raw_partition (partition, sequence) SELECT g, 0 FROM generate_series(0, ${partitions - 1}) g",
            )
        }
        val first = snapshotTemplates.first()
        val values = snapshotColumns.mapIndexed { i, column ->
            when (column.name) {
                "aggregate_id" -> "'raw-' || g"
                "version" -> "1"
                else -> first[i]?.let { "${literal(it)}::${column.type}" } ?: "NULL"
            }
        }
        connection.createStatement().use { s ->
            s.execute(
                "INSERT INTO raw_snapshot (${snapshotColumns.joinToString { it.name }}) " +
                    "SELECT ${values.joinToString()} FROM generate_series(0, ${aggregates - 1}) g",
            )
            s.execute("ANALYZE raw_log, raw_snapshot, raw_partition")
            s.execute("CHECKPOINT")
        }
    }

    /** [commands]' snapshots as one upsert in a transaction of their own, committed [synchronous]ly or not. */
    private fun snapshots(connection: Connection, commands: List<Aggregates.Command>, synchronous: Boolean): Int {
        connection.autoCommit = false
        if (!synchronous) connection.createStatement().use { it.execute("SET LOCAL synchronous_commit TO off") }
        upsert(connection, commands)
        connection.commit()
        connection.autoCommit = true
        return commands.size
    }

    /** [commands]' append, one statement (autocommitted unless a transaction is open). */
    private fun append(connection: Connection, commands: List<Aggregates.Command>): Int {
        connection.prepareStatement(appendText(commands.size)).use { s ->
            var at = 1
            for (command in commands) {
                s.setInt(at++, Math.floorMod(command.id.hashCode(), partitions))
                val row = logTemplates[Math.floorMod(template.getAndIncrement(), logTemplates.size)]
                at = bind(s, at, logColumns, row) { name ->
                    when (name) {
                        "aggregate_id" -> command.id
                        "version" -> command.version.toString()
                        "command_id", "request_id" -> UUID.randomUUID().toString()
                        "commit_time" -> System.currentTimeMillis().toString()
                        else -> null
                    }
                }
            }
            s.execute()
        }
        return commands.size
    }

    /** [commands]' snapshots as Wow's one multi-row upsert. */
    private fun upsert(connection: Connection, commands: List<Aggregates.Command>) {
        connection.prepareStatement(upsertText(commands.size)).use { s ->
            var at = 1
            for (command in commands) {
                val row = snapshotTemplates[Math.floorMod(template.get(), snapshotTemplates.size)]
                at = bind(s, at, snapshotColumns, row) { name ->
                    when (name) {
                        "aggregate_id" -> command.id
                        "version" -> command.version.toString()
                        "last_event_time" -> System.currentTimeMillis().toString()
                        else -> null
                    }
                }
            }
            s.execute()
        }
    }

    private fun bind(
        s: PreparedStatement,
        start: Int,
        columns: List<Column>,
        row: List<String?>,
        value: (String) -> String?,
    ): Int {
        var at = start
        columns.forEachIndexed { i, column -> s.setString(at++, value(column.name) ?: row[i]) }
        return at
    }

    /**
     * The append of [streams] streams as one statement, as Wow's group commit across partitions: each stream's current
     * version read by the version key, the partitions' counters locked in order and bumped by their streams, the rows
     * inserted at their sequences.
     */
    fun appendText(streams: Int): String = synchronized(appends) {
        appends.getOrPut(streams) {
            val names = logColumns.joinToString { it.name }
            val row = "(?::integer, " + logColumns.joinToString { "?::${it.type}" } + ")"
            """
            WITH input (part, $names) AS (VALUES ${List(streams) { row }.joinToString(", ")}),
            current AS (
                SELECT (SELECT max(log.version) FROM raw_log AS log
                    WHERE log.aggregate_id = input.aggregate_id AND log.tenant_id = input.tenant_id) AS version
                FROM input
            ),
            locked AS (
                SELECT partition FROM raw_partition WHERE partition IN (SELECT part FROM input)
                ORDER BY partition FOR UPDATE
            ),
            counts AS (SELECT part, count(*) AS n FROM input GROUP BY part),
            bump AS (
                UPDATE raw_partition AS p SET sequence = p.sequence + counts.n FROM counts
                WHERE p.partition = counts.part AND (SELECT count(*) FROM locked) >= 0
                RETURNING p.partition, p.sequence
            ),
            fits AS (
                SELECT input.*, row_number() OVER (PARTITION BY input.part ORDER BY input.request_id) AS rank,
                    count(*) OVER (PARTITION BY input.part) AS total
                FROM input
            )
            INSERT INTO raw_log (partition, sequence, $names)
            SELECT fits.part, bump.sequence - fits.total + fits.rank, ${logColumns.joinToString { "fits.${it.name}" }}
            FROM fits JOIN bump ON bump.partition = fits.part
            WHERE (SELECT count(version) FROM current) >= 0
            """.trimIndent()
        }
    }

    /** The save of [rows] snapshots as one statement: Wow's version-guarded upsert. */
    fun upsertText(rows: Int): String = synchronized(upserts) {
        upserts.getOrPut(rows) {
            val row = "(" + snapshotColumns.joinToString { "?::${it.type}" } + ")"
            val updates = snapshotColumns.filter { it.name != "tenant_id" && it.name != "aggregate_id" }
                .joinToString(",\n    ") { "${it.name} = excluded.${it.name}" }
            """
            INSERT INTO raw_snapshot AS stored (${snapshotColumns.joinToString { it.name }})
            VALUES ${List(rows) { row }.joinToString(", ")}
            ON CONFLICT (tenant_id, aggregate_id) DO UPDATE SET
                $updates,
                stale_version = CASE WHEN stored.stale_version > excluded.version THEN stored.stale_version END
            WHERE $SAVE_GUARD
            """.trimIndent()
        }
    }

    /** A checkpoint, so what follows writes its pages' first images as a step after [prepare] does. */
    fun checkpoint() = connect().use { connection -> connection.createStatement().use { it.execute("CHECKPOINT") } }

    /** A snapshot read by its key. */
    val read: String get() = "SELECT ${snapshotColumns.joinToString { it.name }} FROM raw_snapshot " +
        "WHERE tenant_id = ? AND aggregate_id = ?"

    /**
     * PostgreSQL's cumulative counters: WAL bytes, records, full-page images, syncs and their time, commits, and the
     * clone's and Wow's own snapshot tables' updates and HOT updates.
     */
    fun counters(): Map<String, Double> = connect().use { connection ->
        connection.createStatement().use { s ->
            s.executeQuery(
                "SELECT w.wal_bytes, w.wal_records, w.wal_fpi, w.wal_sync, w.wal_sync_time, d.xact_commit, " +
                    "coalesce(t.n_tup_upd, 0), coalesce(t.n_tup_hot_upd, 0), " +
                    "coalesce(o.n_tup_upd, 0), coalesce(o.n_tup_hot_upd, 0) " +
                    "FROM pg_stat_wal w CROSS JOIN pg_stat_database d " +
                    "LEFT JOIN pg_stat_user_tables t ON t.relname = 'raw_snapshot' " +
                    "LEFT JOIN pg_stat_user_tables o ON o.relname = '$SNAPSHOT' " +
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
                    "wowSnapshotUpdates" to r.getDouble(9),
                    "wowSnapshotHotUpdates" to r.getDouble(10),
                )
            }
        }
    }

    /** Each of Wow's tables' rows inserted, updated (HOT among them) and deleted: where a calibration's gap is. */
    fun tables(): Map<String, Double> = connect().use { connection ->
        connection.createStatement().use { s ->
            s.executeQuery(
                "SELECT relname, n_tup_ins, n_tup_upd, n_tup_hot_upd, n_tup_del FROM pg_stat_user_tables " +
                    "WHERE relname LIKE 'wow\\_%'",
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
            (listOf("server_version") + SETTINGS).associateWith { setting ->
                s.executeQuery("SHOW $setting").use { r ->
                    r.next()
                    r.getString(1)
                }
            }
        }
    }

    private val appends = HashMap<Int, String>()
    private val upserts = HashMap<Int, String>()

    private fun columnsOf(connection: Connection, table: String): List<Column> =
        connection.prepareStatement(
            "SELECT column_name, udt_name FROM information_schema.columns WHERE table_name = ? ORDER BY ordinal_position",
        ).use { s ->
            s.setString(1, table)
            s.executeQuery().use { r ->
                buildList { while (r.next()) add(Column(r.getString(1), r.getString(2))) }
            }
        }.also { check(it.isNotEmpty()) { "No table $table: the domain's run did not create it." } }

    private fun rows(connection: Connection, table: String, columns: List<Column>, tail: String): List<List<String?>> =
        connection.createStatement().use { s ->
            s.executeQuery("SELECT ${columns.joinToString { "${it.name}::text" }} FROM $table $tail").use { r ->
                buildList { while (r.next()) add(List(columns.size) { r.getString(it + 1) }) }
            }
        }

    private fun literal(text: String): String = "'" + text.replace("'", "''") + "'"

    companion object {
        const val LOG = "wow_log__transfer__account"
        const val SNAPSHOT = "wow_snapshot__transfer__account"
        const val PARTITIONS = "wow_log__transfer__partition"
        const val WOW_TODAY = "wow-today"
        const val P2_SEPARATE = "p2-separate"
        const val P2_COMBINED = "p2-combined"
        private const val COMMAND = "command"
        private const val FILLFACTOR = 100
        private const val LOW_FILLFACTOR = 80

        /** The columns Wow's save writes, in its order. */
        val SNAPSHOT_COLUMNS = listOf(
            "tenant_id", "aggregate_id", "version", "schema_fingerprint", "state", "owner_id", "space_id", "deleted",
            "first_event_time", "last_event_time", "access", "operator", "first_operator", "schedules", "state_revision",
        )

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
    }
}

/**
 * The aggregates one worker writes: every [of]-th of [count], by [index], each with its version, taken in turn so a
 * flush holds distinct aggregates and each one's versions rise by one.
 */
class Aggregates(count: Int, index: Int, of: Int) {
    class Command(val id: String, var version: Int)

    private val owned = (index until count step of).map { Command("raw-$it", 1) }
    private var next = 0

    init {
        require(owned.isNotEmpty()) { "Worker $index of $of owns none of $count aggregates." }
    }

    fun next(): Command = owned[next++ % owned.size]

    /** The next [n] aggregates, each one version higher. */
    fun take(n: Int): List<Command> = List(n) { next().also { it.version++ } }
}
