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
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class LocalDeliveryReceiptTest {

    @Test
    fun `each route can confirm a delivery only once`() {
        val firstRoute = LocalDeliveryRouteTarget()
        val secondRoute = LocalDeliveryRouteTarget()
        val receipt = LocalDeliveryReceipt(setOf(firstRoute, secondRoute))
        val result = receipt.signal().toFuture()
        val firstTicket = checkNotNull(receipt.claim(firstRoute))
        val secondTicket = checkNotNull(receipt.claim(secondRoute))
        val firstExchange = LocalDeliveryReceiptTestExchange()
        firstExchange.attachLocalDeliveryTicket(firstTicket)

        receipt.claim(firstRoute).assert().isNull()
        receipt.claim(LocalDeliveryRouteTarget()).assert().isNull()
        firstExchange.confirmLocalDelivery()
        firstExchange.confirmLocalDelivery()
        firstExchange.rejectLocalDelivery()

        result.isDone.assert().isFalse()

        secondTicket.confirm()

        result.get(1, TimeUnit.SECONDS).assert().isTrue()
    }
}

private class LocalDeliveryReceiptTestExchange :
    me.ahoo.wow.messaging.handler.MessageExchange<
        LocalDeliveryReceiptTestExchange,
        TestNamedMessage,
        > {
    override val attributes: MutableMap<String, Any> = mutableMapOf()
    override val message: TestNamedMessage = TestNamedMessage()
}
