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
                    "coalesce(t.n_tup_upd, 0), coalesce(t.n_tup_hot_upd, 0), " +
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
                    WAL_POSITION to r.getDouble(9),
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

    /**
     * The WAL written between [before]'s and [after]'s positions (WAL_POSITION), per record type and the table it
     * touched (an index's or TOAST table's own, its table's): `wal <type> <table> records`, the type
     * `<resource manager>/<record>` and the table `-` for a record of none (a commit), and each table's record bytes,
     * full-page images among them (`wal <table> bytes`). Read with pg_walinspect; empty when the extension is not there
     * or a checkpoint recycled the segments.
     */
    fun records(before: Map<String, Double>, after: Map<String, Double>): Map<String, Double> {
        val start = before[WAL_POSITION]?.toLong() ?: return emptyMap()
        val end = after[WAL_POSITION]?.toLong()?.takeIf { it > start } ?: return emptyMap()
        return try {
            connect().use { connection ->
                connection.createStatement().use { it.execute("CREATE EXTENSION IF NOT EXISTS pg_walinspect") }
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
        private val RECORDS = """
            SELECT w.resource_manager || '/' || split_part(w.record_type, ' ', 1),
                coalesce(owner.relname, rel.relname, '-'), count(*), sum(w.record_length)
            FROM pg_get_wal_records_info(?::pg_lsn, ?::pg_lsn) w
            LEFT JOIN pg_class rel
                ON rel.relfilenode = substring(w.block_ref FROM 'rel \d+/\d+/(\d+)')::oid AND rel.relfilenode <> 0
            LEFT JOIN pg_index i ON i.indexrelid = rel.oid
            LEFT JOIN pg_class owner ON owner.oid = coalesce(
                i.indrelid,
                (SELECT t.oid FROM pg_class t WHERE t.reltoastrelid = rel.oid)
            )
            GROUP BY 1, 2
        """.trimIndent()

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
