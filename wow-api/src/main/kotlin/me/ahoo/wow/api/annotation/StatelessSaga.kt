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

package me.ahoo.wow.api.annotation

import org.springframework.stereotype.Component
import java.lang.annotation.Inherited

/**
 * Marks a class as a stateless saga for orchestrating complex business processes.
 *
 * Stateless sagas coordinate multiple aggregates and external services to implement
 * long-running business processes. Unlike stateful sagas, they don't maintain persistent
 * state between events, making them simpler but less flexible for complex orchestrations.
 *
 * Stateless sagas are ideal for:
 * - Simple sequential process orchestration
 * - Event-driven workflows without complex state
 * - Processes that can be restarted from any point
 * - High-throughput scenarios where state persistence is costly
 *
 *
 * Example usage:
 * ```kotlin
 * @StatelessSaga
 * class OrderFulfillmentSaga {
 *
 *     @OnEvent
 *     fun onOrderCreated(event: OrderCreated) {
 *         // Start fulfillment process
 *         inventoryService.reserveItems(event.orderId, event.items)
 *         shippingService.schedulePickup(event.orderId, event.shippingAddress)
 *     }
 *
 *     @OnEvent
 *     fun onPaymentConfirmed(event: PaymentConfirmed) {
 *         // Complete order when payment is confirmed
 *         orderService.markAsPaid(event.orderId)
 *         notificationService.sendOrderConfirmation(event.customerId)
 *     }
 *
 *     @OnEvent
 *     fun onInventoryShortage(event: InventoryShortage) {
 *         // Handle inventory issues
 *         orderService.cancelOrder(event.orderId, "Insufficient inventory")
 *         notificationService.sendCancellationNotice(event.customerId)
 *     }
 * }
 * ```
 *
 * @see EventProcessor for general event processing
 * @see OnEvent for event handler methods

 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
@Component
annotation class StatelessSaga
