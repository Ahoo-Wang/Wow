package me.ahoo.wow.example.domain.order

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.example.api.order.OrderCreated
import me.ahoo.wow.example.api.order.OrderItem
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
