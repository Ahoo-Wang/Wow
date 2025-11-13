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

import me.ahoo.wow.api.annotation.Retry.Companion.DEFAULT_EXECUTION_TIMEOUT
import me.ahoo.wow.api.annotation.Retry.Companion.DEFAULT_MAX_RETRIES
import me.ahoo.wow.api.annotation.Retry.Companion.DEFAULT_MIN_BACKOFF
import kotlin.reflect.KClass

/**
 * Enables retry mechanism for annotated functions with configurable retry policies.
 *
 * This annotation provides resilient error handling by automatically retrying failed operations
 * according to specified policies. It's essential for handling transient failures in distributed
 * systems, network operations, and external service calls.
 *
 * The retry mechanism supports:
 * - Exponential backoff with jitter
 * - Configurable retry counts and timeouts
 * - Selective exception handling (recoverable vs unrecoverable)
 * - Circuit breaker patterns integration
 *
 * Example usage:
 * ```kotlin
 * class PaymentService {
 *
 *     @Retry(
 *         maxRetries = 3,
 *         minBackoff = 1,
 *         recoverable = [IOException::class, TimeoutException::class],
 *         unrecoverable = [IllegalArgumentException::class]
 *     )
 *     fun processPayment(request: PaymentRequest): PaymentResult {
 *         // Network call that might fail temporarily
 *         return paymentGateway.charge(request)
 *     }
 *
 *     @Retry(enabled = false)  // Disable retries for this operation
 *     fun validatePayment(request: PaymentRequest) {
 *         // Validation that should fail fast
 *         require(request.amount > 0) { "Amount must be positive" }
 *     }
 * }
 * ```
 *
 * @param enabled Whether retry functionality is enabled. Defaults to true.
 * @param maxRetries Maximum number of retry attempts. Defaults to [DEFAULT_MAX_RETRIES].
 * @param minBackoff Minimum backoff duration in seconds before first retry.
 *                  Uses exponential backoff for subsequent retries.
 *                  See [java.time.temporal.ChronoUnit.SECONDS] for time units.
 * @param executionTimeout Maximum timeout in seconds for each execution attempt.
 *                        Prevents hanging operations. See [java.time.temporal.ChronoUnit.SECONDS].
 * @param recoverable Array of exception types that should trigger retries when encountered.
 *                   Only these exceptions will cause retry attempts.
 * @param unrecoverable Array of exception types that should NOT trigger retries.
 *                     These exceptions fail immediately without retry.
 *
 * @see DEFAULT_MAX_RETRIES for the default retry limit
 * @see DEFAULT_MIN_BACKOFF for the default backoff duration
 * @see DEFAULT_EXECUTION_TIMEOUT for the default execution timeout
 */
@Target(AnnotationTarget.FUNCTION)
annotation class Retry(
    val enabled: Boolean = true,
    /**
     * Maximum number of retry attempts.
     */
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    /**
     * Minimum backoff duration in seconds before the first retry.
     *
     * @see java.time.temporal.ChronoUnit.SECONDS
     */
    val minBackoff: Int = DEFAULT_MIN_BACKOFF,
    /**
     * Maximum execution timeout in seconds for each retry attempt.
     *
     * @see java.time.temporal.ChronoUnit.SECONDS
     */
    val executionTimeout: Int = DEFAULT_EXECUTION_TIMEOUT,
    val recoverable: Array<KClass<out Throwable>> = [],
    val unrecoverable: Array<KClass<out Throwable>> = []
) {
    companion object {
        const val DEFAULT_MAX_RETRIES = 10
        const val DEFAULT_MIN_BACKOFF = 180
        const val DEFAULT_EXECUTION_TIMEOUT = 120
    }
}
