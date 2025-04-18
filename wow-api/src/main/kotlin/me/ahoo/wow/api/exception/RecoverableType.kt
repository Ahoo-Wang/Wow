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

import me.ahoo.wow.api.exception.RecoverableType.RECOVERABLE
import me.ahoo.wow.api.exception.RecoverableType.UNKNOWN
import me.ahoo.wow.api.exception.RecoverableType.UNRECOVERABLE

/**
 * Enumerates the types of recoverability for errors or operations, allowing for classification and handling based on whether an error is transient and can be resolved by retrying.
 *
 * The [RecoverableType] enum provides a way to categorize errors into three distinct categories: [RECOVERABLE], [UNRECOVERABLE], and [UNKNOWN].
 * This categorization is essential for implementing robust error handling and retry mechanisms in applications,
 * ensuring that temporary issues are retried while permanent or unknown issues are handled appropriately.
 *
 *  @see me.ahoo.wow.api.annotation.Retry
 */
enum class RecoverableType {
    /**
     * Represents an error type that indicates the operation or error can be retried.
     *
     * This enum value is used to classify errors in a way that allows for the implementation of retry logic. When an error
     * is marked as [RECOVERABLE], it signifies that the error condition is temporary and might be resolved upon retrying the
     * operation. This is particularly useful in scenarios where network issues, transient server errors, or other temporary
     * conditions may cause an operation to fail, but with a high likelihood of success on subsequent attempts.
     */
    RECOVERABLE,

    /**
     * Represents an error type that indicates the operation or error cannot be retried.
     *
     * This enum value is used to classify errors in a way that signifies the error condition is permanent and retrying the operation will not resolve the issue. It is particularly
     *  useful for handling errors where the underlying problem is fundamental and cannot be resolved by simply retrying, such as invalid input, resource exhaustion, or other non-transient
     *  issues.
     */
    UNRECOVERABLE,

    /**
     * Represents an unknown type of recoverability for an error or operation.
     * This is used when the recoverability of an error cannot be determined or is not specified.
     */
    UNKNOWN
}
