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

/** PostgreSQL's counters and settings around a raw run's windows, on connections from [connect]. */
class PostgresCounters(private val connect: () -> Connection) {
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
            (listOf("server_version") + settingNames).associateWith<String, Any?> { setting ->
                s.executeQuery("SHOW $setting").use { r ->
                    r.next()
                    r.getString(1)
                }
            }
        }
    }

    private companion object {
        /** The durability and storage settings a ceiling is quoted with. */
        val settingNames = listOf(
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
