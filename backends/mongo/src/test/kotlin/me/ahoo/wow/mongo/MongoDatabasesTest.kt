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

import com.mongodb.ReadPreference
import com.mongodb.WriteConcern
import com.mongodb.client.MongoClients
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** The database handle every store reads and writes through: the client's defaults cannot weaken it. */
class MongoDatabasesTest {
    @Test
    fun `a store's handle reads the primary and writes to a majority, whatever the client's defaults`() {
        // No server is needed: the client connects lazily, and only the handle's settings are read.
        MongoClients.create("mongodb://localhost:1/?readPreference=secondaryPreferred&w=1").use { client ->
            client.getDatabase("wow").readPreference.assert().isEqualTo(ReadPreference.secondaryPreferred())
            val database = MongoDatabases.of(client, "wow")
            database.readPreference.assert().isEqualTo(ReadPreference.primary())
            database.writeConcern.assert().isEqualTo(WriteConcern.MAJORITY)
            database.getCollection("wow_log").readPreference.assert().isEqualTo(ReadPreference.primary())
        }
    }
}
