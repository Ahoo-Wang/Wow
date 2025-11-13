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
 * Marks a class as an event processor component.
 *
 * Event processors are responsible for handling domain events published by aggregates.
 * They can perform cross-aggregate operations, update read models, send notifications,
 * or trigger external system integrations.
 *
 * Event processors are automatically registered as Spring components and discovered
 * by the framework for event routing and processing.
 *
 * Example usage:
 * ```kotlin
 * @EventProcessor
 * class OrderEventProcessor {
 *
 *     @OnEvent
 *     fun onOrderCreated(event: OrderCreated) {
 *         // Update inventory
 *         inventoryService.reserveItems(event.items)
 *
 *         // Send confirmation email
 *         emailService.sendOrderConfirmation(event.customerId, event.orderId)
 *     }
 *
 *     @OnEvent
 *     fun onOrderShipped(event: OrderShipped) {
 *         // Update shipping records
 *         shippingService.recordShipment(event.orderId, event.trackingNumber)
 *     }
 * }
 * ```
 * @see OnEvent for marking event handler methods within processors
 * @see ProjectionProcessor for read model projectors
 * @see StatelessSaga for process managers
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
@Component
annotation class EventProcessor
