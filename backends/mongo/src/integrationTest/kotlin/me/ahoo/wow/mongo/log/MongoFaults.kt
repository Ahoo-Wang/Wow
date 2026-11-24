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
import com.mongodb.client.FindIterable
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.InsertManyOptions
import com.mongodb.client.result.InsertManyResult
import com.mongodb.client.result.InsertOneResult
import org.bson.Document
import org.bson.conversions.Bson
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The fixture's client whose log collections run each insert through [insert], given the document and the real
 * insert to call or not: a fault injected where a stream or a placeholder is written. [find] is called before each
 * read of a log collection by a filter, [reserve] before each reservation of a block in the partition counters, and
 * [record] before each insert of a record into the log's or the timers' registry: each throws to fail it.
 */
internal fun faulty(
    many: (List<Document>, (List<Document>) -> Unit) -> Unit = { documents, insert -> insert(documents) },
    find: (Bson) -> Unit = {},
    reserve: () -> Unit = {},
    record: (Document) -> Unit = {},
    insert: (Document, () -> Unit) -> Unit = { _, write -> write() },
): MongoClient {
    val real = MongoLogFixture.client
    val faults = Faults(insert, many, find, reserve, record)
    return object : MongoClient by real {
        override fun getDatabase(databaseName: String): MongoDatabase =
            FaultyDatabase(real.getDatabase(databaseName), faults)
    }
}

private class Faults(
    val insert: (Document, () -> Unit) -> Unit,
    val many: (List<Document>, (List<Document>) -> Unit) -> Unit,
    val find: (Bson) -> Unit,
    val reserve: () -> Unit,
    val record: (Document) -> Unit,
)

/**
 * A database whose aggregate log collections are [Faulty] and whose partition counters are [FaultyCounters], also once
 * it is given a timeout, a write concern or a read preference (`MongoDatabases.of` gives all three).
 */
private class FaultyDatabase(private val real: MongoDatabase, private val faults: Faults) : MongoDatabase by real {
    override fun withTimeout(timeout: Long, timeUnit: TimeUnit): MongoDatabase =
        FaultyDatabase(real.withTimeout(timeout, timeUnit), faults)

    override fun withWriteConcern(writeConcern: WriteConcern): MongoDatabase =
        FaultyDatabase(real.withWriteConcern(writeConcern), faults)

    override fun withReadPreference(readPreference: ReadPreference): MongoDatabase =
        FaultyDatabase(real.withReadPreference(readPreference), faults)

    override fun getCollection(collectionName: String): MongoCollection<Document> {
        val collection = real.getCollection(collectionName)
        return when {
            collectionName in REGISTRIES -> FaultyRegistry(collection, faults)
            !collectionName.startsWith("wow_log__") -> collection
            collectionName.endsWith("__partition") -> FaultyCounters(collection, faults)
            else -> Faulty(collection, faults)
        }
    }
}

private class Faulty(
    private val real: MongoCollection<Document>,
    private val faults: Faults,
) : MongoCollection<Document> by real {
    override fun withWriteConcern(writeConcern: WriteConcern) = Faulty(real.withWriteConcern(writeConcern), faults)

    override fun withReadConcern(readConcern: ReadConcern) = Faulty(real.withReadConcern(readConcern), faults)

    override fun withTimeout(timeout: Long, timeUnit: TimeUnit) = Faulty(real.withTimeout(timeout, timeUnit), faults)

    override fun insertMany(documents: List<Document>, options: InsertManyOptions): InsertManyResult {
        var result: InsertManyResult? = null
        faults.many(documents) { written -> result = real.insertMany(written, options) }
        return result ?: throw MongoSocketReadTimeoutException("no reply", ServerAddress(), IOException("timed out"))
    }

    override fun insertOne(document: Document): InsertOneResult {
        var result: InsertOneResult? = null
        faults.insert(document) { result = real.insertOne(document) }
        return result ?: throw MongoSocketReadTimeoutException("no reply", ServerAddress(), IOException("timed out"))
    }

    override fun find(filter: Bson): FindIterable<Document> {
        faults.find(filter)
        return real.find(filter)
    }
}

/** The partition counters, whose block reservations (a pipeline `findOneAndUpdate`) go through [Faults.reserve]. */
private class FaultyCounters(
    private val real: MongoCollection<Document>,
    private val faults: Faults,
) : MongoCollection<Document> by real {
    override fun withWriteConcern(writeConcern: WriteConcern) =
        FaultyCounters(real.withWriteConcern(writeConcern), faults)

    override fun withReadConcern(readConcern: ReadConcern) = FaultyCounters(real.withReadConcern(readConcern), faults)

    override fun withTimeout(timeout: Long, timeUnit: TimeUnit) =
        FaultyCounters(real.withTimeout(timeout, timeUnit), faults)

    override fun findOneAndUpdate(filter: Bson, update: List<Bson>, options: FindOneAndUpdateOptions): Document? {
        faults.reserve()
        return real.findOneAndUpdate(filter, update, options)
    }
}

/** The log's and the timers' registries, whose records' inserts go through [Faults.record] first: it throws to fail one. */
private class FaultyRegistry(
    private val real: MongoCollection<Document>,
    private val faults: Faults,
) : MongoCollection<Document> by real {
    override fun withWriteConcern(
        writeConcern: WriteConcern
    ) = FaultyRegistry(real.withWriteConcern(writeConcern), faults)

    override fun withReadConcern(readConcern: ReadConcern) = FaultyRegistry(real.withReadConcern(readConcern), faults)

    override fun withTimeout(timeout: Long, timeUnit: TimeUnit) = FaultyRegistry(
        real.withTimeout(timeout, timeUnit),
        faults
    )

    override fun insertOne(document: Document): InsertOneResult {
        faults.record(document)
        return real.insertOne(document)
    }
}

private val REGISTRIES = setOf("wow_log_aggregate", "wow_timer_aggregate")
