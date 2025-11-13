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

package me.ahoo.wow.api.exception

/**
 * Defines the recoverability classification for errors and operations.
 *
 * This enum categorizes errors based on whether they can be resolved through retry mechanisms.
 * It provides a standardized way to determine error handling strategies, enabling intelligent
 * retry policies and failure recovery in distributed systems.
 *
 * The classification helps in:
 * - Implementing circuit breakers and retry logic
 * - Determining appropriate error responses to clients
 * - Logging and monitoring error patterns
 * - Resource allocation for error recovery
 *
 * @see me.ahoo.wow.api.annotation.Retry for retry policy annotations
 *
 * @sample
 * ```kotlin
 * when (error.recoverableType) {
 *     RECOVERABLE -> retryWithBackoff()
 *     UNRECOVERABLE -> logAndFail(error)
 *     UNKNOWN -> conservativeRetry()
 * }
 * ```
 */
enum class RecoverableType {
    /**
     * Indicates that the error or operation failure is recoverable through retry.
     *
     * Use this classification for transient errors that are likely to succeed on retry.
     * Common scenarios include:
     * - Network timeouts or connectivity issues
     * - Temporary service unavailability
     * - Rate limiting or throttling
     * - Optimistic locking conflicts
     * - Temporary resource exhaustion
     *
     * Operations with this type should be retried with appropriate backoff strategies.
     */
    RECOVERABLE,

    /**
     * Indicates that the error or operation failure is not recoverable through retry.
     *
     * Use this classification for permanent errors that will consistently fail on retry.
     * Common scenarios include:
     * - Invalid input data or request parameters
     * - Authentication or authorization failures
     * - Resource not found (404 errors)
     * - Business logic violations
     * - Configuration errors
     *
     * Operations with this type should not be retried and should be handled as permanent failures.
     */
    UNRECOVERABLE,

    /**
     * Indicates that the recoverability of the error cannot be determined.
     *
     * Use this classification when:
     * - Error classification logic is not implemented
     * - Error type is new or unrecognized
     * - External systems don't provide recoverability information
     * - Conservative approach is needed for unknown errors
     *
     * Operations with this type should use conservative retry policies or require manual intervention.
     */
    UNKNOWN
}
