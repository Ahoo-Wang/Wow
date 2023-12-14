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

package me.ahoo.wow.compensation.domain

import me.ahoo.wow.api.annotation.OnSourcing
import me.ahoo.wow.api.messaging.FunctionKind
import me.ahoo.wow.api.messaging.processor.ProcessorInfoData
import me.ahoo.wow.compensation.api.CompensationPrepared
import me.ahoo.wow.compensation.api.ErrorDetails
import me.ahoo.wow.compensation.api.EventId
import me.ahoo.wow.compensation.api.ExecutionFailedApplied
import me.ahoo.wow.compensation.api.ExecutionFailedCreated
import me.ahoo.wow.compensation.api.ExecutionFailedStatus
import me.ahoo.wow.compensation.api.ExecutionSuccessApplied
import me.ahoo.wow.compensation.api.IExecutionFailedState

class ExecutionFailedState(override val id: String) : IExecutionFailedState {
    override lateinit var eventId: EventId
        private set
    override lateinit var processor: ProcessorInfoData
        private set
    override lateinit var functionKind: FunctionKind
        private set
    override lateinit var error: ErrorDetails
        private set
    override var executionTime: Long = 0
        private set
    override var status: ExecutionFailedStatus = ExecutionFailedStatus.FAILED
        private set
    override var retriedTimes: Int = 0
        private set

    @OnSourcing
    fun onCreated(event: ExecutionFailedCreated) {
        this.eventId = event.eventId
        this.processor = event.processor
        this.functionKind = event.functionKind
        this.error = event.error
        this.executionTime = event.executionTime
        this.status = ExecutionFailedStatus.FAILED
    }

    @OnSourcing
    fun onPrepared(event: CompensationPrepared) {
        this.status = ExecutionFailedStatus.PREPARED
    }

    @OnSourcing
    fun onFailed(event: ExecutionFailedApplied) {
        this.retriedTimes = event.retriedTimes
        this.executionTime = event.executionTime
        this.status = ExecutionFailedStatus.FAILED
    }

    @OnSourcing
    fun onSuccess(event: ExecutionSuccessApplied) {
        this.retriedTimes = event.retriedTimes
        this.executionTime = event.executionTime
        this.status = ExecutionFailedStatus.SUCCEEDED
    }
}
