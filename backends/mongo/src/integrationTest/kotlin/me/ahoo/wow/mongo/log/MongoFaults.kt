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

import com.mongodb.MongoSocketReadTimeoutException
import com.mongodb.ReadConcern
import com.mongodb.ReadPreference
import com.mongodb.ServerAddress
import com.mongodb.WriteConcern
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.InsertManyOptions
import com.mongodb.client.result.InsertManyResult
import com.mongodb.client.result.InsertOneResult
import org.bson.Document
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The fixture's client whose log collections run each insert through [insert], given the document and the real
 * insert to call or not: a fault injected where a stream or a placeholder is written.
 */
internal fun faulty(
    many: (List<Document>, (List<Document>) -> Unit) -> Unit = { documents, insert -> insert(documents) },
    insert: (Document, () -> Unit) -> Unit = { _, write -> write() },
): MongoClient {
    val real = MongoLogFixture.client
    return object : MongoClient by real {
        override fun getDatabase(databaseName: String): MongoDatabase =
            FaultyDatabase(real.getDatabase(databaseName), insert, many)
    }
}

/**
 * A database whose aggregate log collections are [Faulty], also once it is given a timeout, a write concern or a
 * read preference (`MongoDatabases.of` gives all three).
 */
private class FaultyDatabase(
    private val real: MongoDatabase,
    private val insert: (Document, () -> Unit) -> Unit,
    private val many: (List<Document>, (List<Document>) -> Unit) -> Unit,
) : MongoDatabase by real {
    override fun withTimeout(timeout: Long, timeUnit: TimeUnit): MongoDatabase =
        FaultyDatabase(real.withTimeout(timeout, timeUnit), insert, many)

    override fun withWriteConcern(writeConcern: WriteConcern): MongoDatabase =
        FaultyDatabase(real.withWriteConcern(writeConcern), insert, many)

    override fun withReadPreference(readPreference: ReadPreference): MongoDatabase =
        FaultyDatabase(real.withReadPreference(readPreference), insert, many)

    override fun getCollection(collectionName: String): MongoCollection<Document> {
        val collection = real.getCollection(collectionName)
        val aggregate = collectionName.startsWith("wow_log__") && !collectionName.endsWith("__partition")
        return if (aggregate) Faulty(collection, insert, many) else collection
    }
}

private class Faulty(
    private val real: MongoCollection<Document>,
    private val insert: (Document, () -> Unit) -> Unit,
    private val many: (List<Document>, (List<Document>) -> Unit) -> Unit,
) : MongoCollection<Document> by real {
    override fun withWriteConcern(writeConcern: WriteConcern) =
        Faulty(real.withWriteConcern(writeConcern), insert, many)

    override fun withReadConcern(readConcern: ReadConcern) = Faulty(real.withReadConcern(readConcern), insert, many)

    override fun withTimeout(timeout: Long, timeUnit: TimeUnit) =
        Faulty(real.withTimeout(timeout, timeUnit), insert, many)

    override fun insertMany(documents: List<Document>, options: InsertManyOptions): InsertManyResult {
        var result: InsertManyResult? = null
        many(documents) { written -> result = real.insertMany(written, options) }
        return result ?: throw MongoSocketReadTimeoutException("no reply", ServerAddress(), IOException("timed out"))
    }

    override fun insertOne(document: Document): InsertOneResult {
        var result: InsertOneResult? = null
        insert(document) { result = real.insertOne(document) }
        return result ?: throw MongoSocketReadTimeoutException("no reply", ServerAddress(), IOException("timed out"))
    }
}
