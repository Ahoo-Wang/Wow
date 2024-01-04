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

fun Throwable.toErrorCode(): String {
    return when (this) {
        is ErrorInfo -> this.errorCode
        is IllegalArgumentException -> ErrorCodes.ILLEGAL_ARGUMENT
        is IllegalStateException -> ErrorCodes.ILLEGAL_STATE
        is TimeoutException -> ErrorCodes.REQUEST_TIMEOUT
        else -> ErrorCodes.BAD_REQUEST
    }
}

fun Throwable.toErrorMsg(): String {
    return when (this) {
        is ErrorInfo -> this.errorMsg
        else -> message ?: ""
    }
}

fun Throwable.toErrorInfo(): ErrorInfo {
    return when (this) {
        is ErrorInfo -> this.materialize()
        else -> ErrorInfo.of(
            toErrorCode(),
            toErrorMsg()
        )
    }
}

fun Throwable.toWowException(
    errorCode: String = this.toErrorCode(),
    errorMsg: String = this.toErrorMsg()
): WowException {
    return when (this) {
        is WowException -> this
        else -> WowException(
            errorCode,
            errorMsg,
            this,
        )
    }
}
