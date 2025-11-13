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

const val ORDER_FIRST = Int.MIN_VALUE
const val ORDER_DEFAULT = 0
const val ORDER_DEFAULT_STEP = 100
const val ORDER_LAST = Int.MAX_VALUE

/**
 * Specifies the execution order for annotated elements.
 *
 * This annotation controls the sequence in which handlers, processors, or components
 * are executed when multiple candidates exist for the same operation. Lower values
 * indicate higher priority (executed first).
 *
 * Ordering is essential for:
 * - Ensuring correct sequence of event processing
 * - Controlling initialization order of components
 * - Managing dependencies between processors
 * - Implementing complex business workflows
 *
 * Example usage:
 * ```kotlin
 * @EventProcessor
 * class OrderProcessor {
 *
 *     @OnEvent
 *     @Order(1)  // Execute first
 *     fun validateOrder(event: OrderCreated) {
 *         // Validation logic
 *     }
 *
 *     @OnEvent
 *     @Order(2)  // Execute after validation
 *     fun processPayment(event: OrderCreated) {
 *         // Payment processing
 *     }
 *
 *     @OnEvent
 *     @Order(after = [PaymentProcessor::class])  // Execute after PaymentProcessor
 *     fun sendConfirmation(event: OrderCreated) {
 *         // Send confirmation
 *     }
 * }
 * ```
 *
 * @param value The priority value. Lower numbers execute first. Common values:
 *             ORDER_FIRST (-2^31), ORDER_DEFAULT (0), ORDER_LAST (2^31-1)
 * @param before Array of classes that this element should execute before.
 *              Useful for relative ordering without hard-coded values.
 * @param after Array of classes that this element should execute after.
 *             Useful for relative ordering without hard-coded values.
 *
 * @see ORDER_FIRST for the highest priority
 * @see ORDER_DEFAULT for normal priority
 * @see ORDER_LAST for the lowest priority
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.FUNCTION)
@Inherited
@MustBeDocumented
annotation class Order(
    val value: Int = ORDER_DEFAULT,
    val before: Array<KClass<*>> = [],
    val after: Array<KClass<*>> = []
) {
    companion object {
        /**
         * Default ordering instance with normal priority.
         */
        val DEFAULT = Order()
    }
}
