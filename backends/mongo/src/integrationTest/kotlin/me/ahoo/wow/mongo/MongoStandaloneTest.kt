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

import com.mongodb.client.MongoClients
import me.ahoo.test.asserts.assert
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.mongodb.MongoDBContainer

/** A log needs a replica set or a sharded cluster outside development: it writes with majority. */
class MongoStandaloneTest {
    @Test
    fun `a log on a standalone mongod refuses to open, unless the node runs for development`() {
        MongoDBContainer(WowContainers.MONGO_IMAGE).use { standalone ->
            standalone.start()
            MongoClients.create(standalone.connectionString).use { client ->
                val refused = assertThrows<IllegalStateException> {
                    MongoStore(StoreConfig.of("main", MongoStoreProvider.TYPE), client, "standalone")
                        .log("standalone", 2)
                }
                refused.message.assert().contains(
                    "MongoDB needs a replica set or a sharded cluster: the log writes with majority",
                    "wow.stores.main",
                )
                MongoStore(StoreConfig.of("main", MongoStoreProvider.TYPE, development = true), client, "standalone")
                    .log("standalone", 2)
            }
        }
        // A replica set opens it.
        MongoStore(StoreConfig.of("main", MongoStoreProvider.TYPE), MongoLogFixture.client, MongoLogFixture.DATABASE)
            .log(MongoLogFixture.newContext("replicated"), 2)
    }
}
