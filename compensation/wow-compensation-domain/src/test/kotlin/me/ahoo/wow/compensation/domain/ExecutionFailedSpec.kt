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
import me.ahoo.wow.test.AggregateSpec

val EVENT_AGGREGATE = "order.order".toNamedAggregate()
val EVENT_ID = EventId(generateGlobalId(), EVENT_AGGREGATE.aggregateId(), 1)
val function = FunctionInfoData(
    functionKind = FunctionKind.EVENT,
    contextName = "order",
    processorName = "OrderProjector",
    name = "onEvent"
)

fun newError(): ErrorDetails {
    return ErrorDetails(generateGlobalId(), "errorMsg", "stackTrace")
}

class ExecutionFailedSpec : AggregateSpec<ExecutionFailed, ExecutionFailedState>({
    inject {
        register(DefaultNextRetryAtCalculatorTest.testRetrySpec)
        register(DefaultNextRetryAtCalculator)
    }
    on {
        val createExecutionFailed = CreateExecutionFailed(
            eventId = EVENT_ID,
            function = function,
            error = newError(),
            executeAt = System.currentTimeMillis(),
            recoverable = RecoverableType.RECOVERABLE,
        )
        whenCommand(createExecutionFailed) {
            expectNoError()
            expectEventType(ExecutionFailedCreated::class.java)
            expectState {
                eventId.assert().isEqualTo(createExecutionFailed.eventId)
                function.assert().isEqualTo(createExecutionFailed.function)
                error.assert().isEqualTo(createExecutionFailed.error)
                executeAt.assert().isEqualTo(createExecutionFailed.executeAt)
                status.assert().isEqualTo(ExecutionFailedStatus.FAILED)
                retryState.retries.assert().isZero()
                isRetryable.assert().isTrue()
                retryState.timeout().assert().isFalse()
                retrySpec.assert().isEqualTo(DefaultNextRetryAtCalculatorTest.testRetrySpec)
                canRetry().assert().isTrue()
                canNextRetry().assert().isFalse()
                recoverable.assert().isEqualTo(createExecutionFailed.recoverable)
            }
            fork {
                val stateRoot = stateRoot
                val currentRetryState = stateRoot.retryState
                val prepareCompensation = PrepareCompensation(id = stateRoot.id)
                whenCommand(prepareCompensation) {
                    expectNoError()
                    expectEventType(CompensationPrepared::class.java)
                    expectEventBody<CompensationPrepared> {
                        eventId.assert().isEqualTo(stateRoot.eventId)
                        function.assert().isEqualTo(stateRoot.function)
                        retryState.retries.assert().isEqualTo(currentRetryState.retries + 1)
                    }
                    expectState {
                        id.assert().isEqualTo(prepareCompensation.id)
                        status.assert().isEqualTo(ExecutionFailedStatus.PREPARED)
                        retryState.retries.assert().isEqualTo(currentRetryState.retries + 1)
                        eventId.assert().isEqualTo(stateRoot.eventId)
                        function.assert().isEqualTo(stateRoot.function)
                        canRetry().assert().isFalse()
                    }
                    fork {
                        whenCommand(prepareCompensation) {
                            expectErrorType(IllegalStateException::class.java)
                                .expectState {
                                    status.assert().isEqualTo(ExecutionFailedStatus.PREPARED)
                                }
                        }
                    }
                }
            }
        }
    }

    on {
        name("WithRetrySpec")
        val createExecutionFailed = CreateExecutionFailed(
            eventId = EVENT_ID,
            function = function,
            error = newError(),
            executeAt = System.currentTimeMillis(),
            recoverable = RecoverableType.RECOVERABLE,
            retrySpec = RetrySpec(3, 3, 3),
        )
        whenCommand(createExecutionFailed) {
            expectNoError()
            expectEventType(ExecutionFailedCreated::class.java)
            expectState {
                eventId.assert().isEqualTo(createExecutionFailed.eventId)
                function.assert().isEqualTo(createExecutionFailed.function)
                error.assert().isEqualTo(createExecutionFailed.error)
                executeAt.assert().isEqualTo(createExecutionFailed.executeAt)
                status.assert().isEqualTo(ExecutionFailedStatus.FAILED)
                retryState.retries.assert().isZero()
                isRetryable.assert().isTrue()
                retryState.timeout().assert().isFalse()
                retrySpec.assert().isEqualTo(createExecutionFailed.retrySpec)
                canRetry().assert().isTrue()
                canNextRetry().assert().isFalse()
                recoverable.assert().isEqualTo(createExecutionFailed.recoverable)
            }
        }
    }

    on {
        name("MaxRetry")
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
        givenEvent(executionFailedCreated) {
            val prepareCompensation = PrepareCompensation(id = generateGlobalId())
            whenCommand(prepareCompensation) {
                expectErrorType(IllegalStateException::class.java)
                    .expectState {
                        id.assert().isEqualTo(prepareCompensation.id)
                        status.assert().isEqualTo(ExecutionFailedStatus.FAILED)
                        retryState.retries.assert().isEqualTo(10)
                        canRetry().assert().isFalse()
                    }
            }
            val forcePrepareCompensation = ForcePrepareCompensation(id = generateGlobalId())
            whenCommand(forcePrepareCompensation) {
                expectNoError()
                expectEventType(CompensationPrepared::class)
                expectState {
                    id.assert().isEqualTo(forcePrepareCompensation.id)
                    status.assert().isEqualTo(ExecutionFailedStatus.PREPARED)
                    retryState.retries.assert().isEqualTo(11)
                    canNextRetry().assert().isFalse()
                }
            }
        }
    }
    on {
        val executionFailedCreated = ExecutionFailedCreated(
            eventId = EVENT_ID,
            function = function,
            error = newError(),
            executeAt = System.currentTimeMillis(),
            retryState = DefaultNextRetryAtCalculator.nextRetryState(
                DefaultNextRetryAtCalculatorTest.testRetrySpec,
                0
            ),
            retrySpec = DefaultNextRetryAtCalculatorTest.testRetrySpec,
            recoverable = RecoverableType.UNKNOWN
        )

        val compensationPrepared = CompensationPrepared(
            eventId = EVENT_ID,
            function = function,
            retryState = DefaultNextRetryAtCalculator.nextRetryState(
                DefaultNextRetryAtCalculatorTest.testRetrySpec,
                1
            )
        )
        givenEvent(arrayOf(executionFailedCreated, compensationPrepared)) {
            val applyExecutionFailed = ApplyExecutionFailed(
                id = generateGlobalId(),
                error = newError(),
                executeAt = System.currentTimeMillis(),
                recoverable = RecoverableType.RECOVERABLE
            )
            whenCommand(applyExecutionFailed) {
                expectNoError()
                expectEventType(ExecutionFailedApplied::class)
                expectState {
                    id.assert().isEqualTo(applyExecutionFailed.id)
                    status.assert().isEqualTo(ExecutionFailedStatus.FAILED)
                    error.assert().isEqualTo(applyExecutionFailed.error)
                    executeAt.assert().isEqualTo(applyExecutionFailed.executeAt)
                    recoverable.assert().isEqualTo(applyExecutionFailed.recoverable)
                    retryState.retries.assert().isEqualTo(1)
                    canRetry().assert().isTrue()
                    canNextRetry().assert().isFalse()
                }
                fork {
                    whenCommand(applyExecutionFailed) {
                        expectErrorType(IllegalStateException::class)
                        expectState {
                            status.assert().isEqualTo(ExecutionFailedStatus.FAILED)
                            retryState.retries.assert().isEqualTo(1)
                        }
                    }
                }
            }
        }
    }
    on {
        val executionFailedCreated = ExecutionFailedCreated(
            eventId = EVENT_ID,
            function = function,
            error = newError(),
            executeAt = System.currentTimeMillis(),
            retryState = DefaultNextRetryAtCalculator.nextRetryState(
                DefaultNextRetryAtCalculatorTest.testRetrySpec,
                0
            ),
            retrySpec = DefaultNextRetryAtCalculatorTest.testRetrySpec
        )
        val compensationPrepared = CompensationPrepared(
            eventId = EVENT_ID,
            function = function,
            retryState = DefaultNextRetryAtCalculator.nextRetryState(
                DefaultNextRetryAtCalculatorTest.testRetrySpec,
                1
            )
        )
        givenEvent(arrayOf(executionFailedCreated, compensationPrepared)) {
            val applyExecutionSuccess = ApplyExecutionSuccess(
                id = generateGlobalId(),
                executeAt = System.currentTimeMillis()
            )
            whenCommand(applyExecutionSuccess) {
                expectNoError()
                expectEventType(ExecutionSuccessApplied::class)
                expectState {
                    id.assert().isEqualTo(applyExecutionSuccess.id)
                    status.assert().isEqualTo(ExecutionFailedStatus.SUCCEEDED)
                    error.assert().isEqualTo(executionFailedCreated.error)
                    recoverable.assert().isEqualTo(executionFailedCreated.recoverable)
                    retryState.retries.assert().isEqualTo(1)
                    canRetry().assert().isFalse()
                    canNextRetry().assert().isFalse()
                }
                fork {
                    whenCommand(applyExecutionSuccess) {
                        expectErrorType(IllegalStateException::class.java)
                        expectState {
                            status.assert().isEqualTo(ExecutionFailedStatus.SUCCEEDED)
                            retryState.retries.assert().isEqualTo(1)
                        }
                    }
                }
            }
            val applyRetrySpec = ApplyRetrySpec(
                id = generateGlobalId(),
                maxRetries = 20,
                minBackoff = 180,
                executionTimeout = 100
            )
            whenCommand(applyRetrySpec) {
                expectNoError()
                expectEventType(RetrySpecApplied::class.java)
                expectState {
                    id.assert().isEqualTo(applyRetrySpec.id)
                    error.assert().isEqualTo(executionFailedCreated.error)
                    retrySpec.maxRetries.assert().isEqualTo(applyRetrySpec.maxRetries)
                    retrySpec.minBackoff.assert().isEqualTo(applyRetrySpec.minBackoff)
                    retrySpec.executionTimeout.assert().isEqualTo(applyRetrySpec.executionTimeout)
                }
            }

            val markRecoverable = MarkRecoverable(
                id = generateGlobalId(),
                recoverable = RecoverableType.RECOVERABLE
            )
            whenCommand(markRecoverable) {
                expectNoError()
                expectEventType(RecoverableMarked::class.java)
                expectState {
                    id.assert().isEqualTo(markRecoverable.id)
                    recoverable.assert().isEqualTo(markRecoverable.recoverable)
                }
                fork {
                    whenCommand(markRecoverable) {
                        expectErrorType(IllegalArgumentException::class.java)
                    }
                }
            }

            val changeFunction = ChangeFunction(
                id = generateGlobalId(),
                contextName = generateGlobalId(),
                processorName = generateGlobalId(),
                name = generateGlobalId(),
                functionKind = FunctionKind.STATE_EVENT
            )
            whenCommand(changeFunction) {
                expectNoError()
                expectEventType(FunctionChanged::class.java)
                expectState {
                    id.assert().isEqualTo(changeFunction.id)
                    recoverable.assert().isEqualTo(executionFailedCreated.recoverable)
                    function.isSameFunction(changeFunction).assert().isTrue()
                }
                fork {
                    whenCommand(changeFunction) {
                        expectErrorType(IllegalArgumentException::class.java)
                    }
                }
            }
        }
    }
})
