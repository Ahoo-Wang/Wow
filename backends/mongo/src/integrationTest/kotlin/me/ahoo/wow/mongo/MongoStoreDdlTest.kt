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

package me.ahoo.wow.mongo

import com.mongodb.client.model.IndexOptions
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.mongo.log.MongoEventLogOptions
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.mongo.timer.MongoTimerStore
import me.ahoo.wow.spi.Ddl
import org.bson.Document
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The stores' DDL modes (review A5-10): `auto` creates, `validate` only checks, `none` leaves the schema alone. */
class MongoStoreDdlTest {
    private val client = MongoLogFixture.client
    private val database = MongoLogFixture.DATABASE

    @Test
    fun `validate refuses a missing log, and passes once auto created it`() {
        val context = MongoLogFixture.newContext("ddl")
        val validating = MongoEventLog(client, database, context, 2, options = MongoEventLogOptions(ddl = Ddl.VALIDATE))
        assertThrows<IllegalStateException> { validating.createSchema() }.message.assert()
            .contains("does not exist, and the store only validates its schema")

        MongoLogFixture.log(2, context, listOf("order"))
        validating.createSchema()
        validating.createCollection("order")
        assertThrows<IllegalStateException> { validating.createCollection("cart") }.message.assert()
            .contains("does not exist, and the store only validates its schema")
    }

    @Test
    fun `validate refuses a missing index`() {
        val context = MongoLogFixture.newContext("ddl")
        val ref = AggregateType(context, "order").ref("t", "o-1")
        val timers = MongoTimerStore(client, database, context, 4)
        timers.createSchema()
        runBlocking { timers.find(ref, "k") }
        client.getDatabase(database).getCollection(timers.collectionName("order")).dropIndexes()
        assertThrows<IllegalStateException> {
            runBlocking { MongoTimerStore(client, database, context, 4, ddl = Ddl.VALIDATE).find(ref, "k") }
        }.message.assert().contains("does not exist, and the store only validates its schema")
    }

    @Test
    fun `validate refuses a version key that is not unique, naming the difference`() {
        // A version key made by hand without `unique` passed by its name, and two streams committed at one version
        // of one aggregate (#6748).
        val context = MongoLogFixture.newContext("ddl")
        MongoLogFixture.log(1, context, listOf("order"))
        replaceIndex(context, "version_key", Document(LOG_VERSION_KEY), IndexOptions().name("version_key"))

        val validating = MongoEventLog(client, database, context, 1, options = MongoEventLogOptions(ddl = Ddl.VALIDATE))
        validating.createSchema()
        assertThrows<IllegalStateException> { validating.createCollection("order") }.message.assert()
            .contains("The index version_key of ${logCollection(context)} is not the one this version of Wow creates")
            .contains("it is not unique")
            .contains("its partial filter is none, not {\"version\": {\"\$exists\": true}}")
    }

    @Test
    fun `auto names an index of Wow's name and another definition, rather than failing on the server's conflict`() {
        val context = MongoLogFixture.newContext("ddl")
        MongoLogFixture.log(1, context, listOf("order"))
        replaceIndex(
            context,
            "request_key",
            Document("partition", 1).append("request_id", 1),
            IndexOptions().name("request_key").unique(true),
        )

        assertThrows<IllegalStateException> { MongoLogFixture.log(1, context, listOf("order")) }.message.assert()
            .contains("The index request_key of ${logCollection(context)} is not the one this version of Wow creates")
            .contains("its key is {\"partition\": 1, \"request_id\": 1}, not {\"partition\": 1, \"tenant_id\": 1")
    }

    @Test
    fun `validate accepts an index made by hand as Wow makes it, its directions written as doubles`() {
        // A shell writes 1.0 as readily as 1: the same index all the same.
        val context = MongoLogFixture.newContext("ddl")
        MongoLogFixture.log(1, context, listOf("order"))
        val keys = Document()
        LOG_VERSION_KEY.keys.forEach { keys[it] = 1.0 }
        replaceIndex(
            context,
            "version_key",
            keys,
            IndexOptions().name(
                "version_key"
            ).unique(true).partialFilterExpression(Document("version", Document("\$exists", true))),
        )

        MongoEventLog(client, database, context, 1, options = MongoEventLogOptions(ddl = Ddl.VALIDATE)).apply {
            createSchema()
            createCollection("order")
        }
    }

    @Test
    fun `validate names an index that holds Wow's keys under another name`() {
        val context = MongoLogFixture.newContext("ddl")
        MongoLogFixture.log(1, context, listOf("order"))
        replaceIndex(
            context,
            "version_key",
            Document(LOG_VERSION_KEY),
            IndexOptions().name(
                "by_version"
            ).unique(true).partialFilterExpression(Document("version", Document("\$exists", true))),
        )

        val validating = MongoEventLog(client, database, context, 1, options = MongoEventLogOptions(ddl = Ddl.VALIDATE))
        validating.createSchema()
        assertThrows<IllegalStateException> { validating.createCollection("order") }.message.assert()
            .contains(
                "The index version_key of ${logCollection(context)} is missing: its keys are the index by_version's"
            )
    }

    @Test
    fun `auto names an index that holds Wow's keys under another name`() {
        // The server refuses to create Wow's under its name (IndexOptionsConflict): the check names the other one.
        val context = MongoLogFixture.newContext("ddl")
        MongoLogFixture.log(1, context, listOf("order"))
        replaceIndex(
            context,
            "version_key",
            Document(LOG_VERSION_KEY),
            IndexOptions().name(
                "by_version"
            ).unique(true).partialFilterExpression(Document("version", Document("\$exists", true))),
        )

        assertThrows<IllegalStateException> { MongoLogFixture.log(1, context, listOf("order")) }.message.assert()
            .contains(
                "The index version_key of ${logCollection(context)} is missing: its keys are the index by_version's"
            )
    }

    /** Drops the index [name] of [context]'s `order` log collection and creates [keys] with [options] in its place. */
    private fun replaceIndex(context: String, name: String, keys: Document, options: IndexOptions) {
        client.getDatabase(database).getCollection(logCollection(context)).apply {
            dropIndex(name)
            createIndex(keys, options)
        }
    }

    private fun logCollection(context: String) = MongoNames.of("wow_log", context, "order")

    @Test
    fun `none creates and checks nothing`() {
        val context = MongoLogFixture.newContext("ddl")
        val timers = MongoTimerStore(client, database, context, 4, ddl = Ddl.NONE)
        timers.createSchema()
        (timers.collectionName("order") in client.getDatabase(database).listCollectionNames()).assert().isFalse()
    }

    private companion object {
        /** The log's version key, as Wow creates it: its fields in order. */
        val LOG_VERSION_KEY: Map<String, Any> =
            linkedMapOf("partition" to 1, "aggregate_id" to 1, "tenant_id" to 1, "version" to 1)
    }
}
