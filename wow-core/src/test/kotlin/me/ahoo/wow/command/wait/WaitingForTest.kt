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

package me.ahoo.wow.command.wait

import me.ahoo.wow.command.COMMAND_GATEWAY_FUNCTION
import me.ahoo.wow.id.generateGlobalId
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test
import java.time.Duration

internal class WaitingForTest {
    private val contextName = "WaitingForTest"

    @Test
    fun processed() {
        val waitStrategy = WaitingFor.stage("PROCESSED", contextName)
        assertThat(waitStrategy.cancelled, equalTo(false))
        assertThat(waitStrategy.terminated, equalTo(false))
        val waitSignal = SimpleWaitSignal(
            commandId = "commandId",
            stage = CommandStage.PROCESSED,
            function = COMMAND_GATEWAY_FUNCTION,
        )
        waitStrategy.waitingLast()
            .test()
            .consumeSubscriptionWith {
                waitStrategy.next(waitSignal)
                assertThat(waitStrategy.terminated, equalTo(true))
            }
            .expectNext(waitSignal)
            .verifyComplete()
    }

    @Test
    fun processedIfSnapshot() {
        val waitStrategy = WaitingFor.stage("PROCESSED", contextName)
        val waitSignal = SimpleWaitSignal(
            commandId = "commandId",
            stage = CommandStage.SNAPSHOT,
            function = COMMAND_GATEWAY_FUNCTION,
        )
        waitStrategy.waitingLast()
            .test()
            .consumeSubscriptionWith {
                waitStrategy.next(waitSignal)
            }
            .verifyTimeout(Duration.ofMillis(100))
        assertThat(waitStrategy.terminated, equalTo(false))
        assertThat(waitStrategy.cancelled, equalTo(true))
    }

    @Test
    fun snapshot() {
        val waitStrategy = WaitingFor.stage("SNAPSHOT", contextName)
        val processedSignal = SimpleWaitSignal(
            commandId = generateGlobalId(),
            stage = CommandStage.PROCESSED,
            function = COMMAND_GATEWAY_FUNCTION,
        )
        val waitSignal = SimpleWaitSignal(
            commandId = generateGlobalId(),
            stage = CommandStage.SNAPSHOT,
            function = COMMAND_GATEWAY_FUNCTION,
        )
        waitStrategy.waitingLast()
            .test()
            .consumeSubscriptionWith {
                waitStrategy.next(processedSignal)
                waitStrategy.next(waitSignal)
            }
            .consumeNextWith {
                assertThat(it.commandId, equalTo(waitSignal.commandId))
            }
            .verifyComplete()
    }

    @Test
    fun snapshotFailFast() {
        val waitStrategy = WaitingFor.snapshot()
        val waitSignal = SimpleWaitSignal(
            commandId = generateGlobalId(),
            stage = CommandStage.PROCESSED,
            function = COMMAND_GATEWAY_FUNCTION,
            errorCode = "ERROR_CODE"
        )
        waitStrategy.waitingLast()
            .test()
            .consumeSubscriptionWith {
                waitStrategy.next(waitSignal)
            }
            .expectNext(waitSignal)
            .verifyComplete()
    }

    @Test
    fun projected() {
        val waitStrategy = WaitingFor.stage("PROJECTED", contextName)
        val processedSignal = SimpleWaitSignal(
            commandId = generateGlobalId(),
            stage = CommandStage.PROCESSED,
            function = COMMAND_GATEWAY_FUNCTION,
        )
        val waitSignal = SimpleWaitSignal(
            commandId = generateGlobalId(),
            stage = CommandStage.PROJECTED,
            function = COMMAND_GATEWAY_FUNCTION.copy(contextName = contextName),
            isLastProjection = true
        )
        waitStrategy.waitingLast()
            .test()
            .consumeSubscriptionWith {
                waitStrategy.next(processedSignal)
                waitStrategy.next(waitSignal)
            }
            .expectNext(waitSignal)
            .verifyComplete()
    }

    @Test
    fun waitingForProjectedProcessor() {
        val waitStrategy = WaitingFor.projected(contextName, "processor")
        val processedSignal = SimpleWaitSignal(
            commandId = generateGlobalId(),
            stage = CommandStage.PROCESSED,
            function = COMMAND_GATEWAY_FUNCTION,
        )
        val waitSignal = SimpleWaitSignal(
            commandId = generateGlobalId(),
            stage = CommandStage.PROJECTED,
            function = COMMAND_GATEWAY_FUNCTION.copy(contextName = contextName, processorName = "processor"),
            isLastProjection = true
        )
        waitStrategy.waitingLast()
            .test()
            .consumeSubscriptionWith {
                waitStrategy.next(processedSignal)
                waitStrategy.next(waitSignal)
            }
            .expectNext(waitSignal)
            .verifyComplete()
    }

    @Test
    fun waitingForProjectedWhenNotLast() {
        val waitStrategy = WaitingFor.projected(contextName)
        val processedSignal = SimpleWaitSignal(
            commandId = generateGlobalId(),
            stage = CommandStage.PROCESSED,
            function = COMMAND_GATEWAY_FUNCTION,
        )
        val waitSignal = SimpleWaitSignal(
            commandId = generateGlobalId(),
            stage = CommandStage.PROJECTED,
            function = COMMAND_GATEWAY_FUNCTION.copy(contextName = contextName),
            isLastProjection = false
        )
        waitStrategy.waitingLast()
            .test()
            .consumeSubscriptionWith {
                waitStrategy.next(processedSignal)
                waitStrategy.next(waitSignal)
            }
            .expectNextCount(0)
            .expectTimeout(Duration.ofMillis(100))
            .verify()
    }

    @Test
    fun waitingForEventHandled() {
        val waitStrategy = WaitingFor.stage("EVENT_HANDLED", contextName)
        val processedSignal = SimpleWaitSignal(
            commandId = generateGlobalId(),
            stage = CommandStage.PROCESSED,
            function = COMMAND_GATEWAY_FUNCTION,
        )
        val waitSignal = SimpleWaitSignal(
            commandId = generateGlobalId(),
            stage = CommandStage.EVENT_HANDLED,
            function = COMMAND_GATEWAY_FUNCTION.copy(contextName = contextName),
            isLastProjection = false
        )
        waitStrategy.waitingLast()
            .test()
            .consumeSubscriptionWith {
                waitStrategy.next(processedSignal)
                waitStrategy.next(waitSignal)
            }
            .expectNext(waitSignal)
            .verifyComplete()
    }

    @Test
    fun waitingForSagaHandled() {
        val waitStrategy = WaitingFor.stage("SAGA_HANDLED", contextName)
        val processedSignal = SimpleWaitSignal(
            commandId = generateGlobalId(),
            stage = CommandStage.PROCESSED,
            function = COMMAND_GATEWAY_FUNCTION,
        )
        val waitSignal = SimpleWaitSignal(
            commandId = generateGlobalId(),
            stage = CommandStage.SAGA_HANDLED,
            function = COMMAND_GATEWAY_FUNCTION.copy(contextName = contextName)
        )
        waitStrategy.waitingLast()
            .test()
            .consumeSubscriptionWith {
                waitStrategy.next(processedSignal)
                waitStrategy.next(waitSignal)
            }
            .expectNext(waitSignal)
            .verifyComplete()
    }

    @Test
    fun waitingWhenNoMatchedContext() {
        val waitStrategy = WaitingFor.projected(contextName)
        waitStrategy.waitingLast()
            .test()
            .consumeSubscriptionWith {
                waitStrategy.next(
                    SimpleWaitSignal(
                        generateGlobalId(),
                        CommandStage.PROJECTED,
                        function = COMMAND_GATEWAY_FUNCTION,
                    )
                )
            }
            .expectNextCount(0)
            .expectTimeout(Duration.ofMillis(100))
            .verify()
    }

    @Test
    fun waitingWhenError() {
        val waitStrategy = WaitingFor.projected(contextName)
        waitStrategy.error(IllegalArgumentException())
        waitStrategy.waitingLast()
            .test()
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun waitingForSent() {
        Assertions.assertThrows<IllegalArgumentException>(IllegalArgumentException::class.java) {
            WaitingFor.stage("SENT", contextName)
        }
    }
}
