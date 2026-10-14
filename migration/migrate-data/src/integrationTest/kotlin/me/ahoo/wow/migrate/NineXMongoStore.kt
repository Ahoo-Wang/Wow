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
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.tck.containers.WowContainers
import org.bson.Document
import java.util.concurrent.atomic.AtomicInteger

/**
 * A 9.x MongoDB event store in the shared container, written as 9.x's `wow-mongo` writes it: the stream's JSON as a
 * document of `<aggregateName>_event_stream`, its `id` as `_id`, a `size` field, and its decimals as `Decimal128`
 * (9.x reads floats as `BigDecimal`, which the driver stores as `Decimal128`).
 */
object NineXMongoStore {
    val client: MongoClient by lazy { MongoClients.create(WowContainers.mongo.replicaSetUrl) }

    private val databases = AtomicInteger()

    /** A fresh database holding the 9.x example's streams. */
    fun example(): MongoDatabase = client.getDatabase("ninex_example_${databases.incrementAndGet()}").also { database ->
        (NineXExample.ORDER_STREAMS + NineXExample.CART_STREAMS).forEach { insert(database, NineXExample.resource(it)) }
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
