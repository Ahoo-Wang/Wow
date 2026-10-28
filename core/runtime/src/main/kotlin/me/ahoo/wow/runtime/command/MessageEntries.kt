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

package me.ahoo.wow.runtime.command

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.subscribe.CommandCommitted
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.runtime.subscription.EntrySource
import me.ahoo.wow.runtime.subscription.SourcedEntry
import me.ahoo.wow.runtime.subscription.SubscriptionStart
import me.ahoo.wow.runtime.subscription.UndecodableMessage
import me.ahoo.wow.runtime.subscription.UnrecoverableException
import me.ahoo.wow.runtime.subscription.parkedAt
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.transport.Envelope
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.transport.EnvelopeKind
import me.ahoo.wow.spi.transport.PartitionedSource
import me.ahoo.wow.spi.transport.SourceMessage

/**
 * The partitions of a topic of commands or receipts as a subscription runtime reads them (–): each
 * message decoded from its envelope ([CommandMessages]) into the entry at the position (partition, offset + 1), so a
 * checkpoint is the offset to read next, carrying what it holds as its [SourcedEntry.message].
 *
 * Nothing in these topics is a commit, so an entry's stream only names the aggregate whose lane it runs in, at
 * version 1, and its order among that aggregate's entries is its offset ([ordinalOf]): parking, resuming and copies go
 * by the offset. A park records it ([sourcePosition]); a resume re-scans the aggregate's partition from it, keeping
 * the messages keyed by the aggregate; an offset no longer retained is unrecoverable.
 *
 * - A message of another kind, format or shape is reported to [observer] and logged: when its key
 *   names an aggregate, it is the entry of an [UndecodableMessage] there, on which the aggregate's subscription fails
 *   and parks it at its offset; otherwise it is passed over, the checkpoint moving past it.
 * - [repeats] is false: a copy of a command is not decided twice by the kernel (its request id), and a copy of a
 *   receipt is dropped by its command id, so the subscriptions need not drop copies themselves.
 * - A subscription starts at the beginning of the topic, or at its end; a start at an instant cannot be searched
 *   for (the messages carry no commit time) and starts at the beginning.
 */
internal class MessageEntries private constructor(
    override val topic: String,
    private val source: PartitionedSource,
    private val partitions: Int,
    private val kind: EnvelopeKind,
    private val partitioner: suspend (AggregateRef) -> Int,
    private val observer: Observer,
    private val decode: (Envelope) -> Decoded,
) : EntrySource {
    /** What a message holds: the aggregate whose lane it runs in, the command it is about, and the [message]. */
    class Decoded(
        val ref: AggregateRef,
        val commandId: String,
        val requestId: String,
        val commandType: String,
        val scope: Scope,
        val causation: Causation?,
        val message: Any,
    )

    override val name: String get() = "topic $topic"

    override fun requirePartition(partition: Int) =
        require(partition in 0 until partitions) { "Topic $topic has $partitions partitions." }

    override fun tail(partition: Int, after: Long?): Flow<SourcedEntry> =
        source.read(topic, partition, after ?: 0L).mapNotNull { entryOf(partition, it) }

    override suspend fun checkpointOf(start: SubscriptionStart, partition: Int): Long = when (start) {
        SubscriptionStart.End -> source.offsets(topic, partition).end
        else -> 0L
    }

    override suspend fun partitionOf(ref: AggregateRef): Int = partitioner(ref)

    override fun sourcePosition(entry: SourcedEntry): Long = entry.position.sequence - 1

    override fun ordinalOf(entry: SourcedEntry): Long = entry.position.sequence

    /** The entry parked at the offset it records; one recording none was parked before every one. */
    override fun ordinalOf(parked: ParkedAggregate): Long = (parked.fromPosition ?: -1L) + 1

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
            .parkedAt(topic, partition, key, fromPosition, offsets.end)
            .filter { it.key == key }
            .mapNotNull { entryOf(partition, it) }
            .filter { it.stream.ref == ref }
    }

    override suspend fun positionOf(ref: AggregateRef, version: Int): LogPosition? = null

    @Suppress("TooGenericExceptionCaught") // Whatever cannot be decoded is passed over.
    private fun entryOf(partition: Int, message: SourceMessage): SourcedEntry? {
        val (envelope, decoded) = try {
            val envelope = EnvelopeCodec.decode(message.value)
            require(envelope.kind == kind) { "It holds a ${envelope.kind}, not a $kind." }
            envelope to decode(envelope)
        } catch (failure: Exception) {
            val ref = UndecodableMessage.refOf(message.key)
            observer.observed(ObservedEvent.Undecodable(topic, partition, message.offset, parked = ref != null))
            val at = "offset ${message.offset} of partition $partition of $topic"
            if (ref == null) {
                logger.error(failure) { "Passing over $at: it holds no $kind this version reads, nor a key." }
                return null
            }
            logger.error(failure) { "Parking $ref at $at: it holds no $kind this version reads." }
            return UndecodableMessage.entryOf(ref, LogPosition(partition, message.offset + 1), failure.toString())
        }
        val stream = EventStreamRecord(
            ref = decoded.ref,
            version = 1,
            commandId = decoded.commandId,
            requestId = decoded.requestId,
            commandType = decoded.commandType,
            commitTime = 0L,
            events = listOf(EventRecord(kind.wire, 1, EMPTY)),
            scope = decoded.scope,
            causation = decoded.causation,
        )
        return SourcedEntry(
            LogPosition(partition, message.offset + 1),
            stream,
            envelope.transport,
            message = decoded.message,
        )
    }

    companion object {
        private val logger = KotlinLogging.logger {}
        private const val EMPTY = "{}"

        /**
         * The command topic [topic] of a context whose log has [partitions] partitions: an aggregate's
         * commands are in the partition the log's partitioner gives it, and each runs in its aggregate's lane.
         */
        fun commands(
            topic: String,
            source: PartitionedSource,
            partitions: Int,
            observer: Observer = Observer.NONE,
        ): MessageEntries = MessageEntries(
            topic,
            source,
            partitions,
            EnvelopeKind.COMMAND,
            { Partitioner.partitionOf(it, partitions) },
            observer,
        ) { envelope ->
            val wire = CommandMessages.commandOf(envelope)
            // A creation that names no aggregate runs, and parks, by its request.
            Decoded(CommandMessages.laneOf(wire), wire.id, wire.requestId, wire.type, wire.scope, wire.causation, wire)
        }

        /**
         * The receipts topic [topic] with [partitions] partitions: a receipt, of a rejection or
         * of a commit, runs in the lane of the aggregate whose commit the saga handled, in the partition its key maps
         * to on the broker.
         */
        fun receipts(
            topic: String,
            source: PartitionedSource,
            partitions: Int,
            observer: Observer = Observer.NONE,
        ): MessageEntries = MessageEntries(
            topic,
            source,
            partitions,
            EnvelopeKind.COMMAND_RECEIPT,
            { source.partitionOf(topic, it.toString()) },
            observer,
        ) { envelope ->
            when (val receipt = CommandMessages.receiptOf(envelope)) {
                is CommandCommitted<*> -> Decoded(
                    receipt.executionId.ref,
                    receipt.commandId,
                    receipt.commandId,
                    receipt.commandType,
                    Scope.NONE,
                    receipt.causation,
                    receipt,
                )

                else -> (receipt as CommandRejected<*>).let { rejected ->
                    Decoded(
                        rejected.executionId.ref,
                        rejected.commandId,
                        rejected.commandId,
                        rejected.commandType,
                        Scope.NONE,
                        rejected.causation,
                        rejected,
                    )
                }
            }
        }
    }
}
