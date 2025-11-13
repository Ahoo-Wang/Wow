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

const val DEFAULT_AGGREGATE_ID_NAME = "id"

/**
 * Marks a field or property as the aggregate identifier.
 *
 * The aggregate ID uniquely identifies an instance of an aggregate within its bounded context.
 * This annotation is used by the framework to automatically map command payloads to the correct
 * aggregate instances and to generate routing information.
 *
 * The annotated field/property should contain a unique identifier that distinguishes one aggregate
 * instance from another. Common types include UUIDs, strings, or custom ID types.
 *
 * Example usage:
 * ```kotlin
 * @AggregateRoot
 * class OrderAggregate(
 *     @AggregateId
 *     val orderId: String
 * ) {
 *
 *     @OnCommand
 *     fun processPayment(command: ProcessPaymentCommand): PaymentProcessed {
 *         // Command processing logic
 *     }
 * }
 *
 * // Command targeting the aggregate
 * data class ProcessPaymentCommand(
 *     @AggregateId
 *     val orderId: String,
 *     val amount: BigDecimal
 * )
 * ```
 *
 * @see AggregateName for marking aggregate type names
 * @see AggregateVersion for marking version fields
 * @see AggregateIdCapable for interfaces that provide aggregate ID access
 */
@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.ANNOTATION_CLASS,
)
@Inherited
annotation class AggregateId

/**
 * Specifies a static aggregate ID for classes that represent single-instance aggregates.
 *
 * This annotation is used for aggregates that have only one instance in the system,
 * such as system-wide configuration aggregates or singleton services. The aggregate ID
 * is fixed and doesn't change between instances.
 *
 * Example usage:
 * ```kotlin
 * @AggregateRoot
 * @StaticAggregateId("system-config")
 * class SystemConfigurationAggregate {
 *
 *     @OnCommand
 *     fun updateConfig(command: UpdateConfigCommand): ConfigUpdated {
 *         // Update system configuration
 *     }
 * }
 * ```
 * @param aggregateId The static identifier for this aggregate type. All instances of
 *                   the annotated class will share this same ID.
 *
 * @see AggregateId for dynamic aggregate IDs
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
annotation class StaticAggregateId(
    val aggregateId: String
)
