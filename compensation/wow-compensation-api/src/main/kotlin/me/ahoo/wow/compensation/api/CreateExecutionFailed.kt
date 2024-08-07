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

import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.ORDER_FIRST
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.api.exception.RecoverableType
import me.ahoo.wow.api.messaging.function.FunctionInfoData

@Order(ORDER_FIRST)
@CreateAggregate
data class CreateExecutionFailed(
    override val eventId: EventId,
    override val function: FunctionInfoData,
    override val error: ErrorDetails,
    override val executeAt: Long,
    val retrySpec: RetrySpec? = null,
    override val recoverable: RecoverableType = RecoverableType.UNKNOWN
) : ExecutionFailedInfo

data class ExecutionFailedCreated(
    override val eventId: EventId,
    override val function: FunctionInfoData,
    override val error: ErrorDetails,
    override val executeAt: Long,
    override val retryState: RetryState,
    val retrySpec: RetrySpec,
    override val recoverable: RecoverableType = RecoverableType.UNKNOWN,
) : ExecutionFailedInfo, IRetryState
