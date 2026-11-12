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

package me.ahoo.wow.spi.subscription

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** The defaults a backend gets when it overrides only what a [CheckpointStore] must have. */
class CheckpointStoreTest {
    /** A store of the required methods alone, its checkpoints in a list. */
    private val store = object : CheckpointStore {
        val saved = mutableListOf<Pair<String, Checkpoint>>()

        override suspend fun load(group: String, subscription: String, partition: Int): Long? = saved
            .lastOrNull { (of, it) -> of == group && it.subscription == subscription && it.partition == partition }
            ?.second?.position

        override suspend fun list(group: String): List<Checkpoint> = saved.filter { it.first == group }.map { it.second }

        override suspend fun save(group: String, subscription: String, partition: Int, position: Long) {
            saved += group to Checkpoint.of(subscription, partition, position)
        }
    }

    @Test
    fun `a subscription's checkpoints default to the group's list, filtered to it`(): Unit = runBlocking {
        val saved = listOf(Checkpoint.of("saga", 0, 3), Checkpoint.of("saga", 1, 5), Checkpoint.of("other", 0, 7))
        store.saveAll("g", saved)
        store.save("h", "saga", 0, 9)
        store.list("g", "saga").assert()
            .containsExactlyInAnyOrder(Checkpoint.of("saga", 0, 3), Checkpoint.of("saga", 1, 5))
        store.list("g", "none").assert().isEmpty()
    }
}
