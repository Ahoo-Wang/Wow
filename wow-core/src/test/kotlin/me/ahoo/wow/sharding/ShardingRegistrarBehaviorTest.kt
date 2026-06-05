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

package me.ahoo.wow.sharding

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.AggregateId
import org.junit.jupiter.api.Test

internal class ShardingRegistrarBehaviorTest {

    @Test
    fun `register should store named sharding by its name and return previous value`() {
        val registrar = SimpleShardingRegistrar()
        val first = NamedSharding("orders", "node-a")
        val second = NamedSharding("orders", "node-b")

        registrar.register(first).assert().isNull()
        registrar.register(second).assert().isSameAs(first)
        registrar["orders"].assert().isSameAs(second)
    }

    private data class NamedSharding(
        override val name: String,
        private val node: String,
    ) : NamedAggregateIdSharding {
        override fun sharding(shardingValue: AggregateId): String = node
    }
}
