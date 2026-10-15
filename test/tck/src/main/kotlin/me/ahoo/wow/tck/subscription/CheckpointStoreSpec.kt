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

package me.ahoo.wow.tck.subscription

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.subscription.CheckpointStore
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The [CheckpointStore] contract (documentation/designs/v10/04-subscriptions.md §1, decision D45) every backend
 * passes. A backend's test extends it and supplies the store from [createStore]; each test uses its own group, so
 * the tests may share one store.
 */
abstract class CheckpointStoreSpec {
    protected abstract fun createStore(): CheckpointStore

    private val group = "tck_" + UUID.randomUUID().toString().replace("-", "").take(GROUP_SUFFIX)

    @Test
    fun `a subscription without a checkpoint loads null`(): Unit = runBlocking {
        createStore().load(group, "projection", 0).assert().isNull()
    }

    @Test
    fun `a checkpoint saved at 0 is kept, and saved again changes nothing`(): Unit = runBlocking {
        // A subscription saves its start at once, 0 in an empty partition (G33).
        val store = createStore()
        store.save(group, "saga", 0, 0)
        store.load(group, "saga", 0).assert().isEqualTo(0L)
        store.save(group, "saga", 0, 0)
        store.load(group, "saga", 0).assert().isEqualTo(0L)
        store.save(group, "saga", 0, 3)
        store.load(group, "saga", 0).assert().isEqualTo(3L)
    }

    @Test
    fun `a checkpoint only moves forward`(): Unit = runBlocking {
        val store = createStore()
        store.save(group, "projection", 0, 10)
        store.load(group, "projection", 0).assert().isEqualTo(10L)
        store.save(group, "projection", 0, 4)
        store.load(group, "projection", 0).assert().isEqualTo(10L)
        store.save(group, "projection", 0, 10)
        store.load(group, "projection", 0).assert().isEqualTo(10L)
        store.save(group, "projection", 0, 11)
        store.load(group, "projection", 0).assert().isEqualTo(11L)
    }

    @Test
    fun `checkpoints are kept per group, subscription and partition`(): Unit = runBlocking {
        val store = createStore()
        val other = group + "_other"
        store.save(group, "projection", 0, 1)
        store.save(group, "projection", 1, 2)
        store.save(group, "saga", 0, 3)
        store.save(other, "projection", 0, 4)
        store.load(group, "projection", 0).assert().isEqualTo(1L)
        store.load(group, "projection", 1).assert().isEqualTo(2L)
        store.load(group, "saga", 0).assert().isEqualTo(3L)
        store.load(other, "projection", 0).assert().isEqualTo(4L)
        store.load(other, "saga", 0).assert().isNull()
    }

    @Test
    fun `concurrent saves leave the highest position`(): Unit = runBlocking {
        val store = createStore()
        val positions = (1L..CONCURRENT_SAVES).shuffled()
        coroutineScope {
            positions.map { position ->
                async(Dispatchers.Default) { store.save(group, "projection", 0, position) }
            }.awaitAll()
        }
        store.load(group, "projection", 0).assert().isEqualTo(CONCURRENT_SAVES)
    }

    protected companion object {
        const val GROUP_SUFFIX = 12
        const val CONCURRENT_SAVES = 64L
    }
}
