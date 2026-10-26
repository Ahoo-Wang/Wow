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

package me.ahoo.wow.test

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.SnapshotTail
import me.ahoo.wow.spi.snapshot.SnapshotStore

/**
 * The log of an [AggregateSpec] whose aggregate starts in a given state ([AggregateSpec.givenState]) at version
 * [offset]: the streams after it are kept in [delegate] from version 1, and read back at their own versions, so the
 * log holds no stream before the given state's.
 */
internal class GivenStateLog(private val delegate: EventLog) : EventLog by delegate {
    /** The version of the given state; 0 without one. */
    var offset: Int = 0

    override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome {
        if (stream.version <= offset) {
            return AppendOutcome.Conflict(offset)
        }
        return when (val outcome = delegate.append(stream.shifted(-offset), transport)) {
            is AppendOutcome.Conflict -> AppendOutcome.Conflict(outcome.currentVersion + offset)
            is AppendOutcome.AlreadyCommitted -> AppendOutcome.AlreadyCommitted(outcome.existing.shifted(offset))
            else -> outcome
        }
    }

    override suspend fun appendAll(streams: List<Pair<EventStreamRecord, ByteArray?>>): List<AppendOutcome> =
        streams.map { (stream, transport) -> append(stream, transport) }

    override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> =
        delegate.read(ref, from(fromVersion), to(toVersion)).map { it.shifted(offset) }

    /** Read as [read] shifts it, never in one round trip by the delegate, which keeps the streams unshifted. */
    override suspend fun readWithSnapshot(ref: AggregateRef, snapshots: SnapshotStore, earlier: Int): SnapshotTail =
        super.readWithSnapshot(ref, snapshots, earlier)

    override fun readEntries(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<LogEntry> =
        delegate.readEntries(ref, from(fromVersion), to(toVersion)).map { it.shifted(offset) }

    override suspend fun positionOf(ref: AggregateRef, version: Int): LogPosition? =
        if (version <= offset) null else delegate.positionOf(ref, version - offset)

    override suspend fun findByRequestId(ref: AggregateRef, requestId: String): EventStreamRecord? =
        delegate.findByRequestId(ref, requestId)?.shifted(offset)

    override suspend fun findByRequestId(type: AggregateType, tenantId: String, requestId: String): EventStreamRecord? =
        delegate.findByRequestId(type, tenantId, requestId)?.shifted(offset)

    override suspend fun findByRequestId(tenantId: String, requestId: String): EventStreamRecord? =
        delegate.findByRequestId(tenantId, requestId)?.shifted(offset)

    override fun tail(partition: Int, after: Long?): Flow<LogEntry> =
        delegate.tail(partition, after).map { it.shifted(offset) }

    /** The entries the log holds, as appended: a fork appends them to a log of its own (decision D245). */
    suspend fun entries(): List<LogEntry> {
        val head = delegate.head(PARTITION) ?: return emptyList()
        return delegate.tail(PARTITION, null).take(head.toInt()).toList()
    }

    /** Appends [entries], another log's [entries], as they were appended there. */
    suspend fun restore(entries: List<LogEntry>) {
        entries.forEach { entry ->
            check(delegate.append(entry.stream, entry.transport) is AppendOutcome.Committed) {
                "Could not copy ${entry.stream.ref} at ${entry.position}."
            }
        }
    }

    private fun from(version: Int): Int = (version - offset).coerceAtLeast(1)

    private fun to(version: Int): Int = if (version == Int.MAX_VALUE) version else version - offset

    private fun EventStreamRecord.shifted(by: Int): EventStreamRecord =
        if (by == 0) this else copyWith(version = version + by)

    private fun LogEntry.shifted(by: Int): LogEntry = if (by == 0) this else copyWith(stream = stream.shifted(by))
}

/** A specification's log has one partition. */
private const val PARTITION = 0
