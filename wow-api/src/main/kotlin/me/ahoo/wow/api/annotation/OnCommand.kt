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
import kotlin.reflect.KClass

const val DEFAULT_ON_COMMAND_NAME = "onCommand"

/**
 * Marks a function as a command handler within an aggregate.
 *
 * Functions annotated with @OnCommand are responsible for processing commands and
 * producing domain events that represent state changes. They are the primary way
 * aggregates respond to business operations.
 *
 * Command handlers should:
 * - Validate command data and business rules
 * - Access and modify aggregate state
 * - Return domain events representing the changes
 * - Be idempotent when possible
 * - Follow the command-query separation principle
 *
 * Example usage:
 * ```kotlin
 * @AggregateRoot
 * class OrderAggregate(
 *     @AggregateId
 *     val orderId: String
 * ) {
 *
 *     @OnCommand(returns = [OrderCreated::class])
 *     fun create(command: CreateOrderCommand): OrderCreated {
 *         require(command.items.isNotEmpty()) { "Order must have items" }
 *
 *         return OrderCreated(
 *             orderId = command.orderId,
 *             customerId = command.customerId,
 *             items = command.items,
 *             total = calculateTotal(command.items)
 *         )
 *     }
 *
 *     @OnCommand
 *     fun addItem(command: AddOrderItemCommand): OrderItemAdded {
 *         // Business logic for adding items
 *         return OrderItemAdded(command.orderId, command.item)
 *     }
 * }
 * ```
 * @param returns Array of event types that this handler may produce. Used for
 *               documentation and validation. Framework automatically infers
 *               return types when not specified.
 * @see AggregateRoot for aggregate classes containing command handlers
 * @see OnEvent for event handlers
 * @see CommandAggregate for the command processing implementation
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@OnMessage(FunctionKind.COMMAND, defaultFunctionName = DEFAULT_ON_COMMAND_NAME)
@MustBeDocumented
annotation class OnCommand(
    /**
     * Specifies the domain event types that this command handler may return.
     *
     * This information is used for:
     * - API documentation generation
     * - Runtime validation of return types
     * - Framework optimization and routing
     *
     * If not specified, the framework will infer the return types from the actual
     * function signature. Explicit specification improves clarity and enables
     * compile-time validation.
     */
    val returns: Array<KClass<*>> = []
)
