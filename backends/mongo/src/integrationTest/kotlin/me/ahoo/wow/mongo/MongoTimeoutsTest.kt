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

import com.mongodb.MongoException
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.mongo.subscription.MongoCheckpointStore
import me.ahoo.wow.spi.StorageUnavailableException
import org.bson.Document
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class MongoTimeoutsTest {
    @Test
    fun `a call that outlasts its timeout fails as transient, a write's outcome unknown`() {
        // A server that does not answer in time stands for a reply lost on the way: the call fails instead of waiting.
        val database = MongoDatabases.of(MongoLogFixture.client, MongoLogFixture.DATABASE, SHORT)
        val slow = database.getCollection("wow_timeouts_probe").also { it.insertOne(Document("n", 1)) }
        val failure = assertThrownBy<MongoException> {
            slow.find(Document("\$where", "sleep($SLEEP_MS) || true")).first()
        }.actual()
        MongoFailures.isTransient(failure).assert().isTrue()
        MongoFailures.isWriteUnknown(failure).assert().isTrue()
    }

    @Test
    fun `a store's calls carry its operation timeout, its schema's commands the longer one`(): Unit = runBlocking {
        val store = MongoCheckpointStore(MongoLogFixture.client, MongoLogFixture.DATABASE, operationTimeout = 1.milliseconds)
        // The schema's commands have five minutes: creating it succeeds.
        store.createSchema()
        // A read has a millisecond, which no round trip fits in.
        assertThrownBy<StorageUnavailableException> { runBlocking { store.load("timeouts", "probe", 0) } }
        MongoTimeouts.DDL.inWholeMinutes.assert().isEqualTo(5)
        MongoTimeouts.OPERATION.inWholeSeconds.assert().isEqualTo(30)
    }

    private companion object {
        val SHORT = 200.milliseconds
        const val SLEEP_MS = 2_000
    }
}
