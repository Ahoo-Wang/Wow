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

const val DEFAULT_ON_SOURCING_NAME = "onSourcing"

/**
 * Marks a function as a state sourcing handler.
 *
 * Functions annotated with @OnSourcing are responsible for rebuilding aggregate state
 * from historical events during event sourcing. They transform event streams into
 * current aggregate state, enabling state recovery and reconstruction.
 *
 * State sourcing handlers:
 * - Are called during aggregate initialization from event store
 * - Must be deterministic (same events always produce same state)
 * - Should not have side effects (no external system calls)
 * - Are applied in event order to build current state
 * - Enable state snapshots and performance optimization
 *
 * Example usage:
 * ```kotlin
 * class OrderState{
 *
 *     @OnSourcing
 *     fun onOrderCreated(event: OrderCreated) {
 *         this.status = OrderStatus.CREATED
 *         this.items.addAll(event.items)
 *         this.totalAmount = event.totalAmount
 *     }
 *
 *     @OnSourcing
 *     fun onOrderPaid(event: OrderPaid) {
 *         this.status = OrderStatus.PAID
 *         this.paidAt = event.paidAt
 *     }
 * }
 * ```
 * @see StateAggregate for state-based aggregates that use sourcing
 * @see OnEvent for event reaction handlers
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@OnMessage(FunctionKind.SOURCING, defaultFunctionName = DEFAULT_ON_SOURCING_NAME)
@MustBeDocumented
annotation class OnSourcing
