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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.WowSpi

/**
 * The log of one bounded context: the only place a fact is written (axiom A1).
 *
 * What a backend must implement are the abstract members; the members with a default are optional optimizations
 * whose default is correct ([appendAll], [readTail], [lineage], [handedOff]).
 * The contract every backend keeps, checked by the TCK:
 * - unique keys are (`ref`, `version`) and (`tenantId`, `requestId`) within each aggregate type; `ref` includes the
 *   tenant. Each aggregate type is stored apart, so a request id reused by two aggregate types of
 *   the context commits twice (makes the reuse the caller's error). A backend whose request key is narrower than the
 *   type, such as MongoDB's, unique within a partition, keeps one request to one aggregate of a creation that names
 *   none by its claims instead ([claimCreation]);
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
     *
     * A failure of the store is thrown only while nothing of the call may have been written. Once any stream of the
     * call may have committed, the call returns an outcome for every stream: a stream whose write failed, and those
     * after it that were not tried, are [AppendOutcome.Unknown], so that a caller never answers a committed stream as
     * one that wrote nothing.
     */
    suspend fun appendAll(streams: List<Append>): List<AppendOutcome> {
        val failed = HashMap<AggregateRef, AppendOutcome>()
        var written = false
        var stopped: Exception? = null
        return streams.map { next ->
            val stream = next.stream
            failed[stream.ref]?.let { return@map it }
            stopped?.let { return@map AppendOutcome.Unknown(it) }
            val outcome = try {
                append(stream, next.transport)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (
                @Suppress("TooGenericExceptionCaught") // Whatever the store threw, what it committed stands.
                failure: Exception,
            ) {
                if (!written) throw failure
                stopped = failure
                AppendOutcome.Unknown(failure)
            }
            written = written || outcome is AppendOutcome.Committed || outcome is AppendOutcome.Unknown
            outcome.also {
                if (it !is AppendOutcome.Committed) failed[stream.ref] = notAppendedAfter(stream, it)
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
     * The claim of the aggregate that the creation of request [requestId] within [tenantId] in [type] targets:
     * [claim], the attempt's own (its freshly drawn id), when the request has none, else the request's first claim.
     * Admission calls it, after [findByRequestId] found nothing committed, for a creation command that names no
     * aggregate, so that every attempt of one request targets one aggregate, in one partition, whatever the attempts'
     * draws.
     *
     * A log whose request key spans the aggregate type keeps no claims and returns [claim]: a second attempt's
     * stream, under another id, meets the first's at the key. A log whose key is narrower must claim:
     * insert-if-absent, one claim per (type, tenant, request), as durable as its streams. A claim lost while an
     * attempt of its request is still deciding lets a retry draw again, and both attempts may then commit, two
     * aggregates under two ids. A claim is never released: no bound holds an attempt, so no time after which a claim
     * is no longer needed. A log that wraps another forwards the call: it has no default, so that a wrapper written
     * by hand cannot drop the claims it does not forward.
     */
    suspend fun claimCreation(
        type: AggregateType,
        tenantId: String,
        requestId: String,
        claim: CreationClaim,
    ): CreationClaim

    /**
     * The entries of [partition] after [after] (exclusive; null from the start), in sequence order, each with its
     * transport. The flow does not complete: after the committed entries it waits for new ones.
     *
     * Their commit times are not ordered along the sequence: a stream's is its decision's time, and decisions of
     * different aggregates are appended in another order than they read the time, as imported history is.
     */
    fun tail(partition: Int, after: Long? = null): Flow<LogEntry>

    /**
     * The sequence of the last entry committed in [partition], or null when the partition is empty. A subscription
     * that starts from the end starts after it.
     */
    suspend fun head(partition: Int): Long?

    /**
     * The head of each of [partitions], as [head] gives it, null for an empty one: every partition of [partitions] is a
     * key of the answer. A store that can reads them all at once, so a caller that needs many partitions' heads (a
     * node's lag sampler, a snapshot backfill, an access walk) pays one round trip rather than one a partition. By
     * default, [head] of each.
     */
    suspend fun heads(partitions: Collection<Int>): Map<Int, Long?> = partitions.associateWith { head(it) }

    /**
     * The partitions among [cursors]' keys in which another writer may have appeared since this log was last asked
     * about them: a node's tails ask it, all their partitions at once, for the entries other nodes appended, which no
     * hand-over brings them. A partition named calls for a read of its head (and of the store past the cursor when the
     * head is past it); one not named means nothing new was seen, not that nothing lies past its cursor. It may name a
     * partition that holds nothing new: the caller then finds its head at its cursor.
     *
     * By default the answer is exact and keeps nothing: the partitions whose [heads] are past their cursors ([cursors]
     * gives each partition's last sequence the caller has). A store may instead answer from less than the heads and
     * from what it remembers of its previous answers, naming a partition once for each change it sees there; it
     * forgets a partition that a call does not ask about, so the next call that asks about it starts afresh. MongoDB's
     * log does so: it names a partition whose counter lists another node's block it had not listed at the previous call,
     * past the cursor ([cursors] serve it to leave out a block the caller has read through), and retires its own block
     * that the other node's lies past, so that its next append lands past it and the caller's tail, busy with this
     * node's commits or not, reads the other node's entry in sequence order.
     */
    suspend fun appendedElsewhere(cursors: Map<Int, Long>): Set<Int> =
        heads(cursors.keys).filter { (partition, head) -> head != null && head > cursors.getValue(partition) }.keys

    /**
     * The lineage of [partition] as stored: [PartitionLineage.ROOT] for a partition the log was created
     * with, which today is every partition. Format 1 reserves it for the online splits of 10.x, which nothing reads
     * yet. A backend that stores no lineage answers [PartitionLineage.ROOT].
     */
    suspend fun lineage(partition: Int): PartitionLineage {
        require(partition in 0 until partitions) { "No partition $partition among $partitions." }
        return PartitionLineage.ROOT
    }

    /**
     * This node handed [partition] off: the commands it ran as the partition's owner are drained, and those it runs
     * for it from now on run without the lease ([ExecutionLease.NOT_HELD]). A backend that gives the owner its
     * sequences ahead finalizes those it did not give out, so the next owner's tail does not wait at them; others
     * have nothing to do, the default. Called once per hand-off, before the lease is released; a failure is logged and
     * the hand-off goes on. A node that stops without handing off (a crash, a lost lease) does not call it: its
     * sequences are then resolved by the backend's tail, as before.
     */
    suspend fun handedOff(partition: Int) {
        require(partition in 0 until partitions) { "No partition $partition among $partitions." }
    }
}
