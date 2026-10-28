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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.mongo.timer.MongoTimerStore
import me.ahoo.wow.spi.Ddl
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The stores' DDL modes (review A5-10): `auto` creates, `validate` only checks, `none` leaves the schema alone. */
class MongoStoreDdlTest {
    private val client = MongoLogFixture.client
    private val database = MongoLogFixture.DATABASE

    @Test
    fun `validate refuses a missing log, and passes once auto created it`() {
        val context = MongoLogFixture.newContext("ddl")
        val validating = MongoEventLog(client, database, context, 2, ddl = Ddl.VALIDATE)
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
    fun `none creates and checks nothing`() {
        val context = MongoLogFixture.newContext("ddl")
        val timers = MongoTimerStore(client, database, context, 4, ddl = Ddl.NONE)
        timers.createSchema()
        (timers.collectionName("order") in client.getDatabase(database).listCollectionNames()).assert().isFalse()
    }
}
