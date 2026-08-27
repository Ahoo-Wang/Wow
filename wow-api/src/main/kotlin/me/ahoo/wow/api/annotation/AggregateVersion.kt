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
 * Marks a field or property as containing the aggregate version for optimistic concurrency control.
 *
 * The aggregate version is used to ensure that commands are applied to the correct version
 * of an aggregate, preventing concurrent modification conflicts. This is essential in
 * distributed systems where multiple clients might attempt to modify the same aggregate
 * simultaneously.
 *
 * The annotated field/property should contain an integer representing the current version
 * of the aggregate. The framework automatically increments this version when events are applied.
 *
 * Example usage:
 * ```kotlin
 * @AggregateRoot
 * class OrderAggregate(
 *     @AggregateId
 *     val orderId: String,
 *
 *     @AggregateVersion
 *     var version: Int = 0
 * ) {
 *
 *     @OnCommand
 *     fun updateOrder(command: UpdateOrderCommand): OrderUpdated {
 *         // Version will be checked automatically before applying changes
 *         return OrderUpdated(orderId, command.updates)
 *     }
 *
 *     @OnEvent
 *     fun onUpdated(event: OrderUpdated) {
 *         version++ // Version incremented after event application
 *     }
 * }
 * ```
 *
 * @see AggregateId for marking aggregate identifiers
 * @see AggregateVersionConflictException when version mismatches occur
 */
@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.ANNOTATION_CLASS,
)
@Inherited
@MustBeDocumented
annotation class AggregateVersion
