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

package me.ahoo.wow.example.domain.order.tradition

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.example.api.order.OrderCreated
import me.ahoo.wow.example.api.order.OrderItem
import me.ahoo.wow.example.domain.order.OrderSaga
import me.ahoo.wow.example.domain.order.OrderState
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.test.SagaVerifier
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class OrderSagaTest {
    private val orderItem = OrderItem(
        id = generateGlobalId(),
        productId = generateGlobalId(),
        price = BigDecimal.valueOf(10),
        quantity = 10,
    )

    @Test
    fun onOrderCreated() {
        SagaVerifier.sagaVerifier<OrderSaga>()
            .whenEvent(
                mockk<OrderCreated> {
                    every {
                        items
                    } returns listOf(orderItem)
                    every {
                        fromCart
                    } returns true
                },
            )
            .expectNoCommand()
            .verify()
    }

    @Test
    fun onOrderCreatedWithOrderState() {
        SagaVerifier.sagaVerifier<OrderSaga>()
            .whenEvent(
                event = mockk<OrderCreated> {
                    every {
                        items
                    } returns listOf(orderItem)
                    every {
                        fromCart
                    } returns true
                },
                state = mockk<OrderState>()
            )
            .expectNoCommand()
            .verify()
    }
}
