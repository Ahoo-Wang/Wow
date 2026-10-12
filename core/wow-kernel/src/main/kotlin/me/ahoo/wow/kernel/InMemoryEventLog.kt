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

package me.ahoo.wow.kernel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.log.Transport

/**
 * An [EventLog] in memory, for tests and samples. Appends are serialized by one lock, so the sequence within a
 * partition is trivially the order commits became visible.
 */
class InMemoryEventLog(override val partitions: Int = DEFAULT_PARTITIONS) : EventLog {
    private val lock = Mutex()
    private val streams = HashMap<AggregateRef, MutableList<EventStreamRecord>>()
    private val requests = HashMap<Pair<String, String>, EventStreamRecord>()
    private val positions = HashMap<AggregateRef, MutableList<LogPosition>>()
    private val entries = List(partitions) { ArrayList<LogEntry>() }
    private val appended = List(partitions) { MutableStateFlow(0L) }

    init {
        require(partitions >= 1) { "There is at least one partition." }
    }

    override suspend fun append(stream: EventStreamRecord, transport: Transport?): AppendOutcome = lock.withLock {
        requests[stream.ref.tenantId to stream.requestId]?.let { return AppendOutcome.AlreadyCommitted(it) }
        val aggregate = streams.getOrPut(stream.ref) { ArrayList() }
        val current = aggregate.size
        if (stream.version != current + 1) {
            return AppendOutcome.Conflict(current)
        }
        aggregate += stream
        requests[stream.ref.tenantId to stream.requestId] = stream
        val partition = Partitioner.partitionOf(stream.ref, partitions)
        val log = entries[partition]
        val position = LogPosition(partition, log.size + 1L)
        log += LogEntry(position, stream, transport)
        positions.getOrPut(stream.ref) { ArrayList() } += position
        appended[partition].value = position.sequence
        AppendOutcome.Committed(position)
    }

    override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> = flow {
        val snapshot = lock.withLock { streams[ref]?.toList().orEmpty() }
        snapshot.filter { it.version in fromVersion..toVersion }.forEach { emit(it) }
    }

    override suspend fun positionOf(ref: AggregateRef, version: Int): LogPosition? =
        lock.withLock { positions[ref]?.getOrNull(version - 1) }

    override suspend fun findByRequestId(tenantId: String, requestId: String): EventStreamRecord? =
        lock.withLock { requests[tenantId to requestId] }

    override suspend fun head(partition: Int): Long? {
        require(partition in 0 until partitions) { "No partition $partition among $partitions." }
        return lock.withLock { entries[partition].size.toLong().takeIf { it > 0 } }
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
