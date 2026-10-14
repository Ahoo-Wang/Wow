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

package me.ahoo.wow.migrate

/** Where a scan by commit time stands: the last stream read, by its `createTime` and its id. */
data class NineXPosition(val createTime: Long, val streamId: String)

/**
 * A 9.x event store, read by its storage format (decision D85: no 9.x module). The calls block; the import runs them
 * off its coroutines.
 *
 * 9.x stores have no position to resume a read from (design 07 §3 step 3): the full import walks the aggregates by
 * id, and the tail scans by commit time, which the store orders by `createTime`, ties by stream id.
 */
interface NineXEventSource {
    /** Scans the whole store: what each 9.x aggregate holds, for [MappingCheck]. */
    fun inventory(): List<NineXInventory>

    /** At most [limit] ids of [aggregate]'s instances after [after] (from the first when null), in id order. */
    fun aggregateIds(aggregate: NineXAggregate, after: NineXAggregateId?, limit: Int): List<NineXAggregateId>

    /** At most [limit] streams of [id] from version [fromVersion] on, in version order. */
    fun streams(aggregate: NineXAggregate, id: NineXAggregateId, fromVersion: Int, limit: Int): List<NineXStream>

    /**
     * At most [limit] streams of [aggregate] committed at or after [from] and after [after] (from [from] when null),
     * in commit-time order.
     */
    fun committedSince(aggregate: NineXAggregate, from: Long, after: NineXPosition?, limit: Int): List<NineXStream>
}

/**
 * A 9.x store whose scan by commit time needs an index 9.x did not create. The tool never writes to a 9.x store unless
 * the operator opts in: without the index, the tail refuses to start and says how to create it.
 */
interface CommitTimeIndexed {
    /** The collections of [aggregates] that lack the index, each with the command that creates it. */
    fun missingCommitTimeIndexes(aggregates: Collection<NineXAggregate>): Map<String, String>

    /** Creates the index on [aggregate]'s collection when it is missing: a write to the 9.x store. */
    fun ensureCommitTimeIndex(aggregate: NineXAggregate)
}
