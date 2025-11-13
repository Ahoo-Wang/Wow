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
 * Marks a function as blocking, indicating it should not be executed asynchronously.
 *
 * Functions annotated with @Blocking will be executed synchronously, blocking the
 * calling thread until completion. This is useful for operations that:
 * - Require immediate results
 * - Have external dependencies that don't support async execution
 * - Need to maintain thread-local context
 * - Must complete within the current transaction boundary
 *
 * Use this annotation sparingly, as blocking operations can reduce system throughput
 * and responsiveness. Prefer asynchronous processing when possible.
 *
 * Example usage:
 * ```kotlin
 * @AggregateRoot
 * class OrderAggregate {
 *
 *     @OnCommand
 *     @Blocking
 *     fun processPayment(command: ProcessPaymentCommand): PaymentProcessed {
 *         // Synchronous payment processing that blocks until complete
 *         val result = paymentGateway.charge(command.amount)
 *         return PaymentProcessed(command.orderId, result.transactionId)
 *     }
 * }
 * ```
 *
 * @see OnCommand for command handler functions
 * @see OnEvent for event handler functions
 */
@Target(AnnotationTarget.FUNCTION)
@Inherited
@MustBeDocumented
annotation class Blocking
