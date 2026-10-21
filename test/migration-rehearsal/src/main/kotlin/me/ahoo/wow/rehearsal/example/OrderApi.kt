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

package me.ahoo.wow.rehearsal.example

import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.Revision
import java.math.BigDecimal

enum class OrderStatus { CREATED, PAID, SHIPPED, RECEIVED }

data class ShippingAddress(
    val country: String,
    val province: String,
    val city: String,
    val district: String,
    val detail: String,
)

data class CreateOrderItem(val productId: String, val price: BigDecimal, val quantity: Int)

data class OrderItem(val id: String, val productId: String, val price: BigDecimal, val quantity: Int) {
    val totalPrice: BigDecimal
        get() = price.multiply(quantity.toBigDecimal())
}

@CreateAggregate
data class CreateOrder(val items: List<CreateOrderItem>, val address: ShippingAddress, val fromCart: Boolean)

data class ChangeAddress(val shippingAddress: ShippingAddress)

data class PayOrder(val paymentId: String, val amount: BigDecimal)

class ShipOrder

class ReceiptOrder

/** Revision 2: the 9.x `orderId` field is the stream's ref now; [upgradeOrderCreatedFrom1] reads the 9.x shape. */
@Revision(2)
data class OrderCreated(val items: List<OrderItem>, val address: ShippingAddress, val fromCart: Boolean)

data class AddressChanged(val shippingAddress: ShippingAddress)

data class OrderPaid(val amount: BigDecimal, val paid: Boolean)

class OrderShipped

class OrderReceived
