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

import me.ahoo.wow.api.exception.RecoverableType
import me.ahoo.wow.api.messaging.FunctionKind
import me.ahoo.wow.api.messaging.processor.ProcessorInfoData
import me.ahoo.wow.compensation.api.ApplyExecutionFailed
import me.ahoo.wow.compensation.api.ApplyExecutionSuccess
import me.ahoo.wow.compensation.api.ApplyRetrySpec
import me.ahoo.wow.compensation.api.ChangeFunctionKind
import me.ahoo.wow.compensation.api.CompensationPrepared
import me.ahoo.wow.compensation.api.CreateExecutionFailed
import me.ahoo.wow.compensation.api.ErrorDetails
import me.ahoo.wow.compensation.api.EventId
import me.ahoo.wow.compensation.api.ExecutionFailedApplied
import me.ahoo.wow.compensation.api.ExecutionFailedCreated
import me.ahoo.wow.compensation.api.ExecutionFailedStatus
import me.ahoo.wow.compensation.api.ExecutionSuccessApplied
import me.ahoo.wow.compensation.api.ForcePrepareCompensation
import me.ahoo.wow.compensation.api.FunctionKindChanged
import me.ahoo.wow.compensation.api.MarkRecoverable
import me.ahoo.wow.compensation.api.PrepareCompensation
import me.ahoo.wow.compensation.api.RecoverableMarked
import me.ahoo.wow.compensation.api.RetrySpecApplied
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.test.aggregate.`when`
import me.ahoo.wow.test.aggregateVerifier
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class ExecutionFailedTest {
    companion object {
        val EVENT_AGGREGATE = "order.order".toNamedAggregate()
        val EVENT_ID = EventId(GlobalIdGenerator.generateAsString(), EVENT_AGGREGATE.aggregateId(), 1)
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
            executeAt = System.currentTimeMillis(),
            recoverable = RecoverableType.RECOVERABLE
        )

        aggregateVerifier<ExecutionFailed, ExecutionFailedState>()
            .inject(DefaultNextRetryAtCalculatorTest.testRetrySpec)
            .inject(DefaultNextRetryAtCalculator)
            .`when`(createExecutionFailed)
            .expectNoError()
            .expectEventType(ExecutionFailedCreated::class.java)
            .expectState {
                assertThat(it.eventId, equalTo(createExecutionFailed.eventId))
                assertThat(it.processor, equalTo(createExecutionFailed.processor))
                assertThat(it.functionKind, equalTo(createExecutionFailed.functionKind))
                assertThat(it.error, equalTo(createExecutionFailed.error))
                assertThat(it.executeAt, equalTo(createExecutionFailed.executeAt))
                assertThat(it.status, equalTo(ExecutionFailedStatus.FAILED))
                assertThat(it.retryState.retries, equalTo(0))
                assertThat(it.isRetryable, equalTo(true))
                assertThat(it.retryState.timeout(), equalTo(false))
                assertThat(it.canRetry(), equalTo(true))
                assertThat(it.canNextRetry(), equalTo(false))
                assertThat(it.recoverable, equalTo(createExecutionFailed.recoverable))
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
            executeAt = System.currentTimeMillis(),
            retryState = DefaultNextRetryAtCalculator.nextRetryState(DefaultNextRetryAtCalculatorTest.testRetrySpec, 0),
            retrySpec = DefaultNextRetryAtCalculatorTest.testRetrySpec
        )

        aggregateVerifier<ExecutionFailed, ExecutionFailedState>()
            .inject(DefaultNextRetryAtCalculator)
            .given(executionFailedCreated)
            .`when`(prepareCompensation)
            .expectNoError()
            .expectEventType(CompensationPrepared::class.java)
            .expectState {
                assertThat(it.id, equalTo(prepareCompensation.id))
                assertThat(it.status, equalTo(ExecutionFailedStatus.PREPARED))
                assertThat(it.retryState.retries, equalTo(1))
                assertThat(it.canNextRetry(), equalTo(false))
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
    fun onPrepareGivenMaxRetry() {
        val prepareCompensation = PrepareCompensation(id = GlobalIdGenerator.generateAsString())
        val executionFailedCreated = ExecutionFailedCreated(
            eventId = EVENT_ID,
            processor = processor,
            functionKind = functionKind,
            error = error,
            executeAt = System.currentTimeMillis(),
            retryState = DefaultNextRetryAtCalculator.nextRetryState(
                DefaultNextRetryAtCalculatorTest.testRetrySpec,
                10
            ),
            retrySpec = DefaultNextRetryAtCalculatorTest.testRetrySpec
        )

        aggregateVerifier<ExecutionFailed, ExecutionFailedState>()
            .inject(DefaultNextRetryAtCalculator)
            .given(executionFailedCreated)
            .`when`(prepareCompensation)
            .expectErrorType(IllegalStateException::class.java)
            .expectState {
                assertThat(it.id, equalTo(prepareCompensation.id))
                assertThat(it.status, equalTo(ExecutionFailedStatus.FAILED))
                assertThat(it.retryState.retries, equalTo(10))
                assertThat(it.canNextRetry(), equalTo(false))
            }
            .verify()
    }

    @Test
    fun onForcePrepare() {
        val prepareCompensation = ForcePrepareCompensation(id = GlobalIdGenerator.generateAsString())
        val executionFailedCreated = ExecutionFailedCreated(
            eventId = EVENT_ID,
            processor = processor,
            functionKind = functionKind,
            error = error,
            executeAt = System.currentTimeMillis(),
            retryState = DefaultNextRetryAtCalculator.nextRetryState(
                DefaultNextRetryAtCalculatorTest.testRetrySpec,
                10
            ),
            retrySpec = DefaultNextRetryAtCalculatorTest.testRetrySpec
        )

        aggregateVerifier<ExecutionFailed, ExecutionFailedState>()
            .inject(DefaultNextRetryAtCalculator)
            .given(executionFailedCreated)
            .`when`(prepareCompensation)
            .expectNoError()
            .expectEventType(CompensationPrepared::class.java)
            .expectState {
                assertThat(it.id, equalTo(prepareCompensation.id))
                assertThat(it.status, equalTo(ExecutionFailedStatus.PREPARED))
                assertThat(it.retryState.retries, equalTo(11))
                assertThat(it.canNextRetry(), equalTo(false))
            }
            .verify()
    }

    @Test
    fun onFailed() {
        val applyExecutionFailed = ApplyExecutionFailed(
            id = GlobalIdGenerator.generateAsString(),
            error = error,
            executeAt = System.currentTimeMillis(),
            recoverable = RecoverableType.RECOVERABLE
        )
        val executionFailedCreated = ExecutionFailedCreated(
            eventId = EVENT_ID,
            processor = processor,
            functionKind = functionKind,
            error = error,
            executeAt = System.currentTimeMillis(),
            retryState = DefaultNextRetryAtCalculator.nextRetryState(DefaultNextRetryAtCalculatorTest.testRetrySpec, 0),
            retrySpec = DefaultNextRetryAtCalculatorTest.testRetrySpec,
            recoverable = RecoverableType.UNKNOWN
        )

        val compensationPrepared = CompensationPrepared(
            eventId = EVENT_ID,
            processor = processor,
            functionKind = functionKind,
            retryState = DefaultNextRetryAtCalculator.nextRetryState(DefaultNextRetryAtCalculatorTest.testRetrySpec, 1)
        )

        aggregateVerifier<ExecutionFailed, ExecutionFailedState>()
            .given(
                executionFailedCreated,
                compensationPrepared
            )
            .`when`(applyExecutionFailed)
            .expectNoError()
            .expectEventType(ExecutionFailedApplied::class.java)
            .expectState {
                assertThat(it.id, equalTo(applyExecutionFailed.id))
                assertThat(it.status, equalTo(ExecutionFailedStatus.FAILED))
                assertThat(it.recoverable, equalTo(applyExecutionFailed.recoverable))
                assertThat(it.retryState.retries, equalTo(1))
                assertThat(it.canRetry(), equalTo(true))
                assertThat(it.canNextRetry(), equalTo(false))
            }
            .verify().then()
            .given()
            .`when`(applyExecutionFailed)
            .expectErrorType(IllegalStateException::class.java)
            .expectState {
                assertThat(it.status, equalTo(ExecutionFailedStatus.FAILED))
                assertThat(it.retryState.retries, equalTo(1))
            }
            .verify()
    }

    @Test
    fun onSucceed() {
        val applyExecutionSuccess = ApplyExecutionSuccess(
            id = GlobalIdGenerator.generateAsString(),
            executeAt = System.currentTimeMillis()
        )
        val executionFailedCreated = ExecutionFailedCreated(
            eventId = EVENT_ID,
            processor = processor,
            functionKind = functionKind,
            error = error,
            executeAt = System.currentTimeMillis(),
            retryState = DefaultNextRetryAtCalculator.nextRetryState(DefaultNextRetryAtCalculatorTest.testRetrySpec, 0),
            retrySpec = DefaultNextRetryAtCalculatorTest.testRetrySpec
        )

        aggregateVerifier<ExecutionFailed, ExecutionFailedState>()
            .given(
                executionFailedCreated,
                CompensationPrepared(
                    eventId = EVENT_ID,
                    processor = processor,
                    functionKind = functionKind,
                    retryState = DefaultNextRetryAtCalculator.nextRetryState(
                        DefaultNextRetryAtCalculatorTest.testRetrySpec,
                        1
                    )
                )
            )
            .`when`(applyExecutionSuccess)
            .expectNoError()
            .expectEventType(ExecutionSuccessApplied::class.java)
            .expectState {
                assertThat(it.id, equalTo(applyExecutionSuccess.id))
                assertThat(it.status, equalTo(ExecutionFailedStatus.SUCCEEDED))
                assertThat(it.recoverable, equalTo(executionFailedCreated.recoverable))
                assertThat(it.retryState.retries, equalTo(1))
                assertThat(it.canRetry(), equalTo(false))
                assertThat(it.canNextRetry(), equalTo(false))
            }
            .verify().then()
            .given()
            .`when`(applyExecutionSuccess)
            .expectErrorType(IllegalStateException::class.java)
            .expectState {
                assertThat(it.status, equalTo(ExecutionFailedStatus.SUCCEEDED))
                assertThat(it.retryState.retries, equalTo(1))
            }
            .verify()
    }

    @Test
    fun onApplyRetrySpec() {
        val applyRetrySpec = ApplyRetrySpec(
            id = GlobalIdGenerator.generateAsString(),
            maxRetries = 20,
            minBackoff = 180,
            executionTimeout = 100
        )
        val executionFailedCreated = ExecutionFailedCreated(
            eventId = EVENT_ID,
            processor = processor,
            functionKind = functionKind,
            error = error,
            executeAt = System.currentTimeMillis(),
            retryState = DefaultNextRetryAtCalculator.nextRetryState(DefaultNextRetryAtCalculatorTest.testRetrySpec, 0),
            retrySpec = DefaultNextRetryAtCalculatorTest.testRetrySpec
        )

        aggregateVerifier<ExecutionFailed, ExecutionFailedState>()
            .inject(DefaultNextRetryAtCalculator)
            .given(executionFailedCreated)
            .`when`(applyRetrySpec)
            .expectNoError()
            .expectEventType(RetrySpecApplied::class.java)
            .expectState {
                assertThat(it.id, equalTo(applyRetrySpec.id))
                assertThat(it.retrySpec.maxRetries, equalTo(applyRetrySpec.maxRetries))
                assertThat(it.retrySpec.minBackoff, equalTo(applyRetrySpec.minBackoff))
                assertThat(it.retrySpec.executionTimeout, equalTo(applyRetrySpec.executionTimeout))
            }
            .verify()
    }

    @Test
    fun onMarkRecoverable() {
        val markRecoverable = MarkRecoverable(
            id = GlobalIdGenerator.generateAsString(),
            recoverable = RecoverableType.RECOVERABLE
        )
        val executionFailedCreated = ExecutionFailedCreated(
            eventId = EVENT_ID,
            processor = processor,
            functionKind = functionKind,
            error = error,
            executeAt = System.currentTimeMillis(),
            retryState = DefaultNextRetryAtCalculator.nextRetryState(DefaultNextRetryAtCalculatorTest.testRetrySpec, 0),
            retrySpec = DefaultNextRetryAtCalculatorTest.testRetrySpec,
            recoverable = RecoverableType.UNKNOWN
        )

        aggregateVerifier<ExecutionFailed, ExecutionFailedState>()
            .inject(DefaultNextRetryAtCalculator)
            .given(executionFailedCreated)
            .`when`(markRecoverable)
            .expectNoError()
            .expectEventType(RecoverableMarked::class.java)
            .expectState {
                assertThat(it.id, equalTo(markRecoverable.id))
                assertThat(it.recoverable, equalTo(markRecoverable.recoverable))
            }
            .verify().then()
            .given()
            .`when`(markRecoverable)
            .expectErrorType(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun onChangeFunctionKind() {
        val changeFunctionKind = ChangeFunctionKind(
            id = GlobalIdGenerator.generateAsString(),
            functionKind = FunctionKind.STATE_EVENT
        )
        val executionFailedCreated = ExecutionFailedCreated(
            eventId = EVENT_ID,
            processor = processor,
            functionKind = functionKind,
            error = error,
            executeAt = System.currentTimeMillis(),
            retryState = DefaultNextRetryAtCalculator.nextRetryState(DefaultNextRetryAtCalculatorTest.testRetrySpec, 0),
            retrySpec = DefaultNextRetryAtCalculatorTest.testRetrySpec,
            recoverable = RecoverableType.RECOVERABLE
        )

        aggregateVerifier<ExecutionFailed, ExecutionFailedState>()
            .inject(DefaultNextRetryAtCalculator)
            .given(executionFailedCreated)
            .`when`(changeFunctionKind)
            .expectNoError()
            .expectEventType(FunctionKindChanged::class.java)
            .expectState {
                assertThat(it.id, equalTo(changeFunctionKind.id))
                assertThat(it.recoverable, equalTo(executionFailedCreated.recoverable))
                assertThat(it.functionKind, equalTo(changeFunctionKind.functionKind))
            }
            .verify().then()
            .given()
            .`when`(changeFunctionKind)
            .expectErrorType(IllegalArgumentException::class.java)
            .verify()
    }
}
