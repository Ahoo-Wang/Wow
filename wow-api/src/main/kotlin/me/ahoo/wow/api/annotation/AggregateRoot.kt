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

import java.lang.annotation.Inherited
import kotlin.reflect.KClass

/**
 * Marks a class as an aggregate root in the domain-driven design pattern.
 *
 * Aggregate roots are the primary entities in a domain model that:
 * - Encapsulate business logic and state
 * - Ensure consistency boundaries for transactions
 * - Control access to internal entities and value objects
 * - Publish domain events when state changes
 *
 * Classes annotated with @AggregateRoot will be automatically discovered by the framework
 * and registered for command processing and event sourcing.
 *
 * Example usage:
 * ```kotlin
 * @AggregateRoot(commands = [CreateOrderCommand::class, UpdateOrderCommand::class])
 * class OrderAggregate(
 *     @AggregateId
 *     val orderId: String
 * ) {
 *
 *     private var status: OrderStatus = OrderStatus.PENDING
 *     private val items: MutableList<OrderItem> = mutableListOf()
 *
 *     @OnCommand
 *     fun create(command: CreateOrderCommand): OrderCreated {
 *         // Validate and create order
 *         return OrderCreated(orderId, command.items)
 *     }
 *
 *     @OnEvent
 *     fun onCreated(event: OrderCreated) {
 *         items.addAll(event.items)
 *         status = OrderStatus.CREATED
 *     }
 * }
 * ```
 *
 * @param commands Array of command classes to mount to this aggregate root. This enables
 *                command rewriting scenarios where commands can be transformed or validated
 *                before processing. Also generates command routes in OpenAPI documentation.
 *
 * @see OnCommand for marking command handler methods
 * @see OnEvent for marking event handler methods
 * @see AggregateId for marking aggregate identifiers
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class AggregateRoot(
    /**
     * Array of command classes to mount to this aggregate root.
     *
     * This parameter enables command rewriting scenarios where commands can be transformed,
     * validated, or enriched before being processed by the aggregate. It also generates
     * corresponding command routes in OpenAPI documentation for API clients.
     *
     * Commands listed here will be automatically associated with this aggregate root,
     * even if they're not directly handled by @OnCommand methods.
     */
    val commands: Array<KClass<*>> = []
)
