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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.exception.RecoverableType
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.compensation.api.ApplyExecutionFailed
import me.ahoo.wow.compensation.api.ApplyExecutionSuccess
import me.ahoo.wow.compensation.api.ApplyRetrySpec
import me.ahoo.wow.compensation.api.ChangeFunction
import me.ahoo.wow.compensation.api.CompensationPrepared
import me.ahoo.wow.compensation.api.CreateExecutionFailed
import me.ahoo.wow.compensation.api.ErrorDetails
import me.ahoo.wow.compensation.api.EventId
import me.ahoo.wow.compensation.api.ExecutionFailedApplied
import me.ahoo.wow.compensation.api.ExecutionFailedCreated
import me.ahoo.wow.compensation.api.ExecutionFailedStatus
import me.ahoo.wow.compensation.api.ExecutionSuccessApplied
import me.ahoo.wow.compensation.api.ForcePrepareCompensation
import me.ahoo.wow.compensation.api.FunctionChanged
import me.ahoo.wow.compensation.api.MarkRecoverable
import me.ahoo.wow.compensation.api.PrepareCompensation
import me.ahoo.wow.compensation.api.RecoverableMarked
import me.ahoo.wow.compensation.api.RetrySpec
import me.ahoo.wow.compensation.api.RetrySpecApplied
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.test.aggregate.whenCommand
import me.ahoo.wow.test.aggregateVerifier
import org.junit.jupiter.api.Test

class ExecutionFailedTest {
    companion object {
        val EVENT_AGGREGATE = "order.order".toNamedAggregate()
        val EVENT_ID = EventId(generateGlobalId(), EVENT_AGGREGATE.aggregateId(), 1)
        val function = FunctionInfoData(
            functionKind = FunctionKind.EVENT,
            contextName = "order",
            processorName = "OrderProjector",
            name = "onEvent"
        )

        private fun newError(): ErrorDetails {
            return ErrorDetails(generateGlobalId(), "errorMsg", "stackTrace")
        }
    }

    @Test
    fun onCreate() {
        val createExecutionFailed = CreateExecutionFailed(
            eventId = EVENT_ID,
            function = function,
            error = newError(),
            executeAt = System.currentTimeMillis(),
            recoverable = RecoverableType.RECOVERABLE,
        )

        aggregateVerifier<ExecutionFailed, ExecutionFailedState>()
            .inject(DefaultNextRetryAtCalculatorTest.testRetrySpec)
            .inject(DefaultNextRetryAtCalculator)
            .whenCommand(createExecutionFailed)
            .expectNoError()
            .expectEventType(ExecutionFailedCreated::class.java)
            .expectState {
                it.eventId.assert().isEqualTo(createExecutionFailed.eventId)
                it.function.assert().isEqualTo(createExecutionFailed.function)
                it.error.assert().isEqualTo(createExecutionFailed.error)
                it.executeAt.assert().isEqualTo(createExecutionFailed.executeAt)
                it.status.assert().isEqualTo(ExecutionFailedStatus.FAILED)
                it.retryState.retries.assert().isZero()
                it.isRetryable.assert().isTrue()
                it.retryState.timeout().assert().isFalse()
                it.retrySpec.assert().isEqualTo(DefaultNextRetryAtCalculatorTest.testRetrySpec)
                it.canRetry().assert().isTrue()
                it.canNextRetry().assert().isFalse()
                it.recoverable.assert().isEqualTo(createExecutionFailed.recoverable)
            }
            .verify()
    }

    @Test
    fun onCreate_CommandRetrySpec() {
        val createExecutionFailed = CreateExecutionFailed(
            eventId = EVENT_ID,
            function = function,
            error = newError(),
            executeAt = System.currentTimeMillis(),
            recoverable = RecoverableType.RECOVERABLE,
            retrySpec = RetrySpec(3, 3, 3),
        )

        aggregateVerifier<ExecutionFailed, ExecutionFailedState>()
            .inject(DefaultNextRetryAtCalculatorTest.testRetrySpec)
            .inject(DefaultNextRetryAtCalculator)
            .whenCommand(createExecutionFailed)
            .expectNoError()
            .expectEventType(ExecutionFailedCreated::class.java)
            .expectState {
                it.eventId.assert().isEqualTo(createExecutionFailed.eventId)
                it.function.assert().isEqualTo(createExecutionFailed.function)
                it.error.assert().isEqualTo(createExecutionFailed.error)
                it.executeAt.assert().isEqualTo(createExecutionFailed.executeAt)
                it.status.assert().isEqualTo(ExecutionFailedStatus.FAILED)
                it.retryState.retries.assert().isZero()
                it.isRetryable.assert().isTrue()
                it.retryState.timeout().assert().isFalse()
                it.retrySpec.assert().isEqualTo(createExecutionFailed.retrySpec)
                it.canRetry().assert().isTrue()
                it.canNextRetry().assert().isFalse()
                it.recoverable.assert().isEqualTo(createExecutionFailed.recoverable)
            }
            .verify()
    }

    @Test
    fun onPrepare() {
        val prepareCompensation = PrepareCompensation(id = generateGlobalId())
        val executionFailedCreated = ExecutionFailedCreated(
            eventId = EVENT_ID,
            function = function,
            error = newError(),
            executeAt = System.currentTimeMillis(),
            retryState = DefaultNextRetryAtCalculator.nextRetryState(DefaultNextRetryAtCalculatorTest.testRetrySpec, 0),
            retrySpec = DefaultNextRetryAtCalculatorTest.testRetrySpec,
        )

        aggregateVerifier<ExecutionFailed, ExecutionFailedState>()
            .inject(DefaultNextRetryAtCalculator)
            .given(executionFailedCreated)
            .whenCommand(prepareCompensation)
            .expectNoError()
            .expectEventType(CompensationPrepared::class.java)
            .expectEventBody<CompensationPrepared> {
                it.eventId.assert().isEqualTo(executionFailedCreated.eventId)
                it.function.assert().isEqualTo(executionFailedCreated.function)
                it.retryState.retries.assert().isEqualTo(executionFailedCreated.retryState.retries + 1)
            }
            .expectState {
                it.id.assert().isEqualTo(prepareCompensation.id)
                it.status.assert().isEqualTo(ExecutionFailedStatus.PREPARED)
                it.retryState.retries.assert().isEqualTo(executionFailedCreated.retryState.retries + 1)
                it.eventId.assert().isEqualTo(executionFailedCreated.eventId)
                it.function.assert().isEqualTo(executionFailedCreated.function)
                it.canRetry().assert().isFalse()
            }
            .verify()
            .fork {
                whenCommand(prepareCompensation)
                    .expectErrorType(IllegalStateException::class.java)
                    .expectState {
                        it.status.assert().isEqualTo(ExecutionFailedStatus.PREPARED)
                    }
                    .verify()
            }
    }

    @Test
    fun onPrepareGivenMaxRetry() {
        val prepareCompensation = PrepareCompensation(id = generateGlobalId())
        val executionFailedCreated = ExecutionFailedCreated(
            eventId = EVENT_ID,
            function = function,
            error = newError(),
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
            .whenCommand(prepareCompensation)
            .expectErrorType(IllegalStateException::class.java)
            .expectState {
                it.id.assert().isEqualTo(prepareCompensation.id)
                it.status.assert().isEqualTo(ExecutionFailedStatus.FAILED)
                it.retryState.retries.assert().isEqualTo(10)
                it.canRetry().assert().isFalse()
            }
            .verify()
    }

    @Test
    fun onForcePrepare() {
        val prepareCompensation = ForcePrepareCompensation(id = generateGlobalId())
        val executionFailedCreated = ExecutionFailedCreated(
            eventId = EVENT_ID,
            function = function,
            error = newError(),
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
            .whenCommand(prepareCompensation)
            .expectNoError()
            .expectEventType(CompensationPrepared::class.java)
            .expectState {
                it.id.assert().isEqualTo(prepareCompensation.id)
                it.status.assert().isEqualTo(ExecutionFailedStatus.PREPARED)
                it.retryState.retries.assert().isEqualTo(11)
                it.canNextRetry().assert().isFalse()
            }
            .verify()
    }

    @Test
    fun onApplyExecutionFailed() {
        val applyExecutionFailed = ApplyExecutionFailed(
            id = generateGlobalId(),
            error = newError(),
            executeAt = System.currentTimeMillis(),
            recoverable = RecoverableType.RECOVERABLE
        )
        val executionFailedCreated = ExecutionFailedCreated(
            eventId = EVENT_ID,
            function = function,
            error = newError(),
            executeAt = System.currentTimeMillis(),
            retryState = DefaultNextRetryAtCalculator.nextRetryState(DefaultNextRetryAtCalculatorTest.testRetrySpec, 0),
            retrySpec = DefaultNextRetryAtCalculatorTest.testRetrySpec,
            recoverable = RecoverableType.UNKNOWN
        )

        val compensationPrepared = CompensationPrepared(
            eventId = EVENT_ID,
            function = function,
            retryState = DefaultNextRetryAtCalculator.nextRetryState(DefaultNextRetryAtCalculatorTest.testRetrySpec, 1)
        )

        aggregateVerifier<ExecutionFailed, ExecutionFailedState>()
            .given(
                executionFailedCreated,
                compensationPrepared
            )
            .whenCommand(applyExecutionFailed)
            .expectNoError()
            .expectEventType(ExecutionFailedApplied::class.java)
            .expectState {
                it.id.assert().isEqualTo(applyExecutionFailed.id)
                it.status.assert().isEqualTo(ExecutionFailedStatus.FAILED)
                it.error.assert().isEqualTo(applyExecutionFailed.error)
                it.executeAt.assert().isEqualTo(applyExecutionFailed.executeAt)
                it.recoverable.assert().isEqualTo(applyExecutionFailed.recoverable)
                it.retryState.retries.assert().isEqualTo(1)
                it.canRetry().assert().isTrue()
                it.canNextRetry().assert().isFalse()
            }
            .verify()
            .fork {
                whenCommand(applyExecutionFailed)
                    .expectErrorType(IllegalStateException::class.java)
                    .expectState {
                        it.status.assert().isEqualTo(ExecutionFailedStatus.FAILED)
                        it.retryState.retries.assert().isEqualTo(1)
                    }
                    .verify()
            }
    }

    @Test
    fun onSucceed() {
        val applyExecutionSuccess = ApplyExecutionSuccess(
            id = generateGlobalId(),
            executeAt = System.currentTimeMillis()
        )
        val executionFailedCreated = ExecutionFailedCreated(
            eventId = EVENT_ID,
            function = function,
            error = newError(),
            executeAt = System.currentTimeMillis(),
            retryState = DefaultNextRetryAtCalculator.nextRetryState(DefaultNextRetryAtCalculatorTest.testRetrySpec, 0),
            retrySpec = DefaultNextRetryAtCalculatorTest.testRetrySpec
        )

        aggregateVerifier<ExecutionFailed, ExecutionFailedState>()
            .given(
                executionFailedCreated,
                CompensationPrepared(
                    eventId = EVENT_ID,
                    function = function,
                    retryState = DefaultNextRetryAtCalculator.nextRetryState(
                        DefaultNextRetryAtCalculatorTest.testRetrySpec,
                        1
                    )
                )
            )
            .whenCommand(applyExecutionSuccess)
            .expectNoError()
            .expectEventType(ExecutionSuccessApplied::class.java)
            .expectState {
                it.id.assert().isEqualTo(applyExecutionSuccess.id)
                it.status.assert().isEqualTo(ExecutionFailedStatus.SUCCEEDED)
                it.error.assert().isEqualTo(executionFailedCreated.error)
                it.recoverable.assert().isEqualTo(executionFailedCreated.recoverable)
                it.retryState.retries.assert().isEqualTo(1)
                it.canRetry().assert().isFalse()
                it.canNextRetry().assert().isFalse()
            }
            .verify()
            .fork {
                whenCommand(applyExecutionSuccess)
                    .expectErrorType(IllegalStateException::class.java)
                    .expectState {
                        it.status.assert().isEqualTo(ExecutionFailedStatus.SUCCEEDED)
                        it.retryState.retries.assert().isEqualTo(1)
                    }
                    .verify()
            }
    }

    @Test
    fun onApplyRetrySpec() {
        val applyRetrySpec = ApplyRetrySpec(
            id = generateGlobalId(),
            maxRetries = 20,
            minBackoff = 180,
            executionTimeout = 100
        )
        val executionFailedCreated = ExecutionFailedCreated(
            eventId = EVENT_ID,
            function = function,
            error = newError(),
            executeAt = System.currentTimeMillis(),
            retryState = DefaultNextRetryAtCalculator.nextRetryState(DefaultNextRetryAtCalculatorTest.testRetrySpec, 0),
            retrySpec = DefaultNextRetryAtCalculatorTest.testRetrySpec
        )

        aggregateVerifier<ExecutionFailed, ExecutionFailedState>()
            .inject(DefaultNextRetryAtCalculator)
            .given(executionFailedCreated)
            .whenCommand(applyRetrySpec)
            .expectNoError()
            .expectEventType(RetrySpecApplied::class.java)
            .expectState {
                it.id.assert().isEqualTo(applyRetrySpec.id)
                it.error.assert().isEqualTo(executionFailedCreated.error)
                it.retrySpec.maxRetries.assert().isEqualTo(applyRetrySpec.maxRetries)
                it.retrySpec.minBackoff.assert().isEqualTo(applyRetrySpec.minBackoff)
                it.retrySpec.executionTimeout.assert().isEqualTo(applyRetrySpec.executionTimeout)
            }
            .verify()
    }

    @Test
    fun onMarkRecoverable() {
        val markRecoverable = MarkRecoverable(
            id = generateGlobalId(),
            recoverable = RecoverableType.RECOVERABLE
        )
        val executionFailedCreated = ExecutionFailedCreated(
            eventId = EVENT_ID,
            function = function,
            error = newError(),
            executeAt = System.currentTimeMillis(),
            retryState = DefaultNextRetryAtCalculator.nextRetryState(DefaultNextRetryAtCalculatorTest.testRetrySpec, 0),
            retrySpec = DefaultNextRetryAtCalculatorTest.testRetrySpec,
            recoverable = RecoverableType.UNKNOWN
        )

        aggregateVerifier<ExecutionFailed, ExecutionFailedState>()
            .inject(DefaultNextRetryAtCalculator)
            .given(executionFailedCreated)
            .whenCommand(markRecoverable)
            .expectNoError()
            .expectEventType(RecoverableMarked::class.java)
            .expectState {
                it.id.assert().isEqualTo(markRecoverable.id)
                it.recoverable.assert().isEqualTo(markRecoverable.recoverable)
            }
            .verify()
            .fork {
                whenCommand(markRecoverable)
                    .expectErrorType(IllegalArgumentException::class.java)
                    .verify()
            }
    }

    @Test
    fun onChangeFunction() {
        val changeFunction = ChangeFunction(
            id = generateGlobalId(),
            contextName = generateGlobalId(),
            processorName = generateGlobalId(),
            name = generateGlobalId(),
            functionKind = FunctionKind.STATE_EVENT
        )
        val executionFailedCreated = ExecutionFailedCreated(
            eventId = EVENT_ID,
            function = function,
            error = newError(),
            executeAt = System.currentTimeMillis(),
            retryState = DefaultNextRetryAtCalculator.nextRetryState(DefaultNextRetryAtCalculatorTest.testRetrySpec, 0),
            retrySpec = DefaultNextRetryAtCalculatorTest.testRetrySpec,
            recoverable = RecoverableType.RECOVERABLE
        )

        aggregateVerifier<ExecutionFailed, ExecutionFailedState>()
            .inject(DefaultNextRetryAtCalculator)
            .given(executionFailedCreated)
            .whenCommand(changeFunction)
            .expectNoError()
            .expectEventType(FunctionChanged::class.java)
            .expectState {
                it.id.assert().isEqualTo(changeFunction.id)
                it.recoverable.assert().isEqualTo(executionFailedCreated.recoverable)
                it.function.isSameFunction(changeFunction).assert().isTrue()
            }
            .verify()
            .fork {
                whenCommand(changeFunction)
                    .expectErrorType(IllegalArgumentException::class.java)
                    .verify()
            }
    }
}
