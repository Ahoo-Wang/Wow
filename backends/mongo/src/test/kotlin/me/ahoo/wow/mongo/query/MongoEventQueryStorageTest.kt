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

package me.ahoo.wow.mongo.query

import com.mongodb.ReadConcern
import com.mongodb.ReadPreference
import com.mongodb.client.MongoClients
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import org.junit.jupiter.api.Test

/** The `events` queries read the log as every reader of it does: what a majority has committed. */
class MongoEventQueryStorageTest {
    @Test
    fun `the log collections the events queries read are read at majority, off the primary when asked`() {
        // The client connects on its first call, which these collections never make.
        MongoClients.create("mongodb://localhost:1/?readConcernLevel=local").use { client ->
            val type = AggregateType("shop", "order")
            val primary = MongoEventQueryStorage(client, "wow", "shop").collectionOf(type)
            primary.readConcern.assert().isEqualTo(ReadConcern.MAJORITY)
            primary.namespace.collectionName.assert().isEqualTo("wow_log__shop__order")
            val secondary = MongoEventQueryStorage(
                client,
                "wow",
                "shop",
                readPreference = ReadPreference.secondaryPreferred(),
            ).collectionOf(type)
            secondary.readConcern.assert().isEqualTo(ReadConcern.MAJORITY)
            secondary.readPreference.assert().isEqualTo(ReadPreference.secondaryPreferred())
        }
    }
}
