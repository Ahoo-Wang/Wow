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

const val DEFAULT_ON_ERROR_NAME = "onError"

/**
 * Marks a function as a command error handler within an aggregate.
 *
 * Functions annotated with @OnError are invoked when command processing fails.
 * They provide an opportunity to handle errors gracefully, potentially recovering
 * from failures or publishing error events for monitoring and debugging.
 *
 * Error handlers can:
 * - Log error details for monitoring
 * - Publish error events for external systems
 * - Attempt error recovery or compensation
 * - Transform exceptions into domain events
 * - Trigger fallback business logic
 *
 * Error handlers receive the original command and the exception that occurred,
 * allowing for context-aware error processing.
 *
 * Example usage:
 * ```kotlin
 * @AggregateRoot
 * class OrderAggregate {
 *
 *     @OnCommand
 *     fun create(command: CreateOrderCommand): OrderCreated {
 *         // Command processing that might fail
 *         validateOrder(command)
 *         return OrderCreated(command.orderId, command.items)
 *     }
 *
 *     @OnError
 *     fun onCreateError(command: CreateOrderCommand, error: Exception): OrderCreationFailed? {
 *         // Log the error
 *         logger.error("Failed to create order ${command.orderId}", error)
 *
 *         // Publish error event for monitoring
 *         return if (error is ValidationException) {
 *             OrderCreationFailed(command.orderId, error.message ?: "Validation failed")
 *         } else null // Don't publish events for unexpected errors
 *     }
 * }
 * ```
 * @see OnCommand for regular command handlers
 * @see CommandAggregate for command processing implementation
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@OnMessage(FunctionKind.ERROR, defaultFunctionName = DEFAULT_ON_ERROR_NAME)
@MustBeDocumented
annotation class OnError
