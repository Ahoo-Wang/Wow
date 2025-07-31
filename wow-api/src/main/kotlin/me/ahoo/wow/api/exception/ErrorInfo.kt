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
 * Represents the information about an error, including whether the operation succeeded, the error code, and any associated messages or binding errors.
 *
 * This interface is designed to provide a standardized way of handling and representing errors across different parts of an application. It includes methods to check if the operation was successful, retrieve the error code
 * , and access any additional error details such as messages or binding errors.
 */
interface ErrorInfo {
    /**
     * Indicates whether the operation has succeeded based on the [errorCode].
     * It returns `true` if the [errorCode] is equal to [SUCCEEDED], otherwise, it returns `false`.
     */
    val succeeded: Boolean get() = SUCCEEDED == errorCode

    /**
     * Represents the error code associated with an error. This value is used to identify the type of error that has occurred,
     * which can be useful for debugging, logging, and handling errors in a standardized way.
     */
    val errorCode: String

    /**
     * Represents the message associated with an error. This message provides a human-readable description of the error, which can be used for logging, debugging, or displaying to the user
     * .
     */
    val errorMsg: String

    /**
     * Provides a list of [BindingError] instances that occurred during the binding process.
     * Each [BindingError] contains information about the error, including its name and a message describing the issue.
     * This property returns an empty list if no binding errors are present.
     */
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val bindingErrors: List<BindingError> get() = emptyList()

    /**
     * Companion object for [ErrorInfo] that provides constants and utility functions to work with [ErrorInfo] instances.
     *
     * It includes predefined success status values, a method to materialize an [ErrorInfo] instance into a [Materialized] one,
     * and a factory function to create [ErrorInfo] instances.
     */
    companion object {
        /**
         * A constant representing a successful operation or status.
         * This value is typically used in the context of error handling and response descriptions to indicate that an operation has been completed successfully.
         */
        const val SUCCEEDED = "Ok"

        /**
         * Represents the message associated with a successful operation.
         * This constant is used to provide a standard message for operations that complete without errors.
         */
        const val SUCCEEDED_MESSAGE = ""
        val OK = DefaultErrorInfo(SUCCEEDED, SUCCEEDED_MESSAGE)

        /**
         * Converts the current [ErrorInfo] instance into a [Materialized] [ErrorInfo] if it is not already one.
         * If the current instance is of type [Materialized], it returns itself. Otherwise, it creates a new
         * [DefaultErrorInfo] with the current error code, error message, and binding errors.
         *
         * @return A [Materialized] [ErrorInfo] instance.
         */
        fun ErrorInfo.materialize(): ErrorInfo {
            if (this is Materialized) {
                return this
            }
            return DefaultErrorInfo(
                errorCode = errorCode,
                errorMsg = errorMsg,
                bindingErrors = bindingErrors
            )
        }

        fun ErrorInfo.toDefault(): DefaultErrorInfo {
            if (this is DefaultErrorInfo) {
                return this
            }
            return DefaultErrorInfo(
                errorCode = errorCode,
                errorMsg = errorMsg,
                bindingErrors = bindingErrors
            )
        }

        fun Any?.isFailed(): Boolean {
            return this is ErrorInfo && !succeeded
        }

        /**
         * Creates an instance of [ErrorInfo] with the specified error code, optional error message, and a list of binding errors.
         *
         * @param errorCode The unique identifier for the error.
         * @param errorMsg An optional message that describes the error. Defaults to an empty string if not provided.
         * @param bindingErrors A list of [BindingError] instances representing errors that occurred during the binding process. Defaults to an empty list if not provided.
         * @return An [ErrorInfo] instance containing the provided error details.
         */
        fun of(
            errorCode: String,
            errorMsg: String? = null,
            bindingErrors: List<BindingError> = emptyList()
        ): DefaultErrorInfo = DefaultErrorInfo(errorCode, errorMsg.orEmpty(), bindingErrors)
    }
}

/**
 * Represents an error that occurs during the binding process, typically when data is being mapped to or from an object.
 * This class extends the [Named] interface, inheriting the `name` property which can be used to identify the source or context of the error.
 *
 * @param name The name or identifier for the context in which the error occurred.
 * @param msg A message describing the error.
 */
data class BindingError(override val name: String, val msg: String) : Named

/**
 * Represents a default implementation of the [ErrorInfo] interface, providing a concrete structure for error information.
 * This class includes an error code, an error message, and optionally a list of [BindingError] instances that can detail
 * errors occurring during data binding processes. It also implements the [Materialized] interface, indicating it is a
 * fully realized instance of [ErrorInfo].
 *
 * @param errorCode The unique identifier for the type of error.
 * @param errorMsg A human-readable message describing the error.
 * @param bindingErrors An optional list of [BindingError] objects, representing additional errors that occurred during the binding process.
 * Defaults to an empty list if not provided.
 */
data class DefaultErrorInfo(
    override val errorCode: String,
    override val errorMsg: String = "",
    override val bindingErrors: List<BindingError> = emptyList()
) : ErrorInfo, Materialized
