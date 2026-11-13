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
import me.ahoo.wow.benchmark.transfer.raw.PostgresTexts.BYTEA
import me.ahoo.wow.benchmark.transfer.raw.PostgresTexts.Column
import me.ahoo.wow.benchmark.transfer.raw.PostgresTexts.ID_SQL
import me.ahoo.wow.benchmark.transfer.raw.PostgresTexts.JSONB
import me.ahoo.wow.benchmark.transfer.raw.PostgresTexts.LOG_COLUMNS
import me.ahoo.wow.benchmark.transfer.raw.PostgresTexts.SNAPSHOT_COLUMNS
import me.ahoo.wow.benchmark.transfer.raw.PostgresTexts.UUID_SQL
import me.ahoo.wow.benchmark.transfer.raw.PostgresTexts.appendOneText
import me.ahoo.wow.benchmark.transfer.raw.PostgresTexts.appendText
import me.ahoo.wow.benchmark.transfer.raw.PostgresTexts.bindTyped
import me.ahoo.wow.benchmark.transfer.raw.PostgresTexts.bound
import me.ahoo.wow.benchmark.transfer.raw.PostgresTexts.read
import me.ahoo.wow.benchmark.transfer.raw.PostgresTexts.upsertText
import me.ahoo.wow.spi.id.GlobalIdGenerator
import me.ahoo.wow.spi.log.Partitioner
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.atomic.AtomicInteger

/**
 * The raw run on PostgreSQL: Wow's tables cloned (`LIKE … INCLUDING ALL`: its columns, keys and indexes) as
 * `raw_log`, `raw_snapshot` and `raw_partition`, written with rows of the domain's own commits as templates, by Wow's
 * own statement texts and parameter types:
 *
 * - the append, Wow's group commit for one table, across partitions ([appendText]) or, when the group's streams are
 *   of one partition, its one-partition form ([appendOneText]), as Wow's appender chooses: each stream's version read
 *   by the version key, linked to the streams before it, the ones that fit taken, the partitions' counters locked in
 *   order and bumped, the rows inserted in key order, each stream's sequence returned; one autocommitted statement;
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

    /**
     * A case of this store, the snapshot table's [fillfactor] it runs at, and its flush on a connection; or, with a
     * [shape], Wow's own write path ([shapeOf]) its callers submit to.
     */
    class Case(
        val case: RawCase,
        val fillfactor: Int,
        val shape: WowShape.Options? = null,
        val flush: (Connection, Aggregates) -> Flushed,
    )

    fun connect(): Connection = DriverManager.getConnection(url, user, password)

    private val stats = PostgresCounters(::connect)

    /** PostgreSQL's cumulative counters (PostgresCounters). */
    fun counters(): Map<String, Double> = stats.counters()

    /** Each of Wow's tables' rows written (PostgresCounters). */
    fun tables(): Map<String, Double> = stats.tables()

    /**
     * [case]'s WAL reader ([records]): the calibration's alone, which its checks read; over a fast step's window the WAL
     * is millions of records, and reading them for nothing made each such step minutes long.
     */
    fun recordsOf(case: RawCase): (Map<String, Double>, Map<String, Double>) -> Map<String, Double> =
        if (case.name == CALIBRATION) ::records else { _, _ -> emptyMap() }

    /** Watches the store's horizon over a window (PostgresCounters.watch). */
    fun watch(): () -> Map<String, Double> = stats.watch()

    /** The WAL between two counters' reads by record type and table (PostgresCounters). */
    fun records(before: Map<String, Double>, after: Map<String, Double>): Map<String, Double> =
        stats.records(before, after)

    /** The snapshot table's HOT share, from the counters' growth. */
    fun ratios(growth: Map<String, Double>): Map<String, Double> = stats.ratios(growth)

    /** The server's version and settings, and Wow's partition count. */
    fun settings(): Map<String, Any?> = stats.settings() + ("partitions" to partitions)

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
        // The measured deposits' streams (version 3 on: an account's opening is 1, its warm-up deposit 2), the latest first.
        logTemplates = rows(connection, LOG, logColumns, "WHERE version >= 3 ORDER BY commit_time DESC LIMIT $count")
        snapshotTemplates = rows(connection, SNAPSHOT, snapshotColumns, "LIMIT $count")
        check(logTemplates.isNotEmpty() && snapshotTemplates.isNotEmpty() && partitions > 0) {
            "Wow's tables hold no rows to copy."
        }
    }

    /**
     * The cases: [WOW_TODAY] (today's shape: [logBatch] streams an append, the snapshots behind the appends
     * [snapshotBatch] a transaction, asynchronously, across appends), the log alone, P0 and P1 (the snapshot upsert
     * alone at each of [rows] rows a statement, synchronous and asynchronous, at fillfactor 100 and 80), P2 (the append
     * and its snapshots in one synchronous transaction against today's split), Wow's own peak shape ([WowShape.cases])
     * and a point read.
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
                both(append(c, commands), snapshots(c, commands, synchronous = false))
            }
        )
        add(
            Case(RawCase(P2_COMBINED, COMMAND, logBatch, "P2: one synchronous transaction"), FILLFACTOR) { c, a ->
                val commands = a.take(logBatch)
                transaction(c) { both(append(c, commands, bound(synchronous = true)), upsert(c, commands)) }
            }
        )
        for ((case, options) in WowShape.cases()) {
            add(Case(case, FILLFACTOR, options) { _, _ -> error("A shape's callers submit to it.") })
        }
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
     * Today's flush of [logBatch] commands: their append, then the worker's snapshots behind it [snapshotBatch] a
     * transaction, async (savedBehind). A command counts once its snapshot was written.
     */
    fun wowToday(connection: Connection, owned: Aggregates, logBatch: Int, snapshotBatch: Int): Flushed =
        savedBehind(owned, logBatch, snapshotBatch, { append(connection, it) }) {
            snapshots(connection, it, synchronous = false)
        }

    /** A command counts once, when its append and its snapshot were both written: the fewer of the two. */
    private fun both(appended: Flushed, saved: Flushed) =
        Flushed(minOf(appended.written, saved.written), appended.refused + saved.refused)

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
    internal fun <T> transaction(connection: Connection, block: () -> T): T {
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

    /** Wow's own write path on this store ([options]): its appenders' and writers' connections, their calls. */
    fun shapeOf(options: WowShape.Options): WowShape<Connection> = WowShape(
        partitions,
        { Partitioner.partitionOf(ACCOUNT_TYPE.ref(tenant, it.id), partitions) },
        options,
        ::connect,
        { connection, commands -> appendEach(connection, commands) },
        { connection, commands ->
            transaction(
                connection
            ) { upsertEach(connection, commands, bound(synchronous = false)) }
        },
    )

    /** [commands]' append, Wow's one statement (prefixed by [prefix], a transaction's bound): its streams written. */
    private fun append(connection: Connection, commands: List<Aggregates.Command>, prefix: String = ""): Flushed =
        counted(appendEach(connection, commands, prefix))

    /** [commands]' append, as [append]: which of them it wrote, in their order. */
    private fun appendEach(
        connection: Connection,
        commands: List<Aggregates.Command>,
        prefix: String = ""
    ): BooleanArray {
        val parts = commands.map { Partitioner.partitionOf(ACCOUNT_TYPE.ref(tenant, it.id), partitions) }
        // As Wow's appender: the one-partition form when the group's streams are of one partition, else across.
        val across = parts.distinct().size > 1
        val text = if (across) appendText(commands.size) else appendOneText(parts[0], commands.size)
        return connection.prepareStatement(prefix + text).use { s ->
            var at = 1
            for ((index, command) in commands.withIndex()) {
                if (across) s.setInt(at++, parts[index])
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
            lastResult(s, BooleanArray(commands.size)) { r ->
                // Wow's final select: each stream's ord, its version, and its sequence when it was written.
                BooleanArray(commands.size).also { written ->
                    while (r.next()) written[r.getInt(1)] = r.getObject(SEQUENCE) != null
                }
            }
        }
    }

    /** [commands]' snapshots as Wow's one multi-row upsert, rows in key order: the keys it wrote. */
    private fun upsert(connection: Connection, commands: List<Aggregates.Command>, prefix: String = ""): Flushed =
        counted(upsertEach(connection, commands, prefix))

    /** [commands]' snapshots, as [upsert]: which of them it wrote, in their order. */
    private fun upsertEach(
        connection: Connection,
        commands: List<Aggregates.Command>,
        prefix: String = ""
    ): BooleanArray =
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
            val keys = lastResult(s, emptySet()) { r -> buildSet { while (r.next()) add(r.getString(2)) } }
            BooleanArray(commands.size) { commands[it].id in keys }
        }

    private fun counted(written: BooleanArray): Flushed = written.count { it }.let { Flushed(it, written.size - it) }

    /** The last result of [statement]'s results (a bound's settings answer first), read by [read]; [none] without. */
    private fun <T> lastResult(statement: PreparedStatement, none: T, read: (ResultSet) -> T): T {
        var found = none
        while (true) {
            statement.resultSet?.use { found = read(it) }
            if (!statement.moreResults && statement.updateCount == -1) return found
        }
    }

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

        /** Wow's tables the clone copies. */
        val WOW_TABLES = setOf(LOG, SNAPSHOT, PARTITIONS)

        /** The clone's tables. */
        val RAW_TABLES = setOf("raw_log", "raw_snapshot", "raw_partition")

        const val WOW_TODAY = "wow-today"
        const val P2_SEPARATE = "p2-separate"
        const val P2_COMBINED = "p2-combined"
        private const val COMMAND = "command"
        private const val SEQUENCE = "sequence"
        private const val FILLFACTOR = 100
        private const val LOW_FILLFACTOR = 80
    }
}

/** Two flushes' units, added. */
operator fun Flushed.plus(other: Flushed): Flushed = Flushed(written + other.written, refused + other.refused)
