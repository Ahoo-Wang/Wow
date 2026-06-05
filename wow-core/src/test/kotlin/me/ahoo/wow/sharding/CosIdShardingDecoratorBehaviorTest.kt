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

import me.ahoo.cosid.sharding.PreciseSharding
import me.ahoo.test.asserts.assert
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import org.junit.jupiter.api.Test

internal class CosIdShardingDecoratorBehaviorTest {

    @Test
    fun `decorator should hash aggregate id string and delegate to numeric sharding`() {
        val seen = mutableListOf<Long>()
        val delegate = PreciseSharding<Long> {
            seen += it
            "node-$it"
        }
        val sharding = CosIdShardingDecorator(delegate) { id ->
            id.removePrefix("order-").toLong()
        }

        sharding.sharding(MaterializedNamedAggregate("sales", "Order").aggregateId("order-42"))
            .assert().isEqualTo("node-42")
        seen.assert().isEqualTo(listOf(42L))
    }

    @Test
    fun `decorator should use global id parser sequence as default hash`() {
        val id = generateGlobalId()
        val expectedSequence = GlobalIdGenerator.stateParser.asState(id).sequence.toLong()
        val seen = mutableListOf<Long>()
        val delegate = PreciseSharding<Long> {
            seen += it
            "node-$it"
        }
        val sharding = CosIdShardingDecorator(delegate)

        sharding.sharding(MaterializedNamedAggregate("sales", "Order").aggregateId(id))
            .assert().isEqualTo("node-$expectedSequence")
        seen.assert().isEqualTo(listOf(expectedSequence))
    }
}
