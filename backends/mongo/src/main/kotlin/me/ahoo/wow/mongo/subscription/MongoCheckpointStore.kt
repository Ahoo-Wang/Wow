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

import com.mongodb.ErrorCategory
import com.mongodb.MongoBulkWriteException
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.BulkWriteOptions
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import kotlinx.coroutines.CoroutineDispatcher
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.mongo.MongoDatabases
import me.ahoo.wow.mongo.MongoFormat
import me.ahoo.wow.mongo.MongoTimeouts
import me.ahoo.wow.mongo.conditionalUpsert
import me.ahoo.wow.mongo.mongo
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.VirtualThreads
import me.ahoo.wow.spi.subscription.Checkpoint
import me.ahoo.wow.spi.subscription.CheckpointStore
import org.bson.Document
import org.bson.conversions.Bson
import kotlin.time.Duration

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
    /** How the store's collections and indexes are managed: created when missing by default (review A5-10). */
    private val ddl: Ddl = Ddl.AUTO,
    /** How long each of its calls may take (`wow.mongo.operation-timeout`, [MongoTimeouts]). */
    operationTimeout: Duration = MongoTimeouts.OPERATION,
) : CheckpointStore {
    private val database = MongoDatabases.of(client, databaseName, operationTimeout)
    private val checkpoints: MongoCollection<Document> = database.getCollection(COLLECTION)

    /** Creates the key index when missing. */
    fun createSchema() {
        MongoFormat.claim(database, COLLECTION, FORMAT, ddl)
        MongoFormat.index(
            checkpoints,
            Indexes.ascending(GROUP, SUBSCRIPTION, PARTITION),
            IndexOptions().unique(true).name(CHECKPOINT_KEY),
            ddl,
        )
    }

    override suspend fun load(group: String, subscription: String, partition: Int): Long? = dispatcher.mongo {
        checkpoints.find(keyFilter(group, subscription, partition)).firstOrNull()?.getLong(POSITION)
    }

    override suspend fun save(group: String, subscription: String, partition: Int, position: Long) {
        dispatcher.mongo {
            val guarded = Filters.and(keyFilter(group, subscription, partition), Filters.lt(POSITION, position))
            conditionalUpsert { upsert ->
                checkpoints.updateOne(guarded, Updates.set(POSITION, position), UpdateOptions().upsert(upsert))
            }
        }
    }

    /**
     * One unordered bulk write of the conditional upserts [save] makes one by one: one round trip. An upsert the key
     * index refuses (the guard failed on a stored checkpoint, or a concurrent insert won) is written again without
     * upsert, as [conditionalUpsert] does.
     */
    override suspend fun saveAll(group: String, checkpoints: List<Checkpoint>) {
        if (checkpoints.isEmpty()) {
            return
        }
        val saves = this.checkpoints
        dispatcher.mongo {
            fun write(upsert: Boolean, of: List<Checkpoint>) = saves.bulkWrite(
                of.map {
                    UpdateOneModel<Document>(
                        Filters.and(keyFilter(group, it.subscription, it.partition), Filters.lt(POSITION, it.position)),
                        Updates.set(POSITION, it.position),
                        UpdateOptions().upsert(upsert),
                    )
                },
                UNORDERED,
            )
            try {
                write(upsert = true, checkpoints)
            } catch (failure: MongoBulkWriteException) {
                val refused = failure.writeErrors.filter { ErrorCategory.fromErrorCode(it.code) == ErrorCategory.DUPLICATE_KEY }
                if (refused.size != failure.writeErrors.size || failure.writeConcernError != null) {
                    throw failure
                }
                write(upsert = false, refused.map { checkpoints[it.index] })
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

        /** The layout version of the collection (decision D59, finding A2-13). */
        const val FORMAT = 1
        const val CHECKPOINT_KEY = "checkpoint_key"
        const val GROUP = "subscription_group"
        const val SUBSCRIPTION = "subscription"
        const val PARTITION = "partition"
        const val POSITION = "position"

        /** Each checkpoint of a [saveAll] is saved whatever happens to the others. */
        val UNORDERED: BulkWriteOptions = BulkWriteOptions().ordered(false)
    }
}
