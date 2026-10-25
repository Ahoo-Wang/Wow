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

import com.mongodb.client.MongoClient
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.spi.StoreDdl
import org.junit.jupiter.api.Test

class MongoShardingOptionTest {
    @Test
    fun `the sharding option is auto when not set, auto or off in any case, refused otherwise`() {
        MongoSharding.of(null).assert().isEqualTo(MongoSharding.AUTO)
        MongoSharding.of("Auto").assert().isEqualTo(MongoSharding.AUTO)
        MongoSharding.of("OFF").assert().isEqualTo(MongoSharding.OFF)
        assertThrownBy<IllegalArgumentException> { MongoSharding.of("on") }
    }

    @Test
    fun `a store shards only with sharding auto and ddl auto, asking the server only then`() {
        // A relaxed mock would answer; a strict one fails if asked: off, validate and none never ask.
        val client = mockk<MongoClient>()
        MongoSharding.OFF.shards(client, StoreDdl.AUTO).assert().isFalse()
        MongoSharding.AUTO.shards(client, StoreDdl.VALIDATE).assert().isFalse()
        MongoSharding.AUTO.shards(client, StoreDdl.NONE).assert().isFalse()
    }
}
