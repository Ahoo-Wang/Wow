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

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import me.ahoo.wow.mongo.MongoFormat
import me.ahoo.wow.tck.containers.WowContainers
import java.util.concurrent.atomic.AtomicInteger

/** One MongoDB replica set for the test JVM, and a fresh context (so fresh collections) per log. */
object MongoLogFixture {
    val client: MongoClient by lazy { MongoClients.create(WowContainers.mongo.replicaSetUrl) }

    private val contexts = AtomicInteger()

    fun newContext(prefix: String = "tck"): String = "$prefix-${contexts.incrementAndGet()}"

    fun log(
        partitions: Int,
        context: String = newContext(),
        aggregates: Collection<String> = emptyList(),
    ): MongoEventLog = MongoEventLog(client, DATABASE, context, partitions).also { log ->
        log.createSchema()
        aggregates.forEach { log.createCollection(it) }
    }

    /** Creates [collection] the way a build without format records did: the collection alone. */
    fun createUnrecorded(collection: String) {
        client.getDatabase(DATABASE).createCollection(collection)
    }

    /** Moves the recorded format version of [structure] one ahead, as a newer Wow would have written it. */
    fun bumpFormat(structure: String) {
        val updated = client.getDatabase(DATABASE).getCollection(MongoFormat.COLLECTION)
            .updateOne(Filters.eq("_id", structure), Updates.inc("version", 1))
        check(updated.matchedCount == 1L) { "$structure has no recorded format." }
    }

    const val DATABASE = "wow_v10_log"
}
