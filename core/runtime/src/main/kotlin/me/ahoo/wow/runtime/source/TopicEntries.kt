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

package me.ahoo.wow.runtime.source

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.runtime.subscription.EntrySource
import me.ahoo.wow.runtime.subscription.SourcedEntry
import me.ahoo.wow.runtime.subscription.SubscriptionStart
import me.ahoo.wow.runtime.subscription.UndecodableMessage
import me.ahoo.wow.runtime.subscription.UnrecoverableException
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.state.StateEventRecord
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.transport.PartitionedSource
import me.ahoo.wow.spi.transport.SourceMessage

/**
 * The partitions of another context's published [topic] as a subscription runtime reads them (decision D134): each
 * message decoded into the [StateEventRecord] its envelope holds ([EnvelopeCodec]), at the position (partition,
 * offset + 1), so a checkpoint is the offset to read next and 0 reads from the first one retained.
 *
 * - A message that holds no state event this version reads (another format or kind, a malformed value) is reported
 *   to [observer] and logged (review A3-14). When its key names an aggregate, it is the entry of an
 *   [UndecodableMessage] there, on which the aggregate's subscriptions fail and park it, at its offset, for an
 *   operator to resume once a version reads it, or to skip; its order is after every entry of the aggregate handled
 *   ([ordinalOf]). Otherwise no aggregate can be parked for it: it is passed over, the checkpoint moving past it.
 * - A start from the end is the partition's end offset; from an instant, a binary search over the offsets retained by
 *   the records' commit times.
 * - An aggregate's records are all in the partition its key (the ref) maps to ([PartitionedSource.partitionOf]). A park
 *   records the offset of the record it failed at; a resume re-scans the partition from it up to the end the
 *   partition has when the resume runs, keeping the records keyed by the ref, of its version or later. When the
 *   partition no longer retains that offset, the resume fails with an [UnrecoverableException].
 * - At least once (decision D45): a record may be published twice, so the subscriptions drop the copies of a version
 *   they handled ([repeats]).
 */
internal class TopicEntries(
    override val topic: String,
    private val source: PartitionedSource,
    private val partitions: Int,
    private val observer: Observer = Observer.NONE,
) : EntrySource {
    override val name: String get() = "topic $topic"

    override val repeats: Boolean get() = true

    override fun requirePartition(partition: Int) =
        require(partition in 0 until partitions) { "Topic $topic has $partitions partitions." }

    override fun tail(partition: Int, after: Long?): Flow<SourcedEntry> =
        source.read(topic, partition, after ?: 0L).mapNotNull { entryOf(partition, it) }

    override suspend fun checkpointOf(start: SubscriptionStart, partition: Int): Long = when (start) {
        SubscriptionStart.Beginning -> 0L
        SubscriptionStart.End -> source.offsets(topic, partition).end
        is SubscriptionStart.At -> {
            val offsets = source.offsets(topic, partition)
            start.search(offsets.start, offsets.end) { after ->
                // The first record decoded at or after the offset; a partition of nothing but undecodable messages
                // up to its end starts at the end.
                source.read(topic, partition, after, offsets.end).mapNotNull { decodedOrNull(it) }
                    .firstOrNull()?.commitTime ?: Long.MAX_VALUE
            }
        }
    }

    override suspend fun partitionOf(ref: AggregateRef): Int = source.partitionOf(topic, ref.toString())

    override fun sourcePosition(entry: SourcedEntry): Long = entry.position.sequence - 1

    /** An undecodable message's entry comes after every entry of its aggregate handled: it is never dropped as a copy. */
    override fun ordinalOf(entry: SourcedEntry): Long =
        if (UndecodableMessage.isUndecodable(entry)) Long.MAX_VALUE else entry.stream.version.toLong()

    override suspend fun resume(ref: AggregateRef, fromVersion: Int, fromPosition: Long?): Flow<SourcedEntry> {
        if (fromPosition == null) {
            throw UnrecoverableException("Its ledger entry records no offset of $topic to re-scan from.")
        }
        val partition = partitionOf(ref)
        val offsets = source.offsets(topic, partition)
        if (offsets.start > fromPosition) {
            throw UnrecoverableException(
                "Partition $partition of $topic no longer retains offset $fromPosition; it starts at ${offsets.start}.",
            )
        }
        val key = ref.toString()
        return source.read(topic, partition, fromPosition, offsets.end)
            .filter { it.key == key }
            .mapNotNull { entryOf(partition, it) }
            .filter { it.stream.ref == ref && it.stream.version >= fromVersion }
    }

    override suspend fun positionOf(ref: AggregateRef, version: Int): LogPosition? = null

    @Suppress("TooGenericExceptionCaught") // Whatever cannot be decoded is parked, or passed over.
    private fun entryOf(partition: Int, message: SourceMessage): SourcedEntry? {
        val record = try {
            EnvelopeCodec.stateEventOf(message.value)
        } catch (failure: Exception) {
            return undecodable(partition, message, failure)
        }
        return SourcedEntry(LogPosition(partition, message.offset + 1), streamOf(record), record.transport, record)
    }

    /** The state event [message] holds, or null when it holds none this version reads. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // A search passes over what it cannot decode.
    private fun decodedOrNull(message: SourceMessage): StateEventRecord? = try {
        EnvelopeCodec.stateEventOf(message.value)
    } catch (_: Exception) {
        null
    }

    /** The entry of an undecodable [message] when its key names an aggregate; null when it is passed over. */
    private fun undecodable(partition: Int, message: SourceMessage, failure: Exception): SourcedEntry? {
        val ref = UndecodableMessage.refOf(message.key)
        observer.undecodable(topic, partition, message.offset, parked = ref != null)
        val at = "offset ${message.offset} of partition $partition of $topic"
        if (ref == null) {
            logger.error(failure) { "Passing over $at: it holds no state event this version reads, nor a key." }
            return null
        }
        logger.error(failure) { "Parking $ref at $at: it holds no state event this version reads." }
        return UndecodableMessage.entryOf(ref, LogPosition(partition, message.offset + 1), failure.toString())
    }

    internal companion object {
        private val logger = KotlinLogging.logger {}

        /** The facts of [record] as a stream: each event's payload as JSON text. */
        fun streamOf(record: StateEventRecord): EventStreamRecord = EventStreamRecord(
            ref = record.ref,
            version = record.version,
            commandId = record.commandId,
            requestId = record.requestId,
            commandType = record.commandType,
            commitTime = record.commitTime,
            events = record.events.map { EventRecord(it.name, it.revision, it.payload.toString()) },
            scope = record.scope,
            causation = record.causation,
            headers = record.headers,
            operator = record.operator,
        )
    }
}
