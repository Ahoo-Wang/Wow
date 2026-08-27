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
 * Marks a class as a projection processor for maintaining read models.
 *
 * Projection processors transform domain events into optimized read models (projections)
 * that are tailored for specific query and display requirements. They maintain denormalized
 * views of the domain data for efficient querying.
 *
 * Projection processors are responsible for:
 * - Building and updating read models from domain events
 * - Maintaining data consistency across projections
 * - Handling event versioning and schema evolution
 * - Optimizing data structures for query performance
 * - Supporting eventual consistency patterns
 *
 * Example usage:
 * ```kotlin
 * @ProjectionProcessor
 * class OrderSummaryProjection {
 *
 *     @OnEvent
 *     fun onOrderCreated(event: OrderCreated) {
 *         val summary = OrderSummary(
 *             orderId = event.orderId,
 *             customerId = event.customerId,
 *             totalAmount = event.totalAmount,
 *             status = OrderStatus.CREATED,
 *             createdAt = event.createdAt
 *         )
 *         orderSummaryRepository.save(summary)
 *     }
 *
 *     @OnEvent
 *     fun onOrderPaid(event: OrderPaid) {
 *         val summary = orderSummaryRepository.findById(event.orderId)
 *         summary?.let {
 *             it.status = OrderStatus.PAID
 *             it.paidAt = event.paidAt
 *             orderSummaryRepository.save(it)
 *         }
 *     }
 * }
 * ```
 * @see EventProcessor for general event processing
 * @see OnEvent for event handler methods
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
@Component
annotation class ProjectionProcessor
