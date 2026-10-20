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

package me.ahoo.wow.kernel.memory

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.log.AggregateTail
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.CommittedRequest
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.PartitionLineage
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.log.TagsBackfill
import me.ahoo.wow.spi.log.Transport

/**
 * An [EventLog] in memory, for tests and samples. Appends are serialized by one lock, so the sequence within a
 * partition is trivially the order commits became visible. As the durable backends, it keeps the request key per
 * aggregate type (decision D162). Each partition keeps its [PartitionLineage], [PartitionLineage.ROOT] as created
 * (decision D240). It backfills the tags a stream was appended without ([TagsBackfill], decision D259) as the durable
 * logs do.
 */
@InternalWowApi
class InMemoryEventLog(override val partitions: Int = DEFAULT_PARTITIONS) :
    EventLog,
    TagsBackfill {
    init {
        Partitioner.requirePartitions(partitions)
    }

    private val lock = Mutex()
    private val streams = HashMap<AggregateRef, MutableList<EventStreamRecord>>()

    /** The streams committed for each (tenant, request id), in commit order: at most one per aggregate type. */
    private val requests = HashMap<Pair<String, String>, MutableList<EventStreamRecord>>()
    private val aggregateEntries = HashMap<AggregateRef, MutableList<LogEntry>>()
    private val entries = List(partitions) { ArrayList<LogEntry>() }
    private val appended = List(partitions) { MutableStateFlow(0L) }
    private val lineages = List(partitions) { PartitionLineage.ROOT }

    override suspend fun untagged(type: AggregateType, after: AggregateRef?, limit: Int): List<AggregateRef> {
        require(limit >= 1) { "A page holds at least one aggregate." }
        return lock.withLock {
            streams.filter { (ref, list) -> ref.type == type && list.any { it.tags == null } }.keys
                .sortedWith(KEY_ORDER)
                .filter { after == null || KEY_ORDER.compare(it, after) > 0 }
                .take(limit)
        }
    }

    /** Replaces each stream it tags wherever the log keeps it: by aggregate, by request and in its partition. */
    override suspend fun recordTags(ref: AggregateRef, tagsByVersion: Map<Int, Map<String, List<String>>>): Int =
        lock.withLock {
            val aggregate = streams[ref] ?: return@withLock 0
            var recorded = 0
            tagsByVersion.forEach { (version, tags) ->
                val stream = aggregate.getOrNull(version - 1)?.takeIf { it.tags == null } ?: return@forEach
                val tagged = stream.copyWith(tags = tags)
                aggregate[version - 1] = tagged
                requests[ref.tenantId to stream.requestId]?.replaceAll { if (it === stream) tagged else it }
                val entry = checkNotNull(aggregateEntries[ref])[version - 1]
                val retagged = entry.copyWith(stream = tagged)
                checkNotNull(aggregateEntries[ref])[version - 1] = retagged
                entries[entry.position.partition][(entry.position.sequence - 1).toInt()] = retagged
                recorded++
            }
            recorded
        }

    override suspend fun append(stream: EventStreamRecord, transport: Transport?): AppendOutcome = lock.withLock {
        val request = stream.ref.tenantId to stream.requestId
        requests[request]?.firstOrNull { it.ref.type == stream.ref.type }
            ?.let { return AppendOutcome.AlreadyCommitted(it) }
        val aggregate = streams.getOrPut(stream.ref) { ArrayList() }
        val current = aggregate.size
        if (stream.version != current + 1) {
            return AppendOutcome.Conflict(current)
        }
        aggregate += stream
        requests.getOrPut(request) { ArrayList() } += stream
        val partition = Partitioner.partitionOf(stream.ref, partitions)
        val log = entries[partition]
        val position = LogPosition(partition, log.size + 1L)
        log += LogEntry(position, stream, transport)
        aggregateEntries.getOrPut(stream.ref) { ArrayList() } += log.last()
        appended[partition].value = position.sequence
        AppendOutcome.Committed(position)
    }

    override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> = flow {
        val snapshot = lock.withLock { streams[ref]?.toList().orEmpty() }
        snapshot.filter { it.version in fromVersion..toVersion }.forEach { emit(it) }
    }

    /** One read under the lock: the tail and the earlier request keys of the same moment (decision D261). */
    override suspend fun readTail(ref: AggregateRef, fromVersion: Int, earlier: Int): AggregateTail {
        require(fromVersion >= 1) { "A stream has a version from 1." }
        require(earlier >= 0) { "At most a non-negative number of earlier requests." }
        val snapshot = lock.withLock { streams[ref]?.toList().orEmpty() }
        val (tail, before) = snapshot.partition { it.version >= fromVersion }
        return AggregateTail(tail, before.asReversed().take(earlier).map(CommittedRequest::of))
    }

    override fun readEntries(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<LogEntry> = flow {
        val snapshot = lock.withLock { aggregateEntries[ref]?.toList().orEmpty() }
        snapshot.filter { it.stream.version in fromVersion..toVersion }.forEach { emit(it) }
    }

    override suspend fun positionOf(ref: AggregateRef, version: Int): LogPosition? =
        lock.withLock { aggregateEntries[ref]?.getOrNull(version - 1)?.position }

    override suspend fun findByRequestId(type: AggregateType, tenantId: String, requestId: String): EventStreamRecord? =
        lock.withLock { requests[tenantId to requestId]?.firstOrNull { it.ref.type == type } }

    override suspend fun findByRequestId(tenantId: String, requestId: String): EventStreamRecord? =
        lock.withLock { requests[tenantId to requestId]?.firstOrNull() }

    override suspend fun head(partition: Int): Long? {
        require(partition in 0 until partitions) { "No partition $partition among $partitions." }
        return lock.withLock { entries[partition].size.toLong().takeIf { it > 0 } }
    }

    override suspend fun lineage(partition: Int): PartitionLineage {
        require(partition in 0 until partitions) { "No partition $partition among $partitions." }
        return lineages[partition]
    }

    override fun tail(partition: Int, after: Long?): Flow<LogEntry> = flow {
        require(partition in 0 until partitions) { "No partition $partition among $partitions." }
        var next = (after ?: 0L) + 1
        while (true) {
            val seen = appended[partition].value
            val batch = lock.withLock { entries[partition].drop((next - 1).toInt()) }
            batch.asFlow().collect {
                emit(it)
                next = it.position.sequence + 1
            }
            if (batch.isEmpty()) {
                appended[partition].first { it != seen }
            }
        }
    }

    private companion object {
        const val DEFAULT_PARTITIONS = 4

        /** The durable logs' key order: aggregate id, then tenant. */
        val KEY_ORDER: Comparator<AggregateRef> = compareBy<AggregateRef>({ it.id }, { it.tenantId })
    }
}
