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
 * Provides a human-readable description for annotated elements.
 *
 * This annotation is used to add descriptive text that can be used for:
 * - API documentation generation
 * - User interface labels
 * - Logging and monitoring
 * - Development tools and IDE support
 *
 * The description should be clear, concise, and provide meaningful context
 * about the purpose and usage of the annotated element.
 *
 * Example usage:
 * ```kotlin
 * @Description("Represents a customer order in the e-commerce system")
 * data class Order(
 *     @Description("Unique identifier for the order")
 *     val orderId: String,
 *
 *     @Description("Total monetary value of the order including taxes and shipping")
 *     val totalAmount: BigDecimal
 * )
 * ```
 * @param value The description text. Should be a complete, grammatically correct sentence or phrase.
 *
 * @see Summary for shorter, concise descriptions
 */
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.ANNOTATION_CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
)
@Inherited
@MustBeDocumented
annotation class Description(
    val value: String
)
