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

package me.ahoo.wow.runtime.subscription

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.state.StateEventRecord
import me.ahoo.wow.spi.transport.SourceMessage

/**
 * One entry a [SubscriptionRuntime] hands its subscriptions: a committed [stream] at its [position] in the source's
 * partition, with its [transport]. [published] is the record it was read as when the source is another context's
 * topic; the stream is then that record's facts, its events' payloads as JSON text.
 *
 * [message] is what an entry of a topic that holds no state events was read as: a command of a context's command
 * topic, or a receipt of its receipts topic (–). Its [stream] then only names the aggregate whose
 * lane it runs in and the command it is about: nothing was committed.
 */
internal class SourcedEntry(
    val position: LogPosition,
    val stream: EventStreamRecord,
    val transport: Transport?,
    val published: StateEventRecord? = null,
    val message: Any? = null,
) {
    constructor(entry: LogEntry) : this(entry.position, entry.stream, entry.decodedTransport)
}

/**
 * Where a [SubscriptionRuntime] reads: the partitions of the context's log
 * ([LogSource]), or those of another context's published topic (`me.ahoo.wow.runtime.source.TopicSource`). Both are
 * read the same way, so the shared tail, the lanes, the contiguous low watermark and the ledger serve either: a
 * position is a partition and a sequence from 1, and a checkpoint the sequence of the last entry completed, 0 before
 * the first.
 */
internal interface EntrySource {
    /** The source as messages name it: the context's log, or a topic. */
    val name: String

    /** The topic the source reads, null for the context's log: its entries are consumed messages. */
    val topic: String? get() = null

    /** Fails unless [partition] is one of the source's. */
    fun requirePartition(partition: Int)

    /** The entries of [partition] after the sequence [after] (exclusive; null from the start); it never completes. */
    fun tail(partition: Int, after: Long?): Flow<SourcedEntry>

    /** The checkpoint a subscription without one starts from in [partition], as [start] says. */
    suspend fun checkpointOf(start: SubscriptionStart, partition: Int): Long

    /** The partition [ref]'s entries are in. */
    suspend fun partitionOf(ref: AggregateRef): Int

    /** Where [entry] lies in the source when it cannot be read by aggregate: what a park records to retry from. */
    fun sourcePosition(entry: SourcedEntry): Long?

    /**
     * [ref]'s entries from [fromVersion] on, as they stand now, for a retry: read by version from the
     * log, or by re-scanning the topic's partition from [fromPosition]. It completes. Throws an
     * [UnrecoverableException] when the source no longer holds them.
     */
    suspend fun retryEntries(ref: AggregateRef, fromVersion: Int, fromPosition: Long?): Flow<SourcedEntry>

    /** The log position of [ref]'s stream at [version], for a duplicate request; null for a topic. */
    suspend fun positionOf(ref: AggregateRef, version: Int): LogPosition?

    /** Whether the source may deliver an entry twice, so a subscription drops the copies it can recognize. */
    val repeats: Boolean get() = false

    /**
     * Where [entry] stands among the entries of its aggregate, the order a subscription runs them in: its version,
     * unless the source orders them otherwise (a topic of commands or receipts: by offset). While the aggregate is
     * parked, an entry at or after the parked one's ordinal is skipped; one at or before the last a retry handled is
     * a copy.
     */
    fun ordinalOf(entry: SourcedEntry): Long = entry.stream.version.toLong()

    /** The ordinal ([ordinalOf]) of the entry [parked] is parked from. */
    fun ordinalOf(parked: ParkedAggregate): Long = parked.fromVersion.toLong()
}

/** The source no longer holds what a parked aggregate is retried from: it can only be skipped. */
internal class UnrecoverableException(message: String) : IllegalStateException(message)

/**
 * The messages of [partition] of [topic] from [fromPosition], where the message an aggregate keyed [key] was parked at
 * lies, checked to be there: a topic that gained partitions since maps the key to
 * another partition, which holds the parked message at no offset, or another message at that offset. A re-scan there
 * would find nothing of the aggregate and resolve the entry, losing what was skipped; it fails with an
 * [UnrecoverableException] instead, so the entry stays parked, marked for an operator to skip once they have dealt
 * with what it skipped. A message no longer retained cannot be checked: the re-scan goes on.
 *
 * The ledger records the parked message's offset only, not its partition ([ParkedAggregate.fromPosition]); recording
 * the partition would let the retry follow the message, at the cost of a field in every backend's ledger.
 */
internal fun Flow<SourceMessage>.parkedAt(
    topic: String,
    partition: Int,
    key: String,
    fromPosition: Long,
    end: Long,
): Flow<SourceMessage> {
    if (fromPosition >= end) {
        throw UnrecoverableException(moved(topic, partition, key, fromPosition))
    }
    return onEach {
        if (it.offset == fromPosition && it.key != key) {
            throw UnrecoverableException(moved(topic, partition, key, fromPosition))
        }
    }
}

private fun moved(topic: String, partition: Int, key: String, offset: Long): String =
    "Partition $partition of $topic holds no message keyed $key at offset $offset: the topic's partitions changed " +
        "since it was parked, and the message is in another partition."

/** The context's [log]. */
internal class LogSource(private val log: EventLog) : EntrySource {
    override val name: String get() = "the log"

    override fun requirePartition(partition: Int) =
        require(partition in 0 until log.partitions) { "The log has ${log.partitions} partitions." }

    override fun tail(partition: Int, after: Long?): Flow<SourcedEntry> = log.tail(partition, after).map(::SourcedEntry)

    override suspend fun checkpointOf(start: SubscriptionStart, partition: Int): Long = start.checkpointIn(
        log,
        partition
    )

    override suspend fun partitionOf(ref: AggregateRef): Int = Partitioner.partitionOf(ref, log.partitions)

    override fun sourcePosition(entry: SourcedEntry): Long? = null

    override suspend fun retryEntries(ref: AggregateRef, fromVersion: Int, fromPosition: Long?): Flow<SourcedEntry> =
        log.readEntries(ref, fromVersion).map(::SourcedEntry)

    override suspend fun positionOf(ref: AggregateRef, version: Int): LogPosition? = log.positionOf(ref, version)
}
