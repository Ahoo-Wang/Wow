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

import com.mongodb.client.model.Filters
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.mongo.CountedMongo
import me.ahoo.wow.mongo.MongoFormat
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.spi.subscription.Checkpoint
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

    @Test
    fun `the checkpoint and lease collections record their format`() {
        // Finding A2-13: a layout change went unnoticed until a read failed.
        store.assert().isNotNull()
        MongoLeaseStore(MongoLogFixture.client, MongoLogFixture.DATABASE).createSchema()
        val formats = MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE).getCollection(MongoFormat.COLLECTION)
        listOf("wow_checkpoint", "wow_lease", "wow_lease_member").forEach { structure ->
            formats.find(Filters.eq("_id", structure)).first()?.getInteger("version").assert().isEqualTo(1)
        }
    }

    @Test
    fun `saving the checkpoints of a tick is one round trip`(): Unit = runBlocking {
        // B.D diagnostics: a runtime's periodic save, one update command for all its subscriptions and partitions.
        val counted = MongoCheckpointStore(CountedMongo.client, MongoLogFixture.DATABASE)
        val group = "one-round-trip"
        counted.save(group, "saga", 0, 9)
        val tick = (0 until 8).map { Checkpoint(if (it % 2 == 0) "saga" else "projection", it / 2, it + 1L) }
        val sent = CountedMongo.commandsOf { runBlocking { counted.saveAll(group, tick) } }.second
        // Saga 0 is stored at 9, ahead of the 1 saved now: its upsert is refused and written again without upsert.
        sent.assert().containsExactly(
            CountedMongo.Sent("update", "wow_checkpoint"),
            CountedMongo.Sent("update", "wow_checkpoint"),
        )
        counted.load(group, "saga", 0).assert().isEqualTo(9L)
        CountedMongo.commandsOf { runBlocking { counted.saveAll(group, tick.map { it.copy(position = it.position + 10) }) } }
            .second.assert().containsExactly(CountedMongo.Sent("update", "wow_checkpoint"))
        tick.forEach { counted.load(group, it.subscription, it.partition).assert().isEqualTo(it.position + 10) }
    }

    private companion object {
        val store by lazy {
            MongoCheckpointStore(MongoLogFixture.client, MongoLogFixture.DATABASE).also { it.createSchema() }
        }
    }
}
