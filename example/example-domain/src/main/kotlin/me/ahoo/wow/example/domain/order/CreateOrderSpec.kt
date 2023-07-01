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
package me.ahoo.wow.example.domain.order

import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.example.api.order.OrderItem
import me.ahoo.wow.example.domain.order.infra.InventoryService
import me.ahoo.wow.example.domain.order.infra.PricingService
import reactor.core.publisher.Mono
import java.math.BigDecimal

interface CreateOrderSpec {
    fun require(orderItem: OrderItem): Mono<OrderItem>
}

/**
 * 创建订单规范/业务规则/约束.
 *
 * @author ahoo wang
 */
@Name("createOrderSpec")
class DefaultCreateOrderSpec(
    private val inventoryService: InventoryService,
    private val pricingService: PricingService
) : CreateOrderSpec {

    override fun require(orderItem: OrderItem): Mono<OrderItem> {
        return Mono.zip(checkPrice(orderItem), checkInventory(orderItem))
            .thenReturn(orderItem)
    }

    /**
     * 预校验库存.
     */
    private fun checkInventory(orderItem: OrderItem): Mono<Int> {
        return inventoryService.getInventory(orderItem.productId)
            .doOnNext {
                if (orderItem.quantity > it) {
                    throw InventoryShortageException(orderItem, it)
                }
            }
    }

    /**
     * 验证商品客户端下单时的价格与最新的定价服务价格时否一致.
     */
    private fun checkPrice(orderItem: OrderItem): Mono<BigDecimal> {
        return pricingService.getProductPrice(orderItem.productId)
            .doOnNext { unitPrice ->
                if (orderItem.price != unitPrice) {
                    throw PriceInconsistencyException(orderItem, unitPrice)
                }
            }
    }

    class InventoryShortageException(val orderItem: OrderItem, val inventory: Int) : IllegalArgumentException(
        "item[$orderItem] is greater than the inventory quantity[$inventory].",
    )

    class PriceInconsistencyException(val orderItem: OrderItem, val price: BigDecimal) : IllegalArgumentException(
        "item[$orderItem]has expired, latest price[$price].",
    )
}
