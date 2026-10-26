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

/** A 9.x stream as its store keys it: by instance id and version (9.x's unique `(aggregateId, version)`). */
internal data class NineXStreamKey(val aggregateId: String, val version: Int)

/** What a 9.x store holds at a [NineXStreamKey]: the stream's tenant and command. */
internal data class NineXStoredStream(val tenantId: String, val commandId: String)

/**
 * A 9.x event store written by the rollback export (decision D32), by its storage format (decision D85: no 9.x
 * module): the only writes the tool makes to a 9.x store besides the tail's index. The calls block.
 */
internal interface NineXEventTarget {
    /** What the store holds of [aggregate] at each of [keys]; a key it holds nothing at is left out. */
    fun stored(aggregate: NineXAggregate, keys: Collection<NineXStreamKey>): Map<NineXStreamKey, NineXStoredStream>

    /**
     * Writes [streams], all of [aggregate], as 9.x writes them; returns the keys of those the store already held a
     * stream at, which it leaves as they are.
     */
    fun insert(aggregate: NineXAggregate, streams: List<NineXStream>): Set<NineXStreamKey>

    /** The revisions of the 9.x event [name] the store holds in [aggregate]'s streams. */
    fun revisionsOf(aggregate: NineXAggregate, name: String): List<String>

    /** The number of streams of [aggregate] the store holds. */
    fun count(aggregate: NineXAggregate): Long
}
