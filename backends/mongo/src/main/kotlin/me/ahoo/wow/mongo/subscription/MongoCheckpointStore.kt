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

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.mongo.VirtualThreads
import me.ahoo.wow.mongo.conditionalUpsert
import me.ahoo.wow.spi.subscription.CheckpointStore
import org.bson.Document
import org.bson.conversions.Bson

/**
 * The [CheckpointStore] on MongoDB: one document per (group, subscription, partition) in `wow_checkpoint`, keyed by
 * a unique index.
 *
 * A save is one conditional upsert whose filter admits only a lower stored position, so concurrent saves leave the
 * highest and a lower one changes nothing (decision D45; see [conditionalUpsert]). Calls run on [dispatcher], virtual
 * threads by default (decision D29).
 */
@InternalWowApi
class MongoCheckpointStore(
    client: MongoClient,
    databaseName: String,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
) : CheckpointStore {
    private val checkpoints: MongoCollection<Document> =
        client.getDatabase(databaseName).getCollection(COLLECTION)

    /** Creates the key index when missing. */
    fun createSchema() {
        checkpoints.createIndex(
            Indexes.ascending(GROUP, SUBSCRIPTION, PARTITION),
            IndexOptions().unique(true).name(CHECKPOINT_KEY),
        )
    }

    override suspend fun load(group: String, subscription: String, partition: Int): Long? = withContext(dispatcher) {
        checkpoints.find(keyFilter(group, subscription, partition)).firstOrNull()?.getLong(POSITION)
    }

    override suspend fun save(group: String, subscription: String, partition: Int, position: Long) {
        withContext(dispatcher) {
            val guarded = Filters.and(keyFilter(group, subscription, partition), Filters.lt(POSITION, position))
            conditionalUpsert { upsert ->
                checkpoints.updateOne(guarded, Updates.set(POSITION, position), UpdateOptions().upsert(upsert))
            }
        }
    }

    private fun keyFilter(group: String, subscription: String, partition: Int): Bson = Filters.and(
        Filters.eq(GROUP, group),
        Filters.eq(SUBSCRIPTION, subscription),
        Filters.eq(PARTITION, partition),
    )

    private companion object {
        const val COLLECTION = "wow_checkpoint"
        const val CHECKPOINT_KEY = "checkpoint_key"
        const val GROUP = "subscription_group"
        const val SUBSCRIPTION = "subscription"
        const val PARTITION = "partition"
        const val POSITION = "position"
    }
}
