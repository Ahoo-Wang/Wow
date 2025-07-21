package me.ahoo.wow.example.domain.cart

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.example.api.cart.RemoveCartItem
import me.ahoo.wow.example.api.order.OrderCreated
import me.ahoo.wow.example.api.order.OrderItem
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.test.SagaVerifier.sagaVerifier
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CartSagaTest {

    @Test
    fun onOrderCreated() {
        val ownerId = generateGlobalId()
        val orderItem = OrderItem(
            id = generateGlobalId(),
            productId = generateGlobalId(),
            price = BigDecimal.valueOf(10),
            quantity = 10,
        )
        sagaVerifier<CartSaga>()
            .whenEvent(
                event = mockk<OrderCreated> {
                    every {
                        items
                    } returns listOf(orderItem)
                    every {
                        fromCart
                    } returns true
                },
                ownerId = ownerId
            )
            .expectCommandType(RemoveCartItem::class)
            .expectCommand<RemoveCartItem> {
                aggregateId.id.assert().isEqualTo(ownerId)
                body.productIds.assert().hasSize(1)
                body.productIds.assert().first().isEqualTo(orderItem.productId)
            }
            .verify()
    }

    @Test
    fun onOrderCreatedWhenNotFromCart() {
        val orderItem = OrderItem(
            id = generateGlobalId(),
            productId = generateGlobalId(),
            price = BigDecimal.valueOf(10),
            quantity = 10,
        )
        sagaVerifier<CartSaga>()
            .whenEvent(
                event = mockk<OrderCreated> {
                    every {
                        items
                    } returns listOf(orderItem)
                    every {
                        fromCart
                    } returns false
                },
                ownerId = generateGlobalId()
            )
            .expectNoCommand()
            .verify()
    }
}
