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

package me.ahoo.wow.kernel.memory

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.subscription.Checkpoint
import me.ahoo.wow.spi.subscription.CheckpointStore
import java.util.concurrent.ConcurrentHashMap

/**
 * A [CheckpointStore] in memory, for tests and samples: the positions of each (group, subscription) by partition, so
 * a subscription's checkpoints are read by the prefix of the key, as the backends read theirs.
 */
@InternalWowApi
class InMemoryCheckpointStore : CheckpointStore {
    private val checkpoints = ConcurrentHashMap<Key, ConcurrentHashMap<Int, Long>>()

    override suspend fun load(group: String, subscription: String, partition: Int): Long? =
        checkpoints[Key(group, subscription)]?.get(partition)

    override suspend fun list(group: String): List<Checkpoint> = checkpoints.entries
        .filter { it.key.group == group }
        .flatMap { (key, positions) -> checkpointsOf(key.subscription, positions) }

    override suspend fun list(group: String, subscription: String): List<Checkpoint> =
        checkpoints[Key(group, subscription)]?.let { checkpointsOf(subscription, it) }.orEmpty()

    override suspend fun save(group: String, subscription: String, partition: Int, position: Long) {
        val positions = checkpoints.computeIfAbsent(Key(group, subscription)) { ConcurrentHashMap() }
        positions.merge(partition, position, ::maxOf)
    }

    private fun checkpointsOf(subscription: String, positions: Map<Int, Long>): List<Checkpoint> =
        positions.map { (partition, position) -> Checkpoint.of(subscription, partition, position) }

    private data class Key(val group: String, val subscription: String)
}
