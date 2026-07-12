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
package me.ahoo.wow.messaging

import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.toNamedAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MessageSubscriptionTest {
    private val namedAggregate = "test.aggregate".toNamedAggregate()

    @Test
    fun `single aggregate constructor uses default receiver group`() {
        val subscription = MessageSubscription(namedAggregate)

        subscription.namedAggregates.assert().isEqualTo(setOf(namedAggregate))
        subscription.receiverGroup.assert().isEqualTo(MessageSubscription.DEFAULT_RECEIVER_GROUP)
    }

    @Test
    fun `constructor rejects blank receiver group`() {
        assertThrows<IllegalArgumentException> {
            MessageSubscription(namedAggregate, receiverGroup = " ")
        }.message.assert().isEqualTo("receiverGroup must not be blank.")
    }
}
