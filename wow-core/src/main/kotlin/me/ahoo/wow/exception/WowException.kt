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

import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.api.exception.BindingError
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.exception.ErrorInfo.Companion.materialize
import me.ahoo.wow.api.exception.RecoverableType
import me.ahoo.wow.exception.ErrorCodeMapping.getErrorCode
import java.util.concurrent.TimeoutException

open class WowException(
    final override val errorCode: String,
    errorMsg: String,
    cause: Throwable? = null,
    override val bindingErrors: List<BindingError> = emptyList(),
) : RuntimeException(errorMsg, cause), ErrorInfo {
    override val errorMsg: String
        get() = message ?: ""
}

/**
 *  RecoverableException can be recovered by retrying.
 *
 *  @see RecoverableType.RECOVERABLE
 */
interface RecoverableException

/**
 * @see RecoverableException
 */
val Throwable.recoverable: RecoverableType
    get() {
        return this.javaClass.recoverable
    }

val Class<out Throwable>.recoverable: RecoverableType
    get() {
        RecoverableExceptionRegistrar.getRecoverableType(this)?.let {
            return it
        }
        return when {
            RecoverableException::class.java.isAssignableFrom(this) -> RecoverableType.RECOVERABLE
            TimeoutException::class.java.isAssignableFrom(this) -> RecoverableType.RECOVERABLE
            else -> RecoverableType.UNKNOWN
        }
    }

fun Retry?.recoverable(throwableClass: Class<out Throwable>): RecoverableType {
    if (this == null) {
        return throwableClass.recoverable
    }

    if (recoverable.any { it.java == throwableClass }) {
        return RecoverableType.RECOVERABLE
    }
    if (unrecoverable.any { it.java == throwableClass }) {
        return RecoverableType.UNRECOVERABLE
    }
    return throwableClass.recoverable
}

fun Throwable.getErrorCode(): String {
    if (this is ErrorInfo) {
        return this.errorCode
    }
    getErrorCode(this::class.java)?.let {
        return it
    }
    return when (this) {
        is IllegalArgumentException -> ErrorCodes.ILLEGAL_ARGUMENT
        is IllegalStateException -> ErrorCodes.ILLEGAL_STATE
        is TimeoutException -> ErrorCodes.REQUEST_TIMEOUT
        else -> ErrorCodes.BAD_REQUEST
    }
}

fun Throwable.getErrorMsg(): String {
    return when (this) {
        is ErrorInfo -> this.errorMsg
        else -> message ?: ""
    }
}

fun Throwable.toErrorInfo(): ErrorInfo {
    return when (this) {
        is ErrorInfo -> this.materialize()
        else -> ErrorInfo.of(
            errorCode = getErrorCode(),
            errorMsg = getErrorMsg()
        )
    }
}

fun Throwable.toWowException(
    errorCode: String = this.getErrorCode(),
    errorMsg: String = this.getErrorMsg()
): WowException {
    return when (this) {
        is WowException -> this
        else -> WowException(
            errorCode = errorCode,
            errorMsg = errorMsg,
            bindingErrors = emptyList(),
            cause = this,
        )
    }
}
