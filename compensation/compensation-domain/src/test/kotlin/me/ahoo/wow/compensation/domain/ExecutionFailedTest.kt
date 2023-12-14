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

import me.ahoo.wow.api.messaging.FunctionKind
import me.ahoo.wow.api.messaging.processor.ProcessorInfoData
import me.ahoo.wow.compensation.api.ApplyExecutionFailed
import me.ahoo.wow.compensation.api.ApplyExecutionSuccess
import me.ahoo.wow.compensation.api.CompensationPrepared
import me.ahoo.wow.compensation.api.CreateExecutionFailed
import me.ahoo.wow.compensation.api.ErrorDetails
import me.ahoo.wow.compensation.api.EventId
import me.ahoo.wow.compensation.api.ExecutionFailedApplied
import me.ahoo.wow.compensation.api.ExecutionFailedCreated
import me.ahoo.wow.compensation.api.ExecutionFailedStatus
import me.ahoo.wow.compensation.api.ExecutionSuccessApplied
import me.ahoo.wow.compensation.api.PrepareCompensation
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.modeling.asNamedAggregate
import me.ahoo.wow.test.aggregate.`when`
import me.ahoo.wow.test.aggregateVerifier
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class ExecutionFailedTest {
    companion object {
        val EVENT_AGGREGATE = "order.order".asNamedAggregate()
        val EVENT_ID = EventId(GlobalIdGenerator.generateAsString(), EVENT_AGGREGATE.asAggregateId(), 1)
        val processor = ProcessorInfoData("order", "OrderProjector")
        val functionKind = FunctionKind.EVENT
        val error = ErrorDetails("errorCode", "errorMsg", "stackTrace")
    }

    @Test
    fun onCreate() {
        val createExecutionFailed = CreateExecutionFailed(
            eventId = EVENT_ID,
            processor = processor,
            functionKind = functionKind,
            error = error,
            executionTime = System.currentTimeMillis()
        )

        aggregateVerifier<ExecutionFailed, ExecutionFailedState>()
            .`when`(createExecutionFailed)
            .expectNoError()
            .expectEventType(ExecutionFailedCreated::class.java)
            .expectState {
                assertThat(it.eventId, equalTo(createExecutionFailed.eventId))
                assertThat(it.processor, equalTo(createExecutionFailed.processor))
                assertThat(it.functionKind, equalTo(createExecutionFailed.functionKind))
                assertThat(it.error, equalTo(createExecutionFailed.error))
                assertThat(it.executionTime, equalTo(createExecutionFailed.executionTime))
                assertThat(it.status, equalTo(ExecutionFailedStatus.FAILED))
                assertThat(it.retriedTimes, equalTo(0))
            }
            .verify()
    }

    @Test
    fun onPrepare() {
        val prepareCompensation = PrepareCompensation(id = GlobalIdGenerator.generateAsString())
        val executionFailedCreated = ExecutionFailedCreated(
            eventId = EVENT_ID,
            processor = processor,
            functionKind = functionKind,
            error = error,
            executionTime = System.currentTimeMillis()
        )
        aggregateVerifier<ExecutionFailed, ExecutionFailedState>()
            .given(executionFailedCreated)
            .`when`(prepareCompensation)
            .expectNoError()
            .expectEventType(CompensationPrepared::class.java)
            .expectState {
                assertThat(it.status, equalTo(ExecutionFailedStatus.PREPARED))
                assertThat(it.retriedTimes, equalTo(0))
            }
            .verify().then()
            .given()
            .`when`(prepareCompensation)
            .expectErrorType(IllegalStateException::class.java)
            .expectState {
                assertThat(it.status, equalTo(ExecutionFailedStatus.PREPARED))
            }
            .verify()
    }

    @Test
    fun onFailed() {
        val applyExecutionFailed = ApplyExecutionFailed(
            id = GlobalIdGenerator.generateAsString(),
            error = error,
            executionTime = System.currentTimeMillis()
        )
        val executionFailedCreated = ExecutionFailedCreated(
            eventId = EVENT_ID,
            processor = processor,
            functionKind = functionKind,
            error = error,
            executionTime = System.currentTimeMillis()
        )

        aggregateVerifier<ExecutionFailed, ExecutionFailedState>()
            .given(executionFailedCreated, CompensationPrepared)
            .`when`(applyExecutionFailed)
            .expectNoError()
            .expectEventType(ExecutionFailedApplied::class.java)
            .expectState {
                assertThat(it.status, equalTo(ExecutionFailedStatus.FAILED))
                assertThat(it.retriedTimes, equalTo(1))
            }
            .verify().then()
            .given()
            .`when`(applyExecutionFailed)
            .expectErrorType(IllegalStateException::class.java)
            .expectState {
                assertThat(it.status, equalTo(ExecutionFailedStatus.FAILED))
                assertThat(it.retriedTimes, equalTo(1))
            }
            .verify()
    }

    @Test
    fun onSucceed() {
        val applyExecutionSuccess = ApplyExecutionSuccess(
            id = GlobalIdGenerator.generateAsString(),
            executionTime = System.currentTimeMillis()
        )
        val executionFailedCreated = ExecutionFailedCreated(
            eventId = EVENT_ID,
            processor = processor,
            functionKind = functionKind,
            error = error,
            executionTime = System.currentTimeMillis()
        )

        aggregateVerifier<ExecutionFailed, ExecutionFailedState>()
            .given(executionFailedCreated, CompensationPrepared)
            .`when`(applyExecutionSuccess)
            .expectNoError()
            .expectEventType(ExecutionSuccessApplied::class.java)
            .expectState {
                assertThat(it.status, equalTo(ExecutionFailedStatus.SUCCEEDED))
                assertThat(it.retriedTimes, equalTo(1))
            }
            .verify().then()
            .given()
            .`when`(applyExecutionSuccess)
            .expectErrorType(IllegalStateException::class.java)
            .expectState {
                assertThat(it.status, equalTo(ExecutionFailedStatus.SUCCEEDED))
                assertThat(it.retriedTimes, equalTo(1))
            }
            .verify()
    }
}
