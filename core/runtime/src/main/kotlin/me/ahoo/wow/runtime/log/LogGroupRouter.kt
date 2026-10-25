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

package me.ahoo.wow.runtime.log

import kotlinx.coroutines.flow.Flow
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.log.AggregateTail
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.PartitionLineage
import me.ahoo.wow.spi.log.SnapshotTail
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.snapshot.SnapshotStore

/**
 * The log of a context whose aggregates are in several log groups (decision D177), as its kernel and its readers by
 * aggregate see it: each call by aggregate goes to the log of the aggregate's group, [groups] for the types they name,
 * [default] for the others. A partition's entries are read per group, by the subscription runtime of each group, so
 * [tail], [head] and [lineage] are not served here: each group's log has its own sequences.
 *
 * Every group has the context's partitions; an append of streams of several groups appends each group's streams to
 * its own log, and the outcomes come back in the order of the streams.
 */
internal class LogGroupRouter(
    private val default: EventLog,
    private val groups: Map<AggregateType, EventLog>
) : EventLog {
    init {
        groups.values.forEach {
            require(it.partitions == default.partitions) {
                "Every log group has the context's ${default.partitions} partitions, not ${it.partitions}."
            }
        }
    }

    override val partitions: Int get() = default.partitions

    /** Each group's share of a call goes to that group's log: across partitions when every group's log takes them. */
    override val appendsAcrossPartitions: Boolean
        get() = default.appendsAcrossPartitions && groups.values.all { it.appendsAcrossPartitions }

    /** The log of [type]'s group. */
    fun logOf(type: AggregateType): EventLog = groups[type] ?: default

    override suspend fun append(stream: EventStreamRecord, transport: Transport?): AppendOutcome =
        logOf(stream.ref.type).append(stream, transport)

    override suspend fun appendAll(streams: List<Pair<EventStreamRecord, Transport?>>): List<AppendOutcome> {
        val byLog = streams.indices.groupBy { logOf(streams[it].first.ref.type) }
        if (byLog.size == 1) {
            return byLog.keys.single().appendAll(streams)
        }
        val outcomes = arrayOfNulls<AppendOutcome>(streams.size)
        byLog.forEach { (log, indices) ->
            log.appendAll(indices.map { streams[it] }).forEachIndexed { at, outcome -> outcomes[indices[at]] = outcome }
        }
        return outcomes.map { checkNotNull(it) }
    }

    override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> =
        logOf(ref.type).read(ref, fromVersion, toVersion)

    override suspend fun readTail(ref: AggregateRef, fromVersion: Int, earlier: Int): AggregateTail =
        logOf(ref.type).readTail(ref, fromVersion, earlier)

    override suspend fun readWithSnapshot(ref: AggregateRef, snapshots: SnapshotStore, earlier: Int): SnapshotTail =
        logOf(ref.type).readWithSnapshot(ref, snapshots, earlier)

    override fun readEntries(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<LogEntry> =
        logOf(ref.type).readEntries(ref, fromVersion, toVersion)

    override suspend fun positionOf(ref: AggregateRef, version: Int): LogPosition? =
        logOf(ref.type).positionOf(ref, version)

    override suspend fun findByRequestId(type: AggregateType, tenantId: String, requestId: String): EventStreamRecord? =
        logOf(type).findByRequestId(type, tenantId, requestId)

    /** The earliest committed stream of the request among every group's. */
    override suspend fun findByRequestId(tenantId: String, requestId: String): EventStreamRecord? =
        (listOf(default) + groups.values.distinct())
            .mapNotNull { it.findByRequestId(tenantId, requestId) }
            .minByOrNull { it.commitTime }

    override fun tail(partition: Int, after: Long?): Flow<LogEntry> =
        throw UnsupportedOperationException("Each log group is tailed by its own log: its sequences are its own.")

    override suspend fun head(partition: Int): Long? =
        throw UnsupportedOperationException("Each log group has a head of its own: its sequences are its own.")

    override suspend fun lineage(partition: Int): PartitionLineage =
        throw UnsupportedOperationException("Each log group has partitions of its own, and their lineage.")

    companion object {
        /** [default] alone when no type is in another group, else the router over them. */
        @JvmStatic
        fun of(default: EventLog, groups: Map<AggregateType, EventLog>): EventLog =
            if (groups.isEmpty()) default else LogGroupRouter(default, groups)
    }
}
