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

package me.ahoo.wow.mongo.log

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The done blocks a log's tails take out of the counter's list ([MongoSequencer]): a listed block a tail read went
 * through (each of its sequences an entry or a placeholder) is done, but its node's next reservation in the partition
 * takes it out in the update it makes anyway. So a tail takes it out only once it has been seen read through for
 * [grace]: by then a live node has reserved again (at load, within a second), and a block still listed is a stopped
 * node's, which leaves by time.
 *
 * Kept in memory, per partition, keyed by the block's node and first sequence: a block no longer listed (taken out by
 * its node, or by another tail) is forgotten.
 */
internal class DoneBlocks(
    private val grace: Duration = GRACE,
    private val time: TimeSource = TimeSource.Monotonic,
) {
    private data class Key(val partition: Int, val node: String, val from: Long)

    private val seen = ConcurrentHashMap<Key, TimeMark>()

    /**
     * Of [listed], [partition]'s blocks as the counter lists them, those to take out now: wholly at or below [through],
     * the sequence a read went through without a gap, and seen so for [grace].
     */
    fun due(partition: Int, listed: List<MongoSequencer.OpenBlock>, through: Long): List<MongoSequencer.OpenBlock> {
        val readThrough = listed.filter { it.to <= through }.associateBy { Key(partition, it.node, it.from) }
        seen.keys.removeIf { it.partition == partition && it !in readThrough }
        return readThrough.filter { (key, _) -> seen.computeIfAbsent(key) { time.markNow() }.elapsedNow() >= grace }
            .onEach { (key, _) -> seen.remove(key) }
            .values.toList()
    }

    companion object {
        /** How long a tail leaves a done block listed for its node's next reservation. */
        val GRACE: Duration = 30.seconds
    }
}
