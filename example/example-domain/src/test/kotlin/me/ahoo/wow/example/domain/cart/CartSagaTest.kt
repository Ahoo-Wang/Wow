package me.ahoo.wow.example.domain.cart

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.example.api.cart.RemoveCartItem
import me.ahoo.wow.example.api.order.OrderCreated
import me.ahoo.wow.example.api.order.OrderItem
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.test.StatelessSagaVerifier.sagaVerifier
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CartSagaTest {

    @Test
    fun onOrderCreated() {
        val orderItem = OrderItem(
            GlobalIdGenerator.generateAsString(),
            GlobalIdGenerator.generateAsString(),
            BigDecimal.valueOf(10),
            10,
        )
        sagaVerifier<CartSaga>()
            .`when`(
                mockk<OrderCreated> {
                    every {
                        customerId
                    } returns "customerId"
                    every {
                        items
                    } returns listOf(orderItem)
                },
            )
            .expectCommandBody<RemoveCartItem> {
                assertThat(it.customerId, equalTo("customerId"))
                assertThat(it.productIds, hasSize(1))
                assertThat(it.productIds.first(), equalTo(orderItem.productId))
            }
            .verify()
    }
}
