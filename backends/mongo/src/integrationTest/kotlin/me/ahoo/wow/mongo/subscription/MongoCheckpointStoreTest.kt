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

package me.ahoo.wow.mongo.subscription

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.tck.subscription.CheckpointStoreSpec
import org.junit.jupiter.api.Test

class MongoCheckpointStoreTest : CheckpointStoreSpec() {
    override fun createStore(): CheckpointStore = store

    @Test
    fun `creating the schema twice keeps the checkpoints`(): Unit = runBlocking {
        store.save("schema-twice", "projection", 0, 7)
        MongoCheckpointStore(MongoLogFixture.client, MongoLogFixture.DATABASE).createSchema()
        store.load("schema-twice", "projection", 0).assert().isEqualTo(7L)
    }

    private companion object {
        val store by lazy {
            MongoCheckpointStore(MongoLogFixture.client, MongoLogFixture.DATABASE).also { it.createSchema() }
        }
    }
}
