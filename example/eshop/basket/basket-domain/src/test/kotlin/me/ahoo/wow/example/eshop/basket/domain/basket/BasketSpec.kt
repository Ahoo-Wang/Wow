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

package me.ahoo.wow.example.eshop.basket.domain.basket

import me.ahoo.test.asserts.assert
import me.ahoo.wow.example.eshop.basket.api.BasketContext
import me.ahoo.wow.example.eshop.basket.api.basket.AddBasketItem
import me.ahoo.wow.example.eshop.basket.api.basket.BasketItemAdded
import me.ahoo.wow.kernel.test.aggregateSpec
import org.junit.jupiter.api.Test

/** The basket on the kernel, through the model the compiler generated. */
class BasketSpec {
    @Test
    fun `adding the first item should create the basket`() {
        aggregateSpec(basketModel(BasketContext.NAME), id = "basket-1") {
            whenCommand(AddBasketItem("mug", 2)) {
                expectEvents(BasketItemAdded("mug", 2))
                expectState {
                    items.assert().containsEntry("mug", 2)
                }
            }
        }
    }
}
