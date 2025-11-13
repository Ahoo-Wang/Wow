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

import com.fasterxml.jackson.annotation.JsonInclude
import me.ahoo.wow.api.exception.ErrorInfo.Companion.SUCCEEDED
import me.ahoo.wow.api.naming.Materialized
import me.ahoo.wow.api.naming.Named

/**
 * Standardized interface for representing error information in API responses and operations.
 *
 * This interface provides a consistent structure for error reporting across the application,
 * enabling uniform error handling, logging, and client communication. It encapsulates
 * error codes, human-readable messages, and detailed binding errors for comprehensive
 * error reporting.
 *
 * Key features:
 * - Success/failure status determination
 * - Structured error codes for programmatic handling
 * - Localized error messages for user display
 * - Detailed binding errors for validation failures
 *
 * @see DefaultErrorInfo for the default implementation
 * @see ErrorInfoCapable for objects that can provide error information
 *
 * @sample
 * ```kotlin
 * // Successful operation
 * val success = ErrorInfo.OK
 * assert(success.succeeded) // true
 *
 * // Failed operation with details
 * val error = ErrorInfo.of(
 *     errorCode = "VALIDATION_ERROR",
 *     errorMsg = "Invalid input parameters",
 *     bindingErrors = listOf(
 *         BindingError("email", "Invalid email format"),
 *         BindingError("age", "Must be positive number")
 *     )
 * )
 * ```
 */
interface ErrorInfo {
    /**
     * Indicates whether the operation was successful.
     *
     * Success is determined by comparing the [errorCode] with the standard
     * success code [SUCCEEDED]. This provides a consistent way to check
     * operation status across different error implementations.
     *
     * @return `true` if the operation succeeded (errorCode equals "Ok"), `false` otherwise
     */
    val succeeded: Boolean get() = SUCCEEDED == errorCode

    /**
     * The error code that uniquely identifies the type of error.
     *
     * Error codes should be:
     * - Uppercase with underscores (e.g., "VALIDATION_ERROR", "NOT_FOUND")
     * - Consistent across the application
     * - Machine-readable for programmatic error handling
     * - Used for internationalization and error categorization
     *
     * The special code "Ok" indicates success.
     */
    val errorCode: String

    /**
     * A human-readable message describing the error.
     *
     * This message should be:
     * - User-friendly and localized when appropriate
     * - Descriptive enough for debugging but not too technical
     * - Suitable for display in UI or logs
     * - Empty string for successful operations
     */
    val errorMsg: String

    /**
     * A list of detailed binding errors that occurred during data validation or mapping.
     *
     * Binding errors provide field-level error information, typically from form validation
     * or data transformation operations. Each error contains the field name and a specific
     * error message. This is particularly useful for API responses where clients need
     * detailed validation feedback.
     *
     * The list is empty for successful operations or when no binding errors occurred.
     * The @JsonInclude annotation ensures empty lists are not serialized in JSON responses.
     */
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val bindingErrors: List<BindingError> get() = emptyList()

    /**
     * Companion object providing constants and utility functions for working with ErrorInfo instances.
     *
     * This companion object offers:
     * - Standard success constants and instances
     * - Factory methods for creating error instances
     * - Utility functions for error checking and conversion
     * - Materialization helpers for serialization
     */
    companion object {
        /**
         * The standard error code indicating a successful operation.
         *
         * This constant should be used as the error code for all successful operations.
         * It provides consistency across the application for success status checking.
         */
        const val SUCCEEDED = "Ok"

        /**
         * The standard message for successful operations.
         *
         * Successful operations typically use an empty message, but this constant
         * provides a consistent way to represent success messages.
         */
        const val SUCCEEDED_MESSAGE = ""

        /**
         * A pre-defined instance representing a successful operation.
         *
         * This can be used directly for successful responses without creating
         * new instances. It has errorCode = "Ok" and an empty error message.
         */
        val OK = DefaultErrorInfo(SUCCEEDED, SUCCEEDED_MESSAGE)

        /**
         * Converts this ErrorInfo to a materialized instance for serialization.
         *
         * If the current instance is already [Materialized], returns it directly.
         * Otherwise, creates a new [DefaultErrorInfo] with the same properties.
         * This is useful for ensuring error information can be properly serialized
         * in JSON responses or stored in databases.
         *
         * @receiver The ErrorInfo instance to materialize
         * @return A [Materialized] ErrorInfo instance with the same error details
         *
         * @sample
         * ```kotlin
         * val error = someErrorInfo.materialize()
         * // Now error is guaranteed to be serializable
         * ```
         */
        fun ErrorInfo.materialize(): ErrorInfo {
            if (this is Materialized) {
                return this
            }
            return DefaultErrorInfo(
                errorCode = errorCode,
                errorMsg = errorMsg,
                bindingErrors = bindingErrors,
            )
        }

        /**
         * Converts this ErrorInfo to a DefaultErrorInfo instance.
         *
         * If the current instance is already a [DefaultErrorInfo], returns it directly.
         * Otherwise, creates a new [DefaultErrorInfo] with the same properties.
         * This is useful when you need the concrete implementation for specific operations.
         *
         * @receiver The ErrorInfo instance to convert
         * @return A [DefaultErrorInfo] instance with the same error details
         */
        fun ErrorInfo.toDefault(): DefaultErrorInfo {
            if (this is DefaultErrorInfo) {
                return this
            }
            return DefaultErrorInfo(
                errorCode = errorCode,
                errorMsg = errorMsg,
                bindingErrors = bindingErrors,
            )
        }

        /**
         * Checks if this object represents a failed operation.
         *
         * This extension function provides a convenient way to check if any object
         * is an ErrorInfo instance that represents a failure (not successful).
         * Returns false for null objects or successful ErrorInfo instances.
         *
         * @receiver Any object that might be an ErrorInfo
         * @return `true` if this is a failed ErrorInfo, `false` otherwise
         *
         * @sample
         * ```kotlin
         * val result = someOperation()
         * if (result.isFailed()) {
         *     handleError(result as ErrorInfo)
         * }
         * ```
         */
        fun Any?.isFailed(): Boolean = this is ErrorInfo && !succeeded

        /**
         * Creates a new ErrorInfo instance with the specified parameters.
         *
         * This factory method provides a convenient way to create error information
         * instances without directly instantiating DefaultErrorInfo. It handles
         * null error messages by converting them to empty strings.
         *
         * @param errorCode The error code that identifies the type of error (must not be null)
         * @param errorMsg An optional human-readable error message (defaults to empty string if null)
         * @param bindingErrors A list of field-level binding errors (defaults to empty list)
         * @return A new [DefaultErrorInfo] instance with the specified error details
         *
         * @sample
         * ```kotlin
         * val validationError = ErrorInfo.of(
         *     errorCode = "VALIDATION_FAILED",
         *     errorMsg = "Input validation failed",
         *     bindingErrors = listOf(
         *         BindingError("username", "Username is required"),
         *         BindingError("email", "Invalid email format")
         *     )
         * )
         * ```
         */
        fun of(
            errorCode: String,
            errorMsg: String? = null,
            bindingErrors: List<BindingError> = emptyList()
        ): DefaultErrorInfo = DefaultErrorInfo(errorCode, errorMsg.orEmpty(), bindingErrors)
    }
}

/**
 * Represents a field-level error that occurred during data binding or validation.
 *
 * Binding errors provide detailed information about validation failures at the
 * individual field level, typically used in form validation, API request processing,
 * or data transformation operations. Each error is associated with a specific
 * field name and contains a descriptive error message.
 *
 * @property name The name of the field or property that failed validation
 * @property msg A human-readable message describing the validation error
 *
 * @see ErrorInfo.bindingErrors for how binding errors are used in error responses
 *
 * @sample
 * ```kotlin
 * val errors = listOf(
 *     BindingError("email", "Invalid email format"),
 *     BindingError("age", "Must be a positive number"),
 *     BindingError("password", "Password must be at least 8 characters")
 * )
 * ```
 */
data class BindingError(
    override val name: String,
    val msg: String
) : Named

/**
 * Default implementation of the [ErrorInfo] interface.
 *
 * This data class provides a concrete, serializable implementation of error information
 * that can be used directly in API responses, logging, and error handling. It implements
 * [Materialized] to ensure it can be properly serialized in JSON responses and stored
 * in databases.
 *
 * @property errorCode The error code that identifies the type of error
 * @property errorMsg A human-readable message describing the error (empty for success)
 * @property bindingErrors A list of field-level validation errors (empty by default)
 *
 * @see ErrorInfo for the interface definition
 * @see ErrorInfo.of for convenient factory method
 *
 * @sample
 * ```kotlin
 * // Create a simple error
 * val error = DefaultErrorInfo("NOT_FOUND", "User not found")
 *
 * // Create an error with binding details
 * val validationError = DefaultErrorInfo(
 *     errorCode = "VALIDATION_ERROR",
 *     errorMsg = "Input validation failed",
 *     bindingErrors = listOf(
 *         BindingError("email", "Invalid format"),
 *         BindingError("password", "Too short")
 *     )
 * )
 * ```
 */
data class DefaultErrorInfo(
    override val errorCode: String,
    override val errorMsg: String = "",
    override val bindingErrors: List<BindingError> = emptyList()
) : ErrorInfo,
    Materialized
