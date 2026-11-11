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

package me.ahoo.wow.spi.sink

import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.spi.state.StateEventRecord

/**
 * Where a context's committed state events go: the Kafka Sink's topics, a warehouse's
 * tables. The runtime's sink subscription reads the log, builds the [StateEventRecord]s and hands them to [write]; it
 * owns the semantics around the write (checkpoints, retries, parking), so a sink only writes.
 *
 * - **Durable on return**: [write] returns once every record is durable where it goes (a broker's acknowledgement, a
 *   committed insert). The subscription moves its checkpoint only after that, so a crash never loses a record.
 * - **Idempotent by id**: publishing is at least once: a record may be written again after a crash, a
 *   retry or a replay, and an old owner of a partition may run beside the new one. Writing a record whose
 *   [StateEventRecord.id] was written before changes nothing a reader sees beyond a duplicate it drops by that id.
 * - **Order**: the records of one aggregate reach [write] in version order, one batch after the previous one has
 *   returned; a batch may hold records of several aggregates.
 * - **Failure**: [write] throws when not every record is durable; the subscription retries, then parks the
 *   aggregate. Some records of a failed batch may have been written already: the retry writes them again.
 * - **Batches** ([maxBatch]): a sink that takes more than one record at once is handed the records of every
 *   aggregate's commits waiting together, at most [maxBatch]; one that does not say is handed one record a call.
 */
@WowSpi
fun interface Sink {
    suspend fun write(records: List<StateEventRecord>)

    /**
     * The most records one [write] takes, 1 by default. Above 1, the sink subscription hands each aggregate's lane the
     * run of its commits waiting together, at most this many, and writes the runs of all its lanes waiting together
     * with one [write] of at most this many records. A commit whose events were all retired has no record, so a run
     * of this many commits holds at most this many records; the limit counts records. A run is one attempt within the
     * subscription handler's timeout, so this is sized for a write of this many records to fit it: a write that times
     * out costs the timeout, and its aggregate's next attempt writes one record alone.
     */
    val maxBatch: Int get() = 1
}
