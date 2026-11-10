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

package me.ahoo.wow.postgres

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.spi.log.AggregateTail
import me.ahoo.wow.spi.log.CommittedRequest
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.SnapshotTailReader
import me.ahoo.wow.spi.log.VersionPages
import javax.sql.DataSource

/** The reads of one aggregate's streams in a [PostgresEventLog]: by version, its tail, its entries, by key. */
internal class PostgresAggregateReads(
    private val dataSource: DataSource,
    private val dispatcher: CoroutineDispatcher,
    private val context: String,
    private val catalog: PostgresLogCatalog,
    private val logRows: PostgresLogRows,
    private val lookups: PostgresLogLookups,
) {
    /** A page of [VersionPages.SIZE] streams a statement, each emitted before the next is read. */
    fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> =
        VersionPages.byVersion(fromVersion, toVersion, EventStreamRecord::version) { from ->
            dispatcher.jdbc {
                dataSource.autoCommitted { connection ->
                    catalog.withTable(connection, ref.type) { table ->
                        connection.prepareStatement(table.read).use { query ->
                            query.setString(1, ref.id)
                            query.setString(2, ref.tenantId)
                            query.setInt(3, from)
                            query.setInt(4, toVersion)
                            query.setInt(5, VersionPages.SIZE)
                            query.executeQuery().use { rows -> rows.map { logRows.streamOf(table.aggregate, it) } }
                        }
                    }
                }
            }
        }

    /** One statement: the tail and the earlier request keys, both by the version key. */
    suspend fun readTail(ref: AggregateRef, fromVersion: Int, earlier: Int): AggregateTail {
        require(fromVersion >= 1) { "A stream has a version from 1." }
        require(earlier >= 0) { "At most a non-negative number of earlier requests." }
        return dispatcher.jdbc {
            dataSource.autoCommitted { connection ->
                catalog.withTable(connection, ref.type) { table ->
                    connection.prepareStatement(table.tail).use { query ->
                        query.setString(1, ref.id)
                        query.setString(2, ref.tenantId)
                        query.setInt(3, fromVersion)
                        query.setString(4, ref.id)
                        query.setString(5, ref.tenantId)
                        query.setInt(6, fromVersion)
                        query.setInt(7, earlier)
                        query.executeQuery().use { rows ->
                            val streams = ArrayList<EventStreamRecord>()
                            val keys = ArrayList<CommittedRequest>()
                            while (rows.next()) {
                                if (rows.getInt("part") == 0) {
                                    streams += logRows.streamOf(table.aggregate, rows)
                                } else {
                                    keys += CommittedRequest(
                                        rows.getInt("version"),
                                        rows.getString("request_id"),
                                        rows.getString("command_type"),
                                    )
                                }
                            }
                            AggregateTail(streams, keys.asReversed())
                        }
                    }
                }
            }
        }
    }

    /**
     * The read of [store]'s snapshots with this log's tails in one statement, [store] keeping this context's snapshots
     * in this log's database: the snapshot, the tail from its version and the request keys before it, as one snapshot
     * of the database sees them; a failure of the statement is thrown, for the kernel to read apart, warn and count it.
     */
    fun snapshotTailsWith(store: PostgresSnapshotStore): SnapshotTailReader {
        val shared = { type: String ->
            checkNotNull(store.sharedTable(dataSource, context, type)) {
                "The snapshots of $context are not in the database of its log."
            }
        }
        return SnapshotTailReader { ref, earlier ->
            require(earlier >= 0) { "At most a non-negative number of earlier requests." }
            dispatcher.jdbc {
                dataSource.autoCommitted { connection ->
                    val snapshot = shared(ref.type.name)
                    catalog.withTable(connection, ref.type) { table ->
                        connection.readSnapshotTail(table.tailWithSnapshot(snapshot), store, ref, earlier) {
                            logRows.streamOf(table.aggregate, it)
                        }
                    }
                }
            }
        }
    }

    /** A page of [VersionPages.SIZE] entries a statement, as [read]. */
    fun readEntries(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<LogEntry> =
        VersionPages.byVersion(fromVersion, toVersion, { it.stream.version }) { from ->
            dispatcher.jdbc {
                dataSource.autoCommitted { connection ->
                    catalog.withTable(connection, ref.type) { table ->
                        connection.prepareStatement(table.entries).use { query ->
                            query.setString(1, ref.id)
                            query.setString(2, ref.tenantId)
                            query.setInt(3, from)
                            query.setInt(4, toVersion)
                            query.setInt(5, VersionPages.SIZE)
                            query.executeQuery().use { rows ->
                                rows.map { logRows.entryOf(it.getInt("partition"), table.aggregate, it) }
                            }
                        }
                    }
                }
            }
        }

    suspend fun positionOf(ref: AggregateRef, version: Int): LogPosition? = dispatcher.jdbc {
        dataSource.autoCommitted { connection ->
            catalog.withTable(connection, ref.type) { table ->
                connection.prepareStatement(table.position).use { query ->
                    query.setString(1, ref.id)
                    query.setString(2, ref.tenantId)
                    query.setInt(3, version)
                    query.executeQuery().use { rows ->
                        if (rows.next()) LogPosition(rows.getInt("partition"), rows.getLong("sequence")) else null
                    }
                }
            }
        }
    }

    /** The stream committed for [requestId] within [tenantId] in [type]'s table: one indexed read of one table. */
    suspend fun findByRequestId(type: AggregateType, tenantId: String, requestId: String): EventStreamRecord? =
        dispatcher.jdbc {
            dataSource.autoCommitted { connection ->
                catalog.withTable(connection, type) { lookups.findByRequestIdIn(connection, it, tenantId, requestId) }
            }
        }
}
