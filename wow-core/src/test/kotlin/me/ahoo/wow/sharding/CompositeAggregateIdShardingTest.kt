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
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class CompositeAggregateIdShardingTest {

    @Test
    fun `composite sharding should delegate by materialized aggregate id`() {
        val order = MaterializedNamedAggregate("sales", "Order")
        val sharding = CompositeAggregateIdSharding(
            mapOf(order to SingleAggregateIdSharding("order-node")),
        )

        sharding.sharding(order.aggregateId("order-1")).assert().isEqualTo("order-node")
    }

    @Test
    fun `composite sharding should fail when aggregate has no registration`() {
        val sharding = CompositeAggregateIdSharding(emptyMap())

        val exception = assertThrows<IllegalStateException> {
            sharding.sharding(MaterializedNamedAggregate("sales", "Order").aggregateId("order-1"))
        }

        exception.message.assert().contains("AggregateIdSharding not found")
    }
}
