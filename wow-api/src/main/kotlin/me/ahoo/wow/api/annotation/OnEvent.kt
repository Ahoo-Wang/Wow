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

import me.ahoo.wow.api.messaging.function.FunctionKind
import java.lang.annotation.Inherited

const val DEFAULT_ON_EVENT_NAME = "onEvent"

/**
 * Marks a function as a domain event handler.
 *
 * Functions annotated with @OnEvent are invoked when domain events are published.
 * They allow aggregates to react to events from the same or different aggregates,
 * enabling complex business workflows and state synchronization.
 *
 * Event handlers are used for:
 * - Updating aggregate state based on events
 * - Implementing event sourcing within aggregates
 * - Cross-aggregate communication and coordination
 * - Maintaining derived state and projections
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
 *
 * @param value Array of aggregate names to listen for events from. If empty,
 *             listens to events from any aggregate. Used for filtering and routing.
 *
 * @see OnCommand for command handlers
 * @see Event for domain event classes
 * @see EventProcessor for external event processors
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@OnMessage(FunctionKind.EVENT, DEFAULT_ON_EVENT_NAME)
@MustBeDocumented
annotation class OnEvent(
    /**
     * Names of aggregates to listen for events from.
     *
     * When specified, the handler will only receive events published by aggregates
     * with matching names. This enables selective event processing and reduces
     * unnecessary handler invocations.
     *
     * If empty (default), the handler receives events from all aggregates.
     * Aggregate names are typically the lowercase class names or custom names
     * specified via @AggregateName.
     */
    vararg val value: String
)
