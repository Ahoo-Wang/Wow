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
 * Specifies a custom name for an annotated element.
 *
 * This annotation allows overriding the default naming conventions used by the framework.
 * It's commonly used for:
 * - Custom aggregate names in routing
 * - Named parameters in configuration
 * - Custom identifiers in serialization
 * - Override default naming in code generation
 *
 * Example usage:
 * ```kotlin
 * @AggregateRoot
 * @Name("purchase-order")  // Custom name for routing instead of class name
 * class OrderAggregate
 *
 * // In configuration or parameters
 * fun process(@Name("order-id") orderId: String) {
 *     // Parameter named "order-id" instead of "orderId"
 * }
 * ```
 * @param value The custom name to use for the annotated element.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.VALUE_PARAMETER)
@Inherited
@MustBeDocumented
annotation class Name(
    val value: String
)
