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

package me.ahoo.wow.spi.log

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.WowSpi

/**
 * The log of one bounded context: the only place a fact is written (axiom A1).
 *
 * What a backend must implement are the abstract members; the members with a default are optional optimizations
 * whose default is correct ([appendAll], [readTail], [lineage]).
 * The contract every backend keeps, checked by the TCK:
 * - unique keys are (`ref`, `version`) and (`tenantId`, `requestId`) within each aggregate type; `ref` includes the
 *   tenant. Each aggregate type is stored apart, so a request id reused by two aggregate types of
 *   the context commits twice (makes the reuse the caller's error);
 * - an aggregate's first stream is version 1 and each next stream is exactly one more;
 * - a stream lands in the partition [Partitioner] assigns its ref, at a sequence that orders it within the partition:
 *   an aggregate's streams are in version order. [tail] delivers in sequence order, never skips a committed entry,
 *   never delivers one before a lower sequence that may still commit, and reads the same entries again from the same
 *   position. A backend may leave sequences unused (MongoDB's blocks): a partition's sequences
 *   increase, without the promise that each follows the last by one;
 * - [partitions] is a power of two that divides [Partitioner.SLOTS]: a log of another count fails
 *   when it is created;
 * - the transport section an append carries is opaque bytes ([TransportCodec]): a backend stores them as
 *   they are and [tail] and [readEntries] give them back unchanged, so a field a newer version adds needs no backend
 *   change;
 * - each partition stores its [PartitionLineage] beside its counter, [PartitionLineage.ROOT] for every partition the
 *   log was created with, and [lineage] reads it back (format 1's reservation for the online splits of 10.x).
 */
@WowSpi
interface EventLog {
    /**
     * The number of partitions, fixed for the context's lifetime: a power of two that divides [Partitioner.SLOTS],
     * so that 10.x can split partitions online by doubling it.
     */
    val partitions: Int

    /**
     * Appends [stream] atomically, with [transport] stored beside it as the opaque bytes they are:
     * [tail] and [readEntries] give them back unchanged, [read] and [findByRequestId] never do.
     */
    suspend fun append(stream: EventStreamRecord, transport: ByteArray? = null): AppendOutcome

    /**
     * Whether [appendAll] takes the streams of several partitions in one call: the append queue then
     * hands it what several partitions' owners queued at once, so that one store call carries them. False by default:
     * the queue calls [appendAll] once per partition.
     */
    val appendsAcrossPartitions: Boolean get() = false

    /**
     * Appends [streams] of one partition (or of several, when [appendsAcrossPartitions]), each atomically with its
     * transport, and returns their outcomes in the same order. A partition's owner calls it with the appends it has
     * queued (group commit): a backend may commit them together to share the round trips, as long as every
     * outcome is what [append] would have returned:
     * a stream the backend refuses ([AppendOutcome.Rejected]) fails alone, and a group whose commit is unknown is
     * [AppendOutcome.Unknown] for each stream, never appended again one by one.
     *
     * Several streams of one aggregate come in consecutive versions, in order (a kernel's batch of the commands its
     * aggregate had queued). They commit as a prefix: once one of them does not commit, the
     * aggregate's later streams are not appended, each [AppendOutcome.Conflict] at the version that stream's outcome
     * leaves the aggregate at ([notAppendedAfter]), whether the backend tried them or not. By default the streams are
     * appended one by one, an aggregate's later streams skipped once one did not commit.
     *
     * Across partitions, each partition's streams take its sequences in the order they come, and one partition's
     * outcomes do not depend on another's: a stream that does not commit stops only its own aggregate's later streams.
     */
    suspend fun appendAll(streams: List<Pair<EventStreamRecord, ByteArray?>>): List<AppendOutcome> {
        val failed = HashMap<AggregateRef, AppendOutcome>()
        return streams.map { (stream, transport) ->
            failed[stream.ref]?.let { return@map it }
            append(stream, transport).also { outcome ->
                if (outcome !is AppendOutcome.Committed) failed[stream.ref] = notAppendedAfter(stream, outcome)
            }
        }
    }

    /** The streams of [ref] with versions in [fromVersion]..[toVersion], in version order: facts only. */
    fun read(ref: AggregateRef, fromVersion: Int = 1, toVersion: Int = Int.MAX_VALUE): Flow<EventStreamRecord>

    /**
     * The streams of [ref] from [fromVersion] on, in version order (facts only, as [read] gives them), and the request
     * keys of its latest streams before [fromVersion], at most [earlier] of them, the latest first, read in one round
     * trip where the backend can: a kernel loading an aggregate from its snapshot reads the tail after
     * it together with the requests it most recently committed, so the next command need not look its request id up.
     * The keys are taken by version alone, whenever they committed; fewer than [earlier] means none is left before
     * them. By default, two [read]s.
     */
    suspend fun readTail(ref: AggregateRef, fromVersion: Int, earlier: Int): AggregateTail {
        require(fromVersion >= 1) { "A stream has a version from 1." }
        require(earlier >= 0) { "At most a non-negative number of earlier requests." }
        val streams = read(ref, fromVersion).toList()
        val keys = if (earlier == 0 || fromVersion == 1) {
            emptyList()
        } else {
            read(ref, maxOf(1, fromVersion - earlier), fromVersion - 1).toList().asReversed().map(CommittedRequest::of)
        }
        return AggregateTail(streams, keys)
    }

    /**
     * The entries of [ref] with versions in [fromVersion]..[toVersion], in version order, each with its position and
     * transport: the read of recovery. A subscription that handles a parked aggregate again reads its streams by
     * version, and the `reservation` subscription needs the keys their transport carries.
     */
    fun readEntries(ref: AggregateRef, fromVersion: Int = 1, toVersion: Int = Int.MAX_VALUE): Flow<LogEntry>

    /**
     * The position of [ref]'s stream at [version], or null when there is none. A duplicate request uses it to tell
     * whether a subscription's checkpoint has passed the stream committed for it.
     */
    suspend fun positionOf(ref: AggregateRef, version: Int): LogPosition?

    /**
     * The stream committed for [requestId] by the aggregate [ref], or by another aggregate of its type and tenant that
     * an append of [ref] would meet, if any: facts only. This is the stream an append of the request to [ref] would
     * find already committed. A log whose request key is narrower than the aggregate type, such as MongoDB's, unique
     * within a partition, reads only what that append meets; by default the type's lookup. The
     * kernel's dedup pre-check and its settling of a conflict call it.
     */
    suspend fun findByRequestId(ref: AggregateRef, requestId: String): EventStreamRecord? =
        findByRequestId(ref.type, ref.tenantId, requestId)

    /**
     * The stream committed for [requestId] within [tenantId] in aggregate type [type], if any: facts only. It reads
     * that type's storage alone, every partition of it. Admission calls it for a creation command that names no
     * aggregate; a caller that knows the aggregate calls the overload taking its [AggregateRef].
     */
    suspend fun findByRequestId(type: AggregateType, tenantId: String, requestId: String): EventStreamRecord?

    /**
     * The entries of [partition] after [after] (exclusive; null from the start), in sequence order, each with its
     * transport. The flow does not complete: after the committed entries it waits for new ones.
     */
    fun tail(partition: Int, after: Long? = null): Flow<LogEntry>

    /**
     * The sequence of the last entry committed in [partition], or null when the partition is empty. A subscription
     * that starts from the end starts after it.
     */
    suspend fun head(partition: Int): Long?

    /**
     * The lineage of [partition] as stored: [PartitionLineage.ROOT] for a partition the log was created
     * with, which today is every partition. Format 1 reserves it for the online splits of 10.x, which nothing reads
     * yet. A backend that stores no lineage answers [PartitionLineage.ROOT].
     */
    suspend fun lineage(partition: Int): PartitionLineage {
        require(partition in 0 until partitions) { "No partition $partition among $partitions." }
        return PartitionLineage.ROOT
    }
}
