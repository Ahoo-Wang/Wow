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
 * Interface for entities that can provide error information.
 *
 * Classes implementing this interface are capable of supplying detailed error
 * information through the [errorInfo] property. This is useful for objects that
 * need to report errors in a standardized way, such as API responses, command
 * results, or any operation that may fail.
 *
 * @see ErrorInfo for the structure of error information
 * @see DefaultErrorInfo for a concrete implementation
 */
interface ErrorInfoCapable {
    /**
     * The error information associated with this entity.
     *
     * This property provides access to detailed error data including error codes,
     * messages, and any binding errors that may have occurred. The error info
     * can be used for logging, user feedback, or programmatic error handling.
     */
    val errorInfo: ErrorInfo
}
