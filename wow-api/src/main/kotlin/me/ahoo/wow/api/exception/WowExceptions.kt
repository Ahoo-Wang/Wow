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

import java.util.concurrent.TimeoutException

open class WowException(
    final override val errorCode: String,
    errorMsg: String,
    cause: Throwable? = null,
) : RuntimeException(errorMsg, cause), ErrorInfo {
    override val errorMsg: String
        get() = message ?: ""
}

interface NotFoundException
interface ConflictException
interface GoneException
interface PreconditionFailedException
interface PreconditionRequiredException

interface RetryableException

/**
 *  Transient exception, which can be recovered by retrying.
 */
open class WowTransientException(
    errorCode: String,
    errorMsg: String,
    cause: Throwable? = null,
) : WowException(errorCode, errorMsg, cause), RetryableException

/**
 * @see WowTransientException
 */
val Throwable.retryable: Boolean
    get() = when (this) {
        is RetryableException -> true
        is TimeoutException -> true
        else -> false
    }

val Throwable.errorCode: String
    get() = when (this) {
        is WowException -> errorCode
        is IllegalArgumentException -> ErrorCodes.ILLEGAL_ARGUMENT
        is IllegalStateException -> ErrorCodes.ILLEGAL_STATE
        is NotFoundException -> ErrorCodes.NOT_FOUND
        is ConflictException -> ErrorCodes.CONFLICT
        is GoneException -> ErrorCodes.GONE
        is PreconditionFailedException -> ErrorCodes.ILLEGAL_ARGUMENT
        is PreconditionRequiredException -> ErrorCodes.ILLEGAL_STATE
        else -> ErrorCodes.UNDEFINED
    }
