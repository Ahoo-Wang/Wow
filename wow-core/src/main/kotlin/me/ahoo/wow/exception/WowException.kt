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

package me.ahoo.wow.exception

import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.exception.ErrorInfo.Companion.materialize
import java.util.concurrent.TimeoutException

open class WowException(
    final override val errorCode: String,
    errorMsg: String,
    cause: Throwable? = null
) : RuntimeException(errorMsg, cause), ErrorInfo {
    override val errorMsg: String
        get() = message ?: ""
}

/**
 *  RetryableException can be recovered by retrying.
 */
interface RetryableException

/**
 * @see RetryableException
 */
val Throwable.retryable: Boolean
    get() = when (this) {
        is RetryableException -> true
        is TimeoutException -> true
        else -> false
    }

fun Throwable.asErrorInfo(): ErrorInfo {
    return when (this) {
        is ErrorInfo -> this.materialize()
        is IllegalArgumentException -> ErrorInfo.of(
            ErrorCodes.ILLEGAL_ARGUMENT,
            message,
        )

        is IllegalStateException -> ErrorInfo.of(
            ErrorCodes.ILLEGAL_STATE,
            message,
        )

        is TimeoutException -> ErrorInfo.of(
            ErrorCodes.REQUEST_TIMEOUT,
            message,
        )

        else -> ErrorInfo.of(
            ErrorCodes.BAD_REQUEST,
            message,
        )
    }
}
