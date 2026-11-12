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
import java.util.UUID

/**
 * The raw run on PostgreSQL: Wow's tables cloned (`LIKE … INCLUDING ALL`: its columns, keys and indexes) as `raw_log`,
 * `raw_snapshot` and `raw_partition`, written with rows of the domain's own commits as templates, by the statements
 * [append], [upsert] and [read] mirror of Wow's: the append one autocommitted statement that reads each stream's
 * version, locks and bumps its partitions' counters and inserts; the save Wow's version-guarded upsert.
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

    /** A column: its name and the type a parameter of it is cast to. */
    data class Column(val name: String, val type: String)

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
     * Fresh `raw_*` tables: cloned from Wow's, the snapshot table at [fillfactor], its [aggregates] snapshots written at
     * version 1 (Wow's runs update existing snapshots), and a checkpoint so every step starts from one.
     */
    fun prepare(fillfactor: Int, aggregates: Int) = connect().use { connection ->
        connection.createStatement().use { s ->
            s.execute("DROP TABLE IF EXISTS raw_log, raw_snapshot, raw_partition")
            s.execute("CREATE TABLE raw_log (LIKE $LOG INCLUDING ALL)")
            s.execute("CREATE TABLE raw_snapshot (LIKE $SNAPSHOT INCLUDING ALL) WITH (fillfactor = $fillfactor)")
            s.execute("CREATE TABLE raw_partition (LIKE $PARTITIONS INCLUDING ALL)")
            s.execute(
                "INSERT INTO raw_partition (partition, sequence) SELECT g, 0 FROM generate_series(0, ${partitions - 1}) g"
            )
        }
        val template = snapshotTemplates.first()
        val values = snapshotColumns.mapIndexed { i, column ->
            when (column.name) {
                "aggregate_id" -> "'raw-' || g"
                "version" -> "1"
                else -> template[i]?.let { "${literal(it)}::${column.type}" } ?: "NULL"
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

    /** One connection's worker of [shape]: its share of the aggregates ([owned]), [logBatch] commands a flush. */
    fun worker(shape: Shape, owned: Aggregates, logBatch: Int, snapshotBatch: Int): RawWorker {
        val connection = connect()
        if (shape.oneTransaction) connection.autoCommit = false
        val groups = snapshotGroups(logBatch, snapshotBatch, shape.mergesSnapshots)
        return object : RawWorker {
            private var template = 0

            override fun flush(): Int {
                if (shape == Shape.POINT_READ) {
                    connection.prepareStatement(read).use { s ->
                        s.setString(1, snapshotTemplates.first()[0])
                        s.setString(2, owned.next().id)
                        s.executeQuery().use { it.next() }
                    }
                    return 1
                }
                val commands = List(logBatch) { owned.next().also { it.version++ } }
                connection.prepareStatement(append(logBatch)).use { s ->
                    var at = 1
                    for (command in commands) {
                        s.setInt(at++, Math.floorMod(command.id.hashCode(), partitions))
                        at = bindLog(s, at, command, logTemplates[template++ % logTemplates.size])
                    }
                    s.execute()
                }
                if (shape.writesSnapshots) {
                    var from = 0
                    for (rows in groups) {
                        connection.prepareStatement(upsert(rows)).use { s ->
                            var at = 1
                            for (command in commands.subList(from, from + rows)) {
                                at = bindSnapshot(s, at, command, snapshotTemplates[template % snapshotTemplates.size])
                            }
                            s.execute()
                        }
                        from += rows
                    }
                }
                if (shape.oneTransaction) connection.commit()
                return logBatch
            }

            override fun close() = connection.close()
        }
    }

    private fun bindLog(s: java.sql.PreparedStatement, start: Int, command: Aggregates.Command, row: List<String?>): Int {
        var at = start
        logColumns.forEachIndexed { i, column ->
            s.setString(
                at++,
                when (column.name) {
                    "aggregate_id" -> command.id
                    "version" -> command.version.toString()
                    "command_id", "request_id" -> UUID.randomUUID().toString()
                    "commit_time" -> System.currentTimeMillis().toString()
                    else -> row[i]
                },
            )
        }
        return at
    }

    private fun bindSnapshot(
        s: java.sql.PreparedStatement,
        start: Int,
        command: Aggregates.Command,
        row: List<String?>,
    ): Int {
        var at = start
        snapshotColumns.forEachIndexed { i, column ->
            s.setString(
                at++,
                when (column.name) {
                    "aggregate_id" -> command.id
                    "version" -> command.version.toString()
                    "last_event_time" -> System.currentTimeMillis().toString()
                    else -> row[i]
                },
            )
        }
        return at
    }

    /**
     * The append of [streams] streams as one autocommitted statement, as Wow's group commit across partitions: each
     * stream's current version read by the version key, the partitions' counters locked in order and bumped by their
     * streams, the rows inserted at their sequences.
     */
    fun append(streams: Int): String = appends.getOrPut(streams) {
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
            SELECT partition FROM raw_partition WHERE partition IN (SELECT part FROM input) ORDER BY partition FOR UPDATE
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

    /** The save of [rows] snapshots as one autocommitted statement: Wow's version-guarded upsert. */
    fun upsert(rows: Int): String = upserts.getOrPut(rows) {
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

    /** A snapshot read by its key. */
    val read: String get() = "SELECT ${snapshotColumns.joinToString { it.name }} FROM raw_snapshot " +
        "WHERE tenant_id = ? AND aggregate_id = ?"

    /** PostgreSQL's cumulative counters: WAL bytes, records, full-page images, syncs and their time, and commits. */
    fun counters(): Map<String, Double> = connect().use { connection ->
        connection.createStatement().use { s ->
            s.executeQuery(
                "SELECT w.wal_bytes, w.wal_records, w.wal_fpi, w.wal_sync, w.wal_sync_time, d.xact_commit " +
                    "FROM pg_stat_wal w, pg_stat_database d WHERE d.datname = current_database()",
            ).use { r ->
                r.next()
                mapOf(
                    "walBytes" to r.getDouble(1),
                    "walRecords" to r.getDouble(2),
                    "walFpi" to r.getDouble(3),
                    "walSyncs" to r.getDouble(4),
                    "walSyncMs" to r.getDouble(5),
                    "commits" to r.getDouble(6),
                )
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

        /** The columns Wow's save writes, in its order. */
        val SNAPSHOT_COLUMNS = listOf(
            "tenant_id", "aggregate_id", "version", "schema_fingerprint", "state", "owner_id", "space_id", "deleted",
            "first_event_time", "last_event_time", "access", "operator", "first_operator", "schedules", "state_revision",
        )

        /** Wow's save guard: a higher version, of the same or a higher state revision. */
        const val SAVE_GUARD = "excluded.version > stored.version AND excluded.state_revision >= stored.state_revision"
    }
}

/**
 * The aggregates one worker writes: every [of]-th of [count], by [index], each with its version, taken in turn so
 * a flush holds distinct aggregates and each one's versions rise by one.
 */
class Aggregates(count: Int, index: Int, of: Int) {
    class Command(val id: String, var version: Int)

    private val owned = (index until count step of).map { Command("raw-$it", 1) }
    private var next = 0

    init {
        require(owned.isNotEmpty()) { "Worker $index of $of owns none of $count aggregates." }
    }

    fun next(): Command = owned[next++ % owned.size]
}
