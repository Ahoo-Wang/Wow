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

package me.ahoo.wow.compensation.api

import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.Version
import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.api.event.EventMessage
import me.ahoo.wow.api.exception.BindingError
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.exception.RecoverableType
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.AggregateIdCapable
import me.ahoo.wow.api.naming.Materialized

data class ErrorDetails(
    override val errorCode: String,
    override val errorMsg: String,
    val stackTrace: String,
    override val bindingErrors: List<BindingError> = emptyList()
) :
    ErrorInfo

data class EventId(override val id: String, override val aggregateId: AggregateId, override val version: Int) :
    Identifier,
    Version,
    AggregateIdCapable {
    companion object {
        fun EventMessage<*, *>.toEventId(): EventId {
            return EventId(id = id, aggregateId = aggregateId, version = version)
        }
    }
}

interface IRetryState {
    val retryState: RetryState
}

interface IRecoverable {
    val recoverable: RecoverableType
}

interface ExecuteAt {
    val executeAt: Long
}

interface ExecutionFailedErrorInfo : ExecuteAt {
    val error: ErrorDetails
}

interface ExecutionFailedInfo : IRecoverable, ExecutionFailedErrorInfo {
    val eventId: EventId
    val function: FunctionInfoData
}

interface IRetrySpec {
    /**
     * 最大重试次数
     */
    val maxRetries: Int

    /**
     * the minimum Duration for the first backoff
     *
     * @see java.time.temporal.ChronoUnit.SECONDS
     */
    val minBackoff: Int

    /**
     * 执行超时时间
     *
     * @see java.time.temporal.ChronoUnit.SECONDS
     */
    val executionTimeout: Int
}

data class RetrySpec(
    override val maxRetries: Int,
    override val minBackoff: Int,
    override val executionTimeout: Int,
) : IRetrySpec, Materialized {
    companion object {

        fun IRetrySpec.materialize(): RetrySpec {
            if (this is RetrySpec) {
                return this
            }
            return RetrySpec(maxRetries = maxRetries, minBackoff = minBackoff, executionTimeout = executionTimeout)
        }

        fun Retry.toSpec(): RetrySpec {
            return RetrySpec(maxRetries = maxRetries, minBackoff = minBackoff, executionTimeout = executionTimeout)
        }
    }
}

data class RetryState(
    /**
     * 当前已尝试次数
     */
    val retries: Int,
    /**
     * 当前尝试执行点
     *
     * @see java.time.temporal.ChronoUnit.MILLIS
     */
    val retryAt: Long,
    /**
     * 执行超时时间点
     *
     * @see java.time.temporal.ChronoUnit.MILLIS
     */
    val timeoutAt: Long,
    /**
     * 下次重试时间点
     *
     * @see java.time.temporal.ChronoUnit.MILLIS
     */
    val nextRetryAt: Long,
) {
    fun timeout(): Boolean {
        return System.currentTimeMillis() > timeoutAt
    }
}

interface IExecutionFailedState : Identifier, ExecutionFailedInfo, IRetryState {
    val retrySpec: RetrySpec
    val status: ExecutionFailedStatus
    val isBelowRetryThreshold: Boolean
        get() = retryState.retries < retrySpec.maxRetries
    val isRetryable: Boolean
        get() = status != ExecutionFailedStatus.SUCCEEDED && isBelowRetryThreshold

    fun canForceRetry(): Boolean {
        return when (status) {
            ExecutionFailedStatus.SUCCEEDED -> false
            ExecutionFailedStatus.FAILED -> true
            ExecutionFailedStatus.PREPARED -> retryState.timeout()
        }
    }

    fun canRetry(): Boolean {
        return canForceRetry() && isBelowRetryThreshold
    }

    fun canNextRetry(): Boolean {
        if (!canRetry()) {
            return false
        }

        return System.currentTimeMillis() >= retryState.nextRetryAt
    }
}

enum class ExecutionFailedStatus {
    FAILED,
    PREPARED,
    SUCCEEDED
}
