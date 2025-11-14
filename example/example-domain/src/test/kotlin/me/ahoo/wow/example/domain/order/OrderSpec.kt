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

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.api.event.AggregateDeleted
import me.ahoo.wow.command.CommandValidationException
import me.ahoo.wow.event.DomainEventException
import me.ahoo.wow.example.api.order.AddressChanged
import me.ahoo.wow.example.api.order.ChangeAddress
import me.ahoo.wow.example.api.order.CreateOrder
import me.ahoo.wow.example.api.order.OrderCreated
import me.ahoo.wow.example.api.order.OrderPaid
import me.ahoo.wow.example.api.order.OrderPayDuplicated
import me.ahoo.wow.example.api.order.OrderReceived
import me.ahoo.wow.example.api.order.OrderShipped
import me.ahoo.wow.example.api.order.PayOrder
import me.ahoo.wow.example.api.order.ReceiptOrder
import me.ahoo.wow.example.api.order.ShipOrder
import me.ahoo.wow.example.api.order.ShippingAddress
import me.ahoo.wow.example.domain.order.DefaultCreateOrderSpec.InventoryShortageException
import me.ahoo.wow.example.domain.order.DefaultCreateOrderSpec.PriceInconsistencyException
import me.ahoo.wow.example.domain.order.OrderFixture.SHIPPING_ADDRESS
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.command.IllegalAccessDeletedAggregateException
import me.ahoo.wow.test.AggregateSpec
import reactor.kotlin.core.publisher.toMono
import java.math.BigDecimal

class OrderSpec : AggregateSpec<Order, OrderState>({
    on {
        val ownerId = generateGlobalId()
        val orderItem =
            CreateOrder.Item(
                productId = generateGlobalId(),
                price = BigDecimal.valueOf(10),
                quantity = 10,
            )
        val orderItems = listOf(orderItem)

        givenOwnerId(ownerId)

        // Mock services inline
        val inventoryService = object : me.ahoo.wow.example.domain.order.infra.InventoryService {
            override fun getInventory(productId: String) =
                orderItems
                    .filter { it.productId == productId }
                    .map { it.quantity }
                    .first()
                    .toMono()
        }
        val pricingService = object : me.ahoo.wow.example.domain.order.infra.PricingService {
            override fun getProductPrice(productId: String) =
                orderItems
                    .filter { it.productId == productId }
                    .map { it.price }
                    .first()
                    .toMono()
        }

        inject {
            register(DefaultCreateOrderSpec(inventoryService, pricingService))
        }

        val totalAmount = orderItem.price.multiply(BigDecimal.valueOf(orderItem.quantity.toLong()))

        whenCommand(CreateOrder(orderItems, SHIPPING_ADDRESS, false)) {
            expectNoError()
            expectEventType(OrderCreated::class)
            expectStateAggregate {
                aggregateId.tenantId.assert().isNotNull()
                ownerId.assert().isEqualTo(ownerId)
            }
            expectState {
                id.assert().isNotNull()
                address.assert().isEqualTo(SHIPPING_ADDRESS)
                items.assert().hasSize(1)
                status.assert().isEqualTo(OrderStatus.CREATED)
                totalAmount.assert().isEqualTo(totalAmount)
            }

            // Fork: Test Pay Order after successful creation
            fork("Pay Order") {
                val payOrder = PayOrder(generateGlobalId(), totalAmount)
                whenCommand(payOrder) {
                    expectNoError()
                    expectEventType(OrderPaid::class)
                    expectState {
                        paidAmount.assert().isEqualTo(totalAmount)
                        status.assert().isEqualTo(OrderStatus.PAID)
                    }

                    // Nested Fork: Test Ship Order after payment
                    fork("Ship Order") {
                        val shipOrder = ShipOrder(stateAggregate.aggregateId.id)
                        whenCommand(shipOrder) {
                            expectNoError()
                            expectEventType(OrderShipped::class)
                            expectState {
                                status.assert().isEqualTo(OrderStatus.SHIPPED)
                            }

                            // Nested Fork: Test Receipt Order after shipping
                            fork("Receipt Order") {
                                val receiptOrder = ReceiptOrder(stateAggregate.aggregateId.id)
                                whenCommand(receiptOrder) {
                                    expectNoError()
                                    expectEventType(OrderReceived::class)
                                    expectState {
                                        status.assert().isEqualTo(OrderStatus.RECEIVED)
                                    }
                                }
                            }
                        }
                    }

                    // Fork: Test Duplicate Payment (should fail)
                    fork("Duplicate Payment") {
                        val duplicatePayOrder = PayOrder(generateGlobalId(), totalAmount)
                        whenCommand(duplicatePayOrder) {
                            expectErrorType(DomainEventException::class)
                            expectEventType(OrderPayDuplicated::class)
                            expectState {
                                // State should remain unchanged
                                paidAmount.assert().isEqualTo(totalAmount)
                                status.assert().isEqualTo(OrderStatus.PAID)
                            }
                        }
                    }

                    // Fork: Test Over Payment
                    fork("Over Payment") {
                        val overPayAmount = totalAmount.plus(BigDecimal.ONE)
                        val overPayOrder = PayOrder(generateGlobalId(), overPayAmount)
                        whenCommand(overPayOrder) {
                            expectEventType(OrderPayDuplicated::class)
                            expectState {
                                // State should remain unchanged
                                paidAmount.assert().isEqualTo(totalAmount)
                                status.assert().isEqualTo(OrderStatus.PAID)
                            }
                        }
                    }
                }
            }

            // Fork: Test Ship Order before payment (should fail)
            fork("Ship Before Payment") {
                val shipOrder = ShipOrder(stateAggregate.aggregateId.id)
                whenCommand(shipOrder) {
                    expectErrorType(IllegalStateException::class)
                    expectState {
                        // State should remain unchanged
                        paidAmount.assert().isEqualTo(BigDecimal.ZERO)
                        status.assert().isEqualTo(OrderStatus.CREATED)
                    }
                }
            }

            // Fork: Test Change Address
            fork("Change Address") {
                val newAddress = ShippingAddress("上海市", "上海市", "浦东新区", "张江高科", "")
                val changeAddress = ChangeAddress(newAddress)
                whenCommand(changeAddress) {
                    expectNoError()
                    expectEventType(AddressChanged::class)
                    expectState {
                        address.assert().isEqualTo(newAddress)
                        status.assert().isEqualTo(OrderStatus.CREATED) // Status unchanged
                    }
                }
            }

            // Fork: Test Delete Order
            fork("Delete Order") {
                whenCommand(DefaultDeleteAggregate) {
                    expectNoError()
                    expectEventType(AggregateDeleted::class)
                    expectStateAggregate {
                        deleted.assert().isTrue()
                    }

                    // Nested Fork: Test operations on deleted order (should fail)
                    fork("Operate on Deleted Order") {
                        val payOrder = PayOrder(generateGlobalId(), totalAmount)
                        whenCommand(payOrder) {
                            expectErrorType(IllegalAccessDeletedAggregateException::class)
                            expectStateAggregate {
                                deleted.assert().isTrue()
                            }
                        }
                    }
                }
            }
        }
    }

    on {
        inject {
            register(mockk<CreateOrderSpec>(), "createOrderSpec")
        }

        whenCommand(CreateOrder(listOf(), SHIPPING_ADDRESS, false)) {
            expectErrorType(CommandValidationException::class)
            expectStateAggregate {
                initialized.assert().isFalse()
            }
        }
    }

    on {
        inject {
            register(
                DefaultCreateOrderSpec(
                    inventoryService = mockk(),
                    pricingService = mockk(),
                ),
            )
        }

        whenCommand(
            CreateOrder(
                listOf(CreateOrder.Item(generateGlobalId(), BigDecimal.TEN, 1)),
                ShippingAddress("US", "US", "US", "US", ""),
                false,
            ),
        ) {
            expectErrorType(IllegalArgumentException::class)
            expectStateAggregate {
                initialized.assert().isFalse()
            }
        }
    }

    on {
        val orderItem =
            CreateOrder.Item(
                productId = generateGlobalId(),
                price = BigDecimal.valueOf(10),
                quantity = 10,
            )
        val orderItems = listOf(orderItem)

        val inventoryService = object : me.ahoo.wow.example.domain.order.infra.InventoryService {
            override fun getInventory(productId: String) =
                orderItems
                    .filter { it.productId == productId }
                    .map { it.quantity - 1 }
                    .first()
                    .toMono()
        }
        val pricingService = object : me.ahoo.wow.example.domain.order.infra.PricingService {
            override fun getProductPrice(productId: String) =
                orderItems
                    .filter { it.productId == productId }
                    .map { it.price }
                    .first()
                    .toMono()
        }

        inject {
            register(DefaultCreateOrderSpec(inventoryService, pricingService))
        }

        whenCommand(CreateOrder(orderItems, SHIPPING_ADDRESS, false)) {
            expectErrorType(InventoryShortageException::class)
            expectStateAggregate {
                initialized.assert().isFalse()
            }
        }
    }

    on {
        val orderItem =
            CreateOrder.Item(
                productId = generateGlobalId(),
                price = BigDecimal.valueOf(10),
                quantity = 10,
            )
        val orderItems = listOf(orderItem)

        val inventoryService = object : me.ahoo.wow.example.domain.order.infra.InventoryService {
            override fun getInventory(productId: String) =
                orderItems
                    .filter { it.productId == productId }
                    .map { it.quantity }
                    .first()
                    .toMono()
        }
        val pricingService = object : me.ahoo.wow.example.domain.order.infra.PricingService {
            override fun getProductPrice(productId: String) =
                orderItems
                    .filter { it.productId == productId }
                    .map { it.price.plus(BigDecimal.ONE) }
                    .first()
                    .toMono()
        }

        inject {
            register(DefaultCreateOrderSpec(inventoryService, pricingService))
        }

        whenCommand(CreateOrder(orderItems, SHIPPING_ADDRESS, false)) {
            expectErrorType(PriceInconsistencyException::class)
        }
    }
})
