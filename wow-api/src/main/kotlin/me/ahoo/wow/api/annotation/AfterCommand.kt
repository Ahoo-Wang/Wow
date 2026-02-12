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

import me.ahoo.wow.api.messaging.function.FunctionKind
import java.lang.annotation.Inherited
import kotlin.reflect.KClass

const val DEFAULT_AFTER_COMMAND_NAME = "afterCommand"

/**
 * Marks a function to be executed after a command function completes.
 *
 * Functions annotated with @AfterCommand are invoked after command processing finishes.
 * If the function returns a non-null value, it will be appended to the event stream as a domain event.
 *
 * This annotation enables post-command processing such as:
 * - Publishing additional domain events
 * - Triggering side effects
 * - Updating related aggregates
 * - Sending notifications
 *
 * Example usage:
 * ```kotlin
 * @AggregateRoot
 * class OrderAggregate {
 *
 *     @OnCommand
 *     fun createOrder(command: CreateOrderCommand): OrderCreated {
 *         // Create order logic
 *         return OrderCreated(orderId, items)
 *     }
 *
 *     @AfterCommand(include = [CreateOrderCommand::class])
 *     fun afterCreateOrder(command: CreateOrderCommand): OrderNotificationSent? {
 *         // Send notification after order creation
 *         if (shouldSendNotification()) {
 *             return OrderNotificationSent(orderId)
 *         }
 *         return null // No event if notification not needed
 *     }
 * }
 * ```
 *
 * @param include Array of command types to listen for. Only commands of these types will trigger the function.
 *                If empty, all commands are included by default.
 * @param exclude Array of command types to exclude from listening. Commands of these types will not trigger the function.
 *
 * @see OnCommand for command handling functions
 * @see FunctionKind.COMMAND for the function kind this annotation targets
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
@OnMessage(FunctionKind.COMMAND, defaultFunctionName = DEFAULT_AFTER_COMMAND_NAME)
annotation class AfterCommand(
    val include: Array<KClass<*>> = [],
    val exclude: Array<KClass<*>> = []
)
