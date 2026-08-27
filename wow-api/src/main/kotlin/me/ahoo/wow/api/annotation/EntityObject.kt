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

/**
 * Marks a class as a domain entity in domain-driven design (DDD).
 *
 * Entities are objects that have a distinct identity that runs through time and different states.
 * They are defined by their identity rather than their attributes, and they can be mutated
 * while maintaining continuity of identity.
 *
 * Key characteristics of entities:
 * - Have a unique identity that distinguishes them from other entities
 * - Can change state over time while maintaining identity
 * - Are mutable and can be modified through business operations
 * - Often contain business logic and validation rules
 *
 * Example usage:
 * ```kotlin
 * @EntityObject
 * class Order(
 *     @AggregateId
 *     val orderId: String,
 *
 *     var status: OrderStatus = OrderStatus.PENDING,
 *     val items: MutableList<OrderItem> = mutableListOf()
 * ) {
 *
 *     fun addItem(item: OrderItem) {
 *         // Business logic for adding items
 *         items.add(item)
 *     }
 *
 *     fun cancel() {
 *         require(status == OrderStatus.PENDING) { "Can only cancel pending orders" }
 *         status = OrderStatus.CANCELLED
 *     }
 * }
 * ```
 * @see AggregateRoot for aggregate root entities
 * @see ValueObject for immutable value objects
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class EntityObject
