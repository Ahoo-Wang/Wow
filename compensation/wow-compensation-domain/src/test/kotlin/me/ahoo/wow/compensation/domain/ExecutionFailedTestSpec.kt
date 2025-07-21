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
import me.ahoo.wow.compensation.api.CompensationPrepared
import me.ahoo.wow.compensation.api.ExecutionFailedCreated
import me.ahoo.wow.compensation.api.ExecutionFailedStatus
import me.ahoo.wow.compensation.api.PrepareCompensation
import me.ahoo.wow.compensation.domain.ExecutionFailedTest.Companion.EVENT_ID
import me.ahoo.wow.compensation.domain.ExecutionFailedTest.Companion.function
import me.ahoo.wow.compensation.domain.ExecutionFailedTest.Companion.newError
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.test.AggregateSpec

class ExecutionFailedTestSpec : AggregateSpec<ExecutionFailed, ExecutionFailedState>({
    given {
        val prepareCompensation = PrepareCompensation(id = generateGlobalId())
        val executionFailedCreated = ExecutionFailedCreated(
            eventId = EVENT_ID,
            function = function,
            error = newError(),
            executeAt = System.currentTimeMillis(),
            retryState = DefaultNextRetryAtCalculator.nextRetryState(DefaultNextRetryAtCalculatorTest.testRetrySpec, 0),
            retrySpec = DefaultNextRetryAtCalculatorTest.testRetrySpec,
        )
        inject {
            register(DefaultNextRetryAtCalculator)
        }
        givenEvent(executionFailedCreated) {
            whenCommand(prepareCompensation) {
                expectNoError()
                    .expectEventType(CompensationPrepared::class.java)
                    .expectEventBody<CompensationPrepared> {
                        eventId.assert().isEqualTo(executionFailedCreated.eventId)
                        function.assert().isEqualTo(executionFailedCreated.function)
                        retryState.retries.assert().isEqualTo(executionFailedCreated.retryState.retries + 1)
                    }
                    .expectState {
                        id.assert().isEqualTo(prepareCompensation.id)
                        status.assert().isEqualTo(ExecutionFailedStatus.PREPARED)
                        retryState.retries.assert().isEqualTo(executionFailedCreated.retryState.retries + 1)
                        eventId.assert().isEqualTo(executionFailedCreated.eventId)
                        function.assert().isEqualTo(executionFailedCreated.function)
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
})
