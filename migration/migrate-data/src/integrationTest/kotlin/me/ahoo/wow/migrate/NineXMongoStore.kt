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

package me.ahoo.wow.migrate

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.ReplaceOptions
import me.ahoo.wow.migrate.example.NineXCompensationExample
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.tck.containers.WowContainers
import org.bson.Document
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

/**
 * A 9.x MongoDB event store in the shared container, written as 9.x's `wow-mongo` writes it: the stream's JSON as a
 * document of `<aggregateName>_event_stream`, its `id` as `_id`, a `size` field, and its decimals as `Decimal128`
 * (9.x reads floats as `BigDecimal`, which the driver stores as `Decimal128`).
 */
object NineXMongoStore {
    val client: MongoClient by lazy { MongoClients.create(WowContainers.mongo.replicaSetUrl) }

    private val databases = AtomicInteger()

    /** A fresh database holding the 9.x example's streams and snapshots. */
    fun example(): MongoDatabase = client.getDatabase("ninex_example_${databases.incrementAndGet()}").also { database ->
        (NineXExample.ORDER_STREAMS + NineXExample.CART_STREAMS).forEach { insert(database, NineXExample.resource(it)) }
        NineXExample.SNAPSHOTS.forEach { saveSnapshot(database, NineXExample.resource(it)) }
    }

    /**
     * A fresh database of the 9.x compensation service holding the example's executions
     * ([NineXCompensationExample]): the snapshots of its `execution_failed` aggregate, as it stored them.
     */
    fun compensation(): MongoDatabase =
        client.getDatabase("ninex_compensation_${databases.incrementAndGet()}").also { database ->
            NineXCompensationExample.FIXTURES.indices.forEach { saveSnapshot(database, NineXCompensationExample.resource(it + 1)) }
        }

    /**
     * Saves the snapshot whose JSON is [text] into [database] as 9.x's `MongoSnapshotStore` does: a document of
     * `<aggregateName>_snapshot`, its `aggregateId` as `_id`, replacing the instance's previous snapshot.
     */
    fun saveSnapshot(database: MongoDatabase, text: String) {
        @Suppress("UNCHECKED_CAST")
        val snapshot = WowJson.mapper.readValue(text, LinkedHashMap::class.java) as LinkedHashMap<String, Any?>
        val document = Document(snapshot)
        document["_id"] = document.remove("aggregateId")
        database.getCollection("${snapshot["aggregateName"]}${MongoNineXSnapshotSource.SUFFIX}")
            .replaceOne(Document("_id", document["_id"]), document, ReplaceOptions().upsert(true))
    }

    /** Inserts the stream whose JSON is [text] into [database], as 9.x would have. */
    fun insert(database: MongoDatabase, text: String) {
        @Suppress("UNCHECKED_CAST")
        val stream = WowJson.mapper.readValue(text, LinkedHashMap::class.java) as LinkedHashMap<String, Any?>
        val document = Document(stream)
        document["_id"] = document.remove("id")
        document["size"] = (stream["body"] as List<*>).size
        database.getCollection("${stream["aggregateName"]}${MongoNineXEventSource.SUFFIX}").insertOne(document)
    }

    /**
     * Prepares [key] of the 9.x `PrepareKey` [name] in [database] as 9.x's `MongoPrepareKey.prepare` writes it:
     * `{_id: key, value, ttlAt: Date}` in `prepare_<name>`, the value a string or a document of its fields.
     */
    fun prepare(database: MongoDatabase, name: String, key: String, value: Any, ttlAt: Long) {
        database.getCollection("${MongoNineXReservationSource.PREFIX}$name")
            .insertOne(Document("_id", key).append("value", value).append("ttlAt", Date(ttlAt)))
    }

    /** The JSON of a 9.x stream following [text]'s: the order received, at the next version, committed at [createTime]. */
    fun received(text: String, createTime: Long): String {
        val stream = NineXStream.parse(text)
        return text
            .replace("\"${stream.id}\"", "\"received-stream\"")
            .replace("\"${stream.commandId}\"", "\"received-command\"")
            .replace("\"version\":${stream.version}", "\"version\":${stream.version + 1}")
            .replace("\"createTime\":${stream.createTime}", "\"createTime\":$createTime")
            .replace("ship_order", "receipt_order")
            .replace("order_shipped", "order_received")
            .replace("OrderShipped", "OrderReceived")
    }
}
