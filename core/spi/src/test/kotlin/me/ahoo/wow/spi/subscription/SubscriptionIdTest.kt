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

package me.ahoo.wow.spi.subscription

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

/** A subscription by its context and name: two contexts' subscriptions of one name are two subscriptions. */
class SubscriptionIdTest {
    @Test
    fun `two contexts' subscriptions of one name are different subscriptions`() {
        val shop = SubscriptionId.of("shop", "wow.snapshot")
        val stock = SubscriptionId.of("stock", "wow.snapshot")
        (shop == shop).assert().isTrue()
        shop.assert().isEqualTo(SubscriptionId.of("shop", "wow.snapshot"))
        shop.hashCode().assert().isEqualTo(SubscriptionId.of("shop", "wow.snapshot").hashCode())
        shop.assert().isNotEqualTo(stock)
        shop.assert().isNotEqualTo(SubscriptionId.of("shop", "Board.onOrder"))
        shop.assert().isNotEqualTo("shop.wow.snapshot")
        shop.key.assert().isEqualTo("shop.wow.snapshot")
        shop.toString().assert().isEqualTo(shop.key)
    }

    @Test
    fun `subscriptions are ordered by context, then by name`() {
        listOf(
            SubscriptionId.of("stock", "a"),
            SubscriptionId.of("shop", "b"),
            SubscriptionId.of("shop", "a"),
        ).sorted().map { it.key }.assert().containsExactly("shop.a", "shop.b", "stock.a")
    }

    @Test
    fun `a key splits at its first dot, a context's name having none`() {
        SubscriptionId.parse("shop.CartSaga.onOrderCreated").assert()
            .isEqualTo(SubscriptionId.of("shop", "CartSaga.onOrderCreated"))
        SubscriptionId.parse("shop").assert().isNull()
        SubscriptionId.parse(".name").assert().isNull()
        SubscriptionId.parse("shop.").assert().isNull()
    }

    @Test
    fun `a subscription needs a context's name and a name`() {
        assertThrownBy<IllegalArgumentException> { SubscriptionId.of(" ", "a") }
        assertThrownBy<IllegalArgumentException> { SubscriptionId.of("sh.op", "a") }
        assertThrownBy<IllegalArgumentException> { SubscriptionId.of("shop", " ") }
    }
}
