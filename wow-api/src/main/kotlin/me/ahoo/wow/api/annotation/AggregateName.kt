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
 * Marks a field or property as containing the aggregate type name.
 *
 * The aggregate name identifies the type or category of aggregate, distinguishing between
 * different kinds of aggregates in the system (e.g., "order", "user", "product").
 *
 * This annotation is used by the framework for:
 * - Routing commands to the correct aggregate handlers
 * - Generating event stream names
 * - Providing context in logging and monitoring
 * - Supporting multi-tenant aggregate isolation
 *
 * The annotated field/property should contain a string that uniquely identifies the
 * aggregate type within the bounded context.
 *
 * Example usage:
 * ```kotlin
 * @AggregateRoot
 * class OrderAggregate(
 *     @AggregateId
 *     val orderId: String,
 *
 *     @AggregateName
 *     val aggregateName: String = "order"
 * ) {
 *
 *     @OnCommand
 *     fun create(command: CreateOrderCommand): OrderCreated {
 *         // Order creation logic
 *     }
 * }
 * ```
 *
 * @see AggregateId for marking aggregate instance identifiers
 * @see NamedAggregate for interfaces that provide aggregate naming
 */
@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.ANNOTATION_CLASS,
)
@Inherited
annotation class AggregateName
