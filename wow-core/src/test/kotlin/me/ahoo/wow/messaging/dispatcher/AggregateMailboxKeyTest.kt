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

package me.ahoo.wow.messaging.dispatcher

import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import org.junit.jupiter.api.Test

class AggregateMailboxKeyTest {
    private val order = MaterializedNamedAggregate("context", "order")
    private val cart = MaterializedNamedAggregate("context", "cart")
    private val otherContext = MaterializedNamedAggregate("other", "order")

    @Test
    fun `the tenant is not part of the key`() {
        val key = order.aggregateId("id", tenantId = "a").toMailboxKey()
        val otherTenant = order.aggregateId("id", tenantId = "b").toMailboxKey()

        key.assert().isEqualTo(otherTenant)
        key.hashCode().assert().isEqualTo(otherTenant.hashCode())
        key.assert().isEqualTo(key)
        key.toString().assert().isEqualTo("context.order@id")
    }

    @Test
    fun `context, aggregate name and ID are`() {
        val key = order.aggregateId("id").toMailboxKey()

        key.assert().isNotEqualTo(order.aggregateId("other").toMailboxKey())
        key.assert().isNotEqualTo(cart.aggregateId("id").toMailboxKey())
        key.assert().isNotEqualTo(otherContext.aggregateId("id").toMailboxKey())
        key.assert().isNotEqualTo(order.aggregateId("id"))
    }
}
