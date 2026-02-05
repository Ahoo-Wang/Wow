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

package me.ahoo.wow.reactor

import reactor.core.publisher.Mono

/**
 * Chains a deferred Mono creation after this Mono completes.
 *
 * This extension function allows you to defer the creation of a subsequent Mono
 * until the current Mono completes successfully. The defer function is only called
 * after this Mono emits completion, providing lazy evaluation of the next step.
 *
 * This is useful for scenarios where you want to perform an operation only after
 * the current operation succeeds, and you want to delay the creation of the next
 * Mono (e.g., for database queries, HTTP calls, or other expensive operations).
 *
 * Example usage:
 * ```kotlin
 * val result = userRepository.save(user)
 *     .thenDefer {
 *         // This Mono is only created after save completes
 *         emailService.sendWelcomeEmail(user.email)
 *     }
 *     .thenDefer {
 *         // This is only created after email sending completes
 *         auditService.logUserCreation(user.id)
 *     }
 * ```
 *
 * @param R the type of the resulting Mono
 * @param defer a function that creates the next Mono when called
 * @return a Mono that emits the result of the deferred Mono
 * @see Mono.then
 * @see Mono.defer
 */
fun <R : Any> Mono<*>.thenDefer(defer: () -> Mono<R>): Mono<R> = this.then(Mono.defer { defer() })

/**
 * Executes a runnable after this Mono completes successfully.
 *
 * This extension function allows you to perform side effects (like logging, cleanup,
 * or notifications) after the current Mono completes. The runnable is executed
 * regardless of whether the Mono produces a value or is empty.
 *
 * The returned Mono emits Void when the runnable completes, making it suitable
 * for chaining additional operations that depend on the side effect completion.
 *
 * Example usage:
 * ```kotlin
 * val result = orderService.processOrder(order)
 *     .thenRunnable {
 *         // Log successful order processing
 *         logger.info("Order ${order.id} processed successfully")
 *     }
 *     .thenRunnable {
 *         // Send notification
 *         notificationService.sendOrderConfirmation(order.customerId)
 *     }
 *     .thenReturn("Order processed")
 * ```
 *
 * @param runnable a function to execute after this Mono completes
 * @return a Mono that emits Void after the runnable completes
 * @see Mono.then
 * @see Mono.fromRunnable
 */
fun Mono<*>.thenRunnable(runnable: () -> Unit): Mono<Void> = this.then(Mono.fromRunnable(runnable))
