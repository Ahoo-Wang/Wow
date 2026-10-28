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
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.config.WowConfigurationException
import org.junit.jupiter.api.Test

class MongoShardingOptionTest {
    @Test
    fun `the sharding option is auto when not set, auto or none in any case, refused otherwise`() {
        val sharding = MongoStoreProvider.SHARDING
        sharding.default.assert().isEqualTo(MongoSharding.AUTO)
        sharding.values.assert().containsExactly("auto", "none")
        sharding.parse("Auto", "sharding").assert().isEqualTo(MongoSharding.AUTO)
        sharding.parse("NONE", "sharding").assert().isEqualTo(MongoSharding.NONE)
        assertThrownBy<WowConfigurationException> { sharding.parse("off", "sharding") }
    }

    @Test
    fun `a store shards only with sharding auto and ddl auto, asking the server only then`() {
        // A relaxed mock would answer; a strict one fails if asked: none, validate and none never ask.
        val client = mockk<MongoClient>()
        MongoSharding.NONE.shards(client, Ddl.AUTO).assert().isFalse()
        MongoSharding.AUTO.shards(client, Ddl.VALIDATE).assert().isFalse()
        MongoSharding.AUTO.shards(client, Ddl.NONE).assert().isFalse()
    }
}
