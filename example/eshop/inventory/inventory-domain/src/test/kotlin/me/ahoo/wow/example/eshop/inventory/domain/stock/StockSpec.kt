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

package me.ahoo.wow.example.eshop.inventory.domain.stock

import me.ahoo.test.asserts.assert
import me.ahoo.wow.example.eshop.inventory.api.InventoryContext
import me.ahoo.wow.example.eshop.inventory.api.stock.ReceiveStock
import me.ahoo.wow.example.eshop.inventory.api.stock.StockReceived
import me.ahoo.wow.kernel.test.aggregateSpec
import org.junit.jupiter.api.Test

/** The stock on the kernel, through the model the compiler generated. */
class StockSpec {
    @Test
    fun `receiving the first stock should create it`() {
        aggregateSpec(stockModel(InventoryContext.NAME), id = "stock-1") {
            whenCommand(ReceiveStock(10)) {
                expectEvents(StockReceived(10))
                expectState {
                    available.assert().isEqualTo(10)
                }
            }
        }
    }
}
