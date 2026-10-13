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
import me.ahoo.wow.api.aggregate.AggregateRef

/**
 * The log of one bounded context: the only place a fact is written (V10 axiom A1).
 *
 * The contract every backend keeps, checked by the TCK:
 * - unique keys are (`ref`, `version`) and (`tenantId`, `requestId`); `ref` includes the tenant. A backend that
 *   stores each aggregate type apart (decision D162) may keep the request key per aggregate type: a request id
 *   reused by two aggregate types of the context then commits twice (decision D55 makes the reuse the caller's
 *   error);
 * - an aggregate's first stream is version 1 and each next stream is exactly one more;
 * - a stream lands in the partition [Partitioner] assigns its ref, and the sequence within a partition is the order
 *   in which commits became visible: [tail] never skips a committed entry and reads the same entries again from the
 *   same position.
 */
interface EventLog {
    /** The number of partitions, fixed for the context's lifetime. */
    val partitions: Int

    /**
     * Appends [stream] atomically, with [transport] stored beside it (decision D25): [tail] and [readEntries] give
     * the transport back, [read] and [findByRequestId] never do.
     */
    suspend fun append(stream: EventStreamRecord, transport: Transport? = null): AppendOutcome

    /**
     * Appends [streams] of one partition, each atomically with its transport, and returns their outcomes in the same
     * order. A partition's owner calls it with the appends it has queued (group commit, finding F1): a backend may
     * commit them together to share the round trips, as long as every outcome is what [append] would have returned.
     * The streams target distinct aggregates. By default they are appended one by one.
     */
    suspend fun appendAll(streams: List<Pair<EventStreamRecord, Transport?>>): List<AppendOutcome> =
        streams.map { (stream, transport) -> append(stream, transport) }

    /** The streams of [ref] with versions in [fromVersion]..[toVersion], in version order: facts only. */
    fun read(ref: AggregateRef, fromVersion: Int = 1, toVersion: Int = Int.MAX_VALUE): Flow<EventStreamRecord>

    /**
     * The entries of [ref] with versions in [fromVersion]..[toVersion], in version order, each with its position and
     * transport: the read of recovery. A subscription that handles a parked aggregate again reads its streams by
     * version, and the `reservation` subscription needs the keys their transport carries (decision D50).
     */
    fun readEntries(ref: AggregateRef, fromVersion: Int = 1, toVersion: Int = Int.MAX_VALUE): Flow<LogEntry>

    /**
     * The position of [ref]'s stream at [version], or null when there is none. A duplicate request uses it to tell
     * whether a subscription's checkpoint has passed the stream committed for it (decision D63).
     */
    suspend fun positionOf(ref: AggregateRef, version: Int): LogPosition?

    /**
     * The stream committed for [requestId] within [tenantId], in any aggregate type of the context, if any: facts
     * only. When two aggregate types hold it (a request key per aggregate type), the earliest committed.
     */
    suspend fun findByRequestId(tenantId: String, requestId: String): EventStreamRecord?

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
}
