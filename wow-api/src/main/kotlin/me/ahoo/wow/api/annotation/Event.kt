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

import me.ahoo.wow.api.event.DEFAULT_REVISION
import java.lang.annotation.Inherited

/**
 * Marks a class as a domain event in domain-driven design (DDD).
 *
 * Domain events represent something that happened in the domain that is of interest
 * to other parts of the system. They are immutable records of state changes that
 * have occurred within aggregates.
 *
 * Key characteristics of domain events:
 * - Are immutable (no setters, all properties are read-only)
 * - Represent past occurrences ("OrderCreated", not "CreateOrder")
 * - Contain all data necessary to understand what happened
 * - Are named in past tense
 * - May trigger side effects in other aggregates or external systems
 *
 * Example usage:
 * ```kotlin
 * @Event
 * data class OrderCreated(
 *     val orderId: String,
 *     val customerId: String,
 *     val items: List<OrderItem>,
 *     val totalAmount: BigDecimal,
 *     val createdAt: Instant = Instant.now()
 * )
 *
 * @Event(revision = "2.0")
 * data class OrderShipped(
 *     val orderId: String,
 *     val trackingNumber: String,
 *     val shippedAt: Instant = Instant.now()
 * )
 * ```
 * @param revision Optional version number for the event schema. Used for event versioning
 *                and migration. Defaults to the framework's default revision.
 *
 * @see AggregateRoot for aggregates that publish events
 * @see OnEvent for event handler methods
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class Event(
    val revision: String = DEFAULT_REVISION
)
