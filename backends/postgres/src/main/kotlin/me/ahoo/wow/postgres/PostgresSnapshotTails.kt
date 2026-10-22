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

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.spi.log.AggregateTail
import me.ahoo.wow.spi.log.CommittedRequest
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.SnapshotTail
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import java.sql.Connection
import java.sql.ResultSet

/**
 * The snapshot of [ref] in [store] and its tail, read with [sql] ([AggregateLogTable.tailWithSnapshot], decision
 * D268): the tail's streams decoded by [streamOf], the request keys before the snapshot taken at most [earlier].
 */
internal fun Connection.readSnapshotTail(
    sql: String,
    store: PostgresSnapshotStore,
    ref: AggregateRef,
    earlier: Int,
    streamOf: (ResultSet) -> EventStreamRecord,
): SnapshotTail = prepareStatement(sql).use { query ->
    query.setString(1, ref.tenantId)
    query.setString(2, ref.id)
    query.setString(3, ref.id)
    query.setString(4, ref.tenantId)
    query.setString(5, ref.id)
    query.setString(6, ref.tenantId)
    query.setInt(7, earlier)
    query.executeQuery().use { rows ->
        val streams = ArrayList<EventStreamRecord>()
        val keys = ArrayList<CommittedRequest>()
        var loaded: SnapshotRecord? = null
        while (rows.next()) {
            when (rows.getInt("part")) {
                0 -> streams += streamOf(rows)
                1 -> keys += CommittedRequest(rows.getInt("version"), rows.getString("request_id"), rows.getString("command_type"))
                else -> loaded = store.snapshotOf(ref.type, rows, "s_")
            }
        }
        SnapshotTail(loaded, AggregateTail(streams, keys.asReversed()))
    }
}
