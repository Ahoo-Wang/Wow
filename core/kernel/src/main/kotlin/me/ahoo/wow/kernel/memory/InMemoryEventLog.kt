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
import me.ahoo.wow.spi.access.AccessWalk
import me.ahoo.wow.spi.access.AccessWalks
import me.ahoo.wow.spi.access.AggregatePage
import me.ahoo.wow.spi.log.AggregateTail
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.CommittedRequest
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.PartitionLineage
import me.ahoo.wow.spi.log.Partitioner

/**
 * An [EventLog] in memory, for tests and samples. Appends are serialized by one lock, so the sequence within a
 * partition is trivially the order commits became visible. As the durable backends, it keeps the request key per
 * aggregate type. Each partition keeps its [PartitionLineage], [PartitionLineage.ROOT] as created. It keeps each
 * stream's stamp, stamps the access attributes over a history and records each type's walk ([AccessWalks]) as the
 * durable logs do; its aggregates are ordered by id, then tenant.
 */
@InternalWowApi
class InMemoryEventLog(override val partitions: Int = DEFAULT_PARTITIONS) :
    EventLog,
    AccessWalks {
    init {
        Partitioner.requirePartitions(partitions)
    }

    private val lock = Mutex()
    private val streams = HashMap<AggregateRef, MutableList<EventStreamRecord>>()

    /** The walk of each type, as [claim] and [advance] recorded it. */
    private val walks = HashMap<AggregateType, AccessWalk>()

    /** Its `appendAll` appends one stream at a time under the one lock, whichever partitions. */
    override val appendsAcrossPartitions: Boolean get() = true

    /** The streams committed for each (tenant, request id), in commit order: at most one per aggregate type. */
    private val requests = HashMap<Pair<String, String>, MutableList<EventStreamRecord>>()
    private val aggregateEntries = HashMap<AggregateRef, MutableList<LogEntry>>()
    private val entries = List(partitions) { ArrayList<LogEntry>() }
    private val appended = List(partitions) { MutableStateFlow(0L) }
    private val lineages = List(partitions) { PartitionLineage.ROOT }

    override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome = lock.withLock {
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

    /** Replaces each stream it stamps wherever the log keeps it: by aggregate, by request and in its partition. */
    override suspend fun stamp(ref: AggregateRef, access: Map<String, Any?>, asOf: Int, from: Int, until: Int): Int =
        lock.withLock {
            val aggregate = streams[ref] ?: return@withLock 0
            var written = 0
            for (version in from.coerceAtLeast(1) until minOf(until, aggregate.size + 1)) {
                val stream = aggregate[version - 1]
                if ((stream.accessVersion ?: 0) > asOf) continue
                val restamped = stream.copyWith(access = access, accessVersion = asOf)
                aggregate[version - 1] = restamped
                requests[ref.tenantId to stream.requestId]?.replaceAll { if (it === stream) restamped else it }
                val entry = checkNotNull(aggregateEntries[ref])[version - 1]
                val moved = entry.copyWith(stream = restamped)
                checkNotNull(aggregateEntries[ref])[version - 1] = moved
                entries[entry.position.partition][(entry.position.sequence - 1).toInt()] = moved
                written++
            }
            written
        }

    override suspend fun aggregates(type: AggregateType, after: String?, limit: Int): AggregatePage = lock.withLock {
        val key = after?.let(AggregatePage::keyOf)
        val refs = streams.keys.filter { it.type == type }
            .sortedWith(compareBy({ it.id }, { it.tenantId }))
            .dropWhile { key != null && compareValuesBy(it.id to it.tenantId, key, { it.first }, { it.second }) <= 0 }
            .take(limit)
        AggregatePage.of(refs, limit)
    }

    override suspend fun histories(refs: List<AggregateRef>): Map<AggregateRef, List<EventStreamRecord>> =
        lock.withLock {
            refs.mapNotNull { ref -> streams[ref]?.takeIf { it.isNotEmpty() }?.let { ref to it.toList() } }.toMap()
        }

    /** Whether a stream of [type] was appended: the store of a type's history is its streams. */
    override suspend fun holds(type: AggregateType): Boolean = lock.withLock { streams.keys.any { it.type == type } }

    override suspend fun walk(type: AggregateType): AccessWalk? = lock.withLock { walks[type] }

    override suspend fun claim(type: AggregateType, walk: AccessWalk, expected: String?): Boolean = lock.withLock {
        if (streams.keys.none { it.type == type } || walks[type]?.schema != expected) {
            return@withLock false
        }
        walks[type] = walk
        true
    }

    override suspend fun advance(
        type: AggregateType,
        schema: String,
        cursor: String?,
        walked: Long,
        completed: Boolean,
    ): Boolean = lock.withLock {
        val walk = walks[type]?.takeIf { it.schema == schema } ?: return@withLock false
        walks[type] = walk.copy(cursor = cursor, walked = walked, completed = completed)
        true
    }

    override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> = flow {
        val snapshot = lock.withLock { streams[ref]?.toList().orEmpty() }
        snapshot.filter { it.version in fromVersion..toVersion }.forEach { emit(it) }
    }

    /** One read under the lock: the tail and the earlier request keys of the same moment. */
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
    }
}
