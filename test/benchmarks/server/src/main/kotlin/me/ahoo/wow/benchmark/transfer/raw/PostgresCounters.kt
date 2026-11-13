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
import java.sql.SQLException

/** PostgreSQL's counters and settings around a raw run's windows, on connections from [connect]. */
class PostgresCounters(private val connect: () -> Connection) {
    /**
     * PostgreSQL's cumulative counters: WAL bytes, records, full-page images, syncs and their time, commits, the
     * clone's snapshot table's updates and HOT updates, and the WAL's insert position (WAL_POSITION, for [records]).
     */
    fun counters(): Map<String, Double> = connect().use { connection ->
        connection.createStatement().use { s ->
            s.executeQuery(
                "SELECT w.wal_bytes, w.wal_records, w.wal_fpi, w.wal_sync, w.wal_sync_time, d.xact_commit, " +
                    "coalesce(t.n_tup_upd, 0), coalesce(t.n_tup_hot_upd, 0), coalesce(t.n_tup_newpage_upd, 0), " +
                    "(pg_current_wal_insert_lsn() - '0/0'::pg_lsn)::float8 " +
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
                    NEW_PAGE_UPDATES to r.getDouble(9),
                    WAL_POSITION to r.getDouble(10),
                )
            }
        }
    }

    /** Each of Wow's tables' rows inserted, updated (HOT among them) and deleted: where a calibration's gap is. */
    fun tables(): Map<String, Double> = connect().use { connection ->
        connection.createStatement().use { s ->
            s.executeQuery(
                "SELECT relname, n_tup_ins, n_tup_upd, n_tup_hot_upd, n_tup_del, n_tup_newpage_upd FROM pg_stat_user_tables " +
                    "WHERE schemaname = current_schema() AND relname LIKE 'wow\\_%'",
            ).use { r ->
                buildMap {
                    while (r.next()) {
                        val table = r.getString(1)
                        put("$table inserts", r.getDouble(2))
                        put("$table updates", r.getDouble(3))
                        put("$table hot updates", r.getDouble(4))
                        put("$table deletes", r.getDouble(5))
                        put("$table new page updates", r.getDouble(6))
                    }
                }
            }
        }
    }

    /** The share of the snapshot table's updates that were HOT, from the counters' growth. */
    fun ratios(growth: Map<String, Double>): Map<String, Double> {
        val updates = growth["snapshotUpdates"] ?: 0.0
        return if (updates > 0) {
            mapOf(
                HOT_SHARE to (growth["snapshotHotUpdates"] ?: 0.0) / updates,
                NEW_PAGE_SHARE to (growth[NEW_PAGE_UPDATES] ?: 0.0) / updates,
            )
        } else {
            emptyMap()
        }
    }

    /**
     * The WAL written between [before]'s and [after]'s positions (WAL_POSITION), per record type and the table it
     * touched (an index's or TOAST table's own, its table's): `wal <type> <table> records`, the type
     * `<resource manager>/<record>` and the table `-` for a record of none (a commit), and each table's record bytes,
     * full-page images among them (`wal <table> bytes`). Read with pg_walinspect; empty when the extension is not there,
     * a checkpoint recycled the segments, or the read took longer than RECORDS_TIMEOUT.
     */
    fun records(before: Map<String, Double>, after: Map<String, Double>): Map<String, Double> {
        val start = before[WAL_POSITION]?.toLong() ?: return emptyMap()
        val end = after[WAL_POSITION]?.toLong()?.takeIf { it > start } ?: return emptyMap()
        return try {
            connect().use { connection ->
                connection.createStatement().use {
                    it.execute("CREATE EXTENSION IF NOT EXISTS pg_walinspect")
                    it.execute("SET statement_timeout = '$RECORDS_TIMEOUT'")
                }
                connection.prepareStatement(RECORDS).use { statement ->
                    statement.setString(1, lsnOf(start))
                    statement.setString(2, lsnOf(end))
                    statement.executeQuery().use { r ->
                        val bytes = mutableMapOf<String, Double>()
                        buildMap {
                            while (r.next()) {
                                put("wal ${r.getString(1)} ${r.getString(2)} records", r.getDouble(3))
                                bytes.merge("wal ${r.getString(2)} bytes", r.getDouble(4), Double::plus)
                            }
                            putAll(bytes)
                        }
                    }
                }
            }
        } catch (e: SQLException) {
            System.err.println("raw: no WAL records by type: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Watches how far back open transactions hold the store's horizon while a window runs, a diagnostic of the HOT
     * share (a dead row version an open snapshot may still see is not pruned, so its page's space is not freed for the
     * next update): every SAMPLE_MILLIS, the oldest backend_xmin's age in transactions and the longest-open
     * transaction holding one, in this database and besides the watcher's own. The returned stop gives their means and
     * maxima over the window (XMIN_METRICS).
     */
    fun watch(): () -> Map<String, Double> {
        val ages = ArrayList<Double>()
        val holds = ArrayList<Double>()
        val stopping = java.util.concurrent.atomic.AtomicBoolean()
        val sampler = Thread.ofPlatform().name("raw-xmin").start {
            runCatching {
                connect().use { connection ->
                    connection.prepareStatement(HORIZON).use { statement ->
                        while (!stopping.get()) {
                            statement.executeQuery().use { r ->
                                r.next()
                                synchronized(ages) {
                                    ages += r.getDouble(1)
                                    holds += r.getDouble(2)
                                }
                            }
                            Thread.sleep(SAMPLE_MILLIS)
                        }
                    }
                }
            }
        }
        return {
            stopping.set(true)
            sampler.join()
            synchronized(ages) {
                if (ages.isEmpty()) {
                    emptyMap()
                } else {
                    mapOf(
                        XMIN_AGE_MEAN to ages.average(),
                        XMIN_AGE_MAX to ages.max(),
                        XMIN_HOLD_MS_MEAN to holds.average(),
                        XMIN_HOLD_MS_MAX to holds.max(),
                    )
                }
            }
        }
    }

    /** The server's version and the settings a ceiling is quoted with. */
    fun settings(): Map<String, Any?> = connect().use { connection ->
        connection.createStatement().use { s ->
            (listOf("server_version") + settingNames).associateWith<String, Any?> { setting ->
                s.executeQuery("SHOW $setting").use { r ->
                    r.next()
                    r.getString(1)
                }
            }
        }
    }

    companion object {
        /** The horizon's diagnostics ([watch]): the oldest xmin's age and the longest hold, mean and maximum. */
        const val XMIN_AGE_MEAN = "xminAgeMean"
        const val XMIN_AGE_MAX = "xminAgeMax"
        const val XMIN_HOLD_MS_MEAN = "xminHoldMsMean"
        const val XMIN_HOLD_MS_MAX = "xminHoldMsMax"
        val XMIN_METRICS = listOf(XMIN_AGE_MEAN, XMIN_AGE_MAX, XMIN_HOLD_MS_MEAN, XMIN_HOLD_MS_MAX)
        private const val SAMPLE_MILLIS = 100L
        private val HORIZON = """
            SELECT coalesce(max(age(backend_xmin)), 0),
                coalesce(max(extract(epoch FROM clock_timestamp() - xact_start) * 1000)
                    FILTER (WHERE backend_xmin IS NOT NULL), 0)
            FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid()
        """.trimIndent()

        /** The snapshot table's updates its new version went to another page for, and their share of its updates. */
        const val NEW_PAGE_UPDATES = "snapshotNewPageUpdates"
        const val NEW_PAGE_SHARE = "newPageShare"
        const val HOT_SHARE = "hotShare"

        /** The WAL's insert position in bytes, among the counters: a step's growth leaves it out. */
        const val WAL_POSITION = "walPosition"
        private const val LOW_BITS = 32
        private const val LOW_MASK = 0xFFFF_FFFFL

        /** A WAL position of [bytes] in PostgreSQL's form, `X/Y` in hexadecimal. */
        fun lsnOf(bytes: Long): String = "%X/%X".format(bytes ushr LOW_BITS, bytes and LOW_MASK)

        /**
         * The WAL records and bytes per unit on [tables] alone, from [records]' counters (`wal … <table> records`,
         * `wal <table> bytes`) as `<prefix>WalRecords` and `<prefix>WalBytes`; empty when none was read.
         */
        fun onTables(records: Map<String, Double>, tables: Set<String>, prefix: String): Map<String, Double> {
            if (records.keys.none { it.startsWith("wal ") }) return emptyMap()
            fun sum(suffix: String) = records.entries.sumOf { (key, value) ->
                val parts = key.split(' ')
                if (parts.first() == "wal" && key.endsWith(suffix) && parts[parts.size - 2] in tables) value else 0.0
            }
            return mapOf("${prefix}WalRecords" to sum(" records"), "${prefix}WalBytes" to sum(" bytes"))
        }

        /** Each record's type and the table its first block is of (an index's or TOAST table's owner), counted. */
        /**
         * Each record's type and the table its first block is of (an index's or TOAST table's owner), counted: the
         * records grouped by type and relation file first, so the catalog is joined once a group, not once a record.
         */
        private val RECORDS = """
            WITH w AS (
                SELECT resource_manager || '/' || split_part(record_type, ' ', 1) AS type,
                    substring(block_ref FROM 'rel \d+/\d+/(\d+)')::oid AS node,
                    count(*) AS records, sum(record_length) AS bytes
                FROM pg_get_wal_records_info(?::pg_lsn, ?::pg_lsn) GROUP BY 1, 2
            )
            SELECT w.type, coalesce(owner.relname, rel.relname, '-'), sum(w.records), sum(w.bytes)
            FROM w
            LEFT JOIN pg_class rel ON rel.relfilenode = w.node AND rel.relfilenode <> 0
            LEFT JOIN pg_index i ON i.indexrelid = rel.oid
            LEFT JOIN pg_class toast ON toast.reltoastrelid = rel.oid
            LEFT JOIN pg_class owner ON owner.oid = coalesce(i.indrelid, toast.oid)
            GROUP BY 1, 2
        """.trimIndent()

        /** The WAL read's bound: past it the read gives up, as without pg_walinspect. */
        private const val RECORDS_TIMEOUT = "60s"

        /** The durability and storage settings a ceiling is quoted with. */
        private val settingNames = listOf(
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
