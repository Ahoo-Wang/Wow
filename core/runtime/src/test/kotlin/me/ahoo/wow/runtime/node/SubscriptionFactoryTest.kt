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

package me.ahoo.wow.runtime.node

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The state readers factories declare, alone and combined, and what a sourced subscription must read. */
class SubscriptionFactoryTest {
    private val order = AggregateType("sales", "order")
    private val invoice = AggregateType("sales", "invoice")

    /** Names [types] as its view types, and reads them under its class's name by default. */
    private class Viewing(private val types: Set<AggregateType>) : SubscriptionFactory {
        override fun subscriptions(context: ContextNode): List<Subscription> = emptyList()

        override fun viewTypes(context: String, models: List<AggregateModel<*>>): Set<AggregateType> = types
    }

    @Test
    fun `a factory naming view types reads them under its class's name, and one naming none reads nothing`() {
        Viewing(setOf(order)).stateReaders("sales", emptyList())
            .assert().isEqualTo(mapOf(Viewing::class.java.name to setOf(order)))
        Viewing(emptySet()).stateReaders("sales", emptyList()).assert().isEmpty()
    }

    @Test
    fun `two factories reading under one name read the types of both`() {
        val combined = Viewing(setOf(order)) + Viewing(setOf(invoice))
        combined.stateReaders("sales", emptyList())
            .assert().isEqualTo(mapOf(Viewing::class.java.name to setOf(order, invoice)))
    }

    @Test
    fun `no factory is none, and several are combined in their order`() {
        SubscriptionFactory.of(emptyList()).assert().isSameAs(SubscriptionFactory.NONE)
        SubscriptionFactory.of(
            listOf(Viewing(setOf(order)), Viewing(setOf(invoice)))
        ).stateReaders("sales", emptyList())
            .assert().isEqualTo(mapOf(Viewing::class.java.name to setOf(order, invoice)))
    }

    @Test
    fun `a sourced subscription reads at least one published stream`() {
        val subscription = Subscription("projector", SubscriptionFilter("sales")) { }
        assertThrows<IllegalArgumentException> { SourcedSubscription(subscription, emptySet()) }
    }
}
