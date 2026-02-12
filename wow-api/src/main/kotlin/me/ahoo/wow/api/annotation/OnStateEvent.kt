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

const val DEFAULT_ON_STATE_EVENT_NAME = "onStateEvent"

/**
 * Marks a function as a state event handler for real-time state change notifications.
 *
 * Functions annotated with @OnStateEvent are triggered when aggregate state changes occur,
 * enabling reactive processing of state updates. Unlike @OnEvent which handles domain events, @OnStateEvent responds to state transitions and modifications.
 *
 * State event handlers are useful for:
 * - Real-time UI updates and notifications
 * - Cross-aggregate state synchronization
 * - External system integration based on state changes
 * - Audit logging of state modifications
 * - Cache invalidation and data consistency
 *
 * Example usage:
 * ```kotlin
 * @EventProcessor
 * class OrderStateProcessor {
 *
 *     @OnStateEvent  // Listen to all aggregates
 *     fun onAnyStateChange(changed: StateChanged, state: OrderState) {
 *         // React to any state change
 *         auditService.logStateChange(changed.aggregateId, changed.oldState, state)
 *     }
 *
 *     @OnStateEvent(value = ["order"])  // Only order aggregates
 *     fun onOrderStateChange(changed: StateChanged, state: OrderState) {
 *         if (state.status == OrderStatus.SHIPPED) {
 *             notificationService.sendShippingConfirmation(state.customerId)
 *         }
 *     }
 * }
 *
 * // Remote context (when state is not locally available)
 * @OnStateEvent
 * fun onRemoteStateEvent(changed: StateChanged, stateRecord: StateRecord<OrderState>) {
 *     val state = stateRecord.toState<OrderState>()
 *     // Process remote state changes
 * }
 * ```
 *
 * @param value Array of aggregate names to listen for state events from. If empty,
 *             listens to state events from all aggregates. Enables selective processing.
 *
 * @see OnEvent for domain event handlers
 * @see StateAggregate for state-based aggregates
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@OnMessage(FunctionKind.STATE_EVENT, DEFAULT_ON_STATE_EVENT_NAME)
@MustBeDocumented
annotation class OnStateEvent(
    /**
     * Names of aggregates to listen for state events from.
     *
     * When specified, the handler will only be invoked for state changes in aggregates
     * with matching names. This provides fine-grained control over which state changes
     * trigger the handler, improving performance and reducing unnecessary processing.
     *
     * If empty (default), the handler receives state events from all aggregates.
     */
    vararg val value: String
)
