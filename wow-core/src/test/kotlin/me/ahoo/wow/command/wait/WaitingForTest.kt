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
import me.ahoo.wow.command.wait.SimpleWaitSignal.Companion.toWaitSignal
import me.ahoo.wow.exception.ErrorCodes
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test
import java.time.Duration

internal class WaitingForTest {
    private val contextName = "WaitingForTest"

    @Test
    fun processed() {
        val waitStrategy = WaitingFor.processed(contextName)

        waitStrategy.next(
            COMMAND_GATEWAY_FUNCTION.toWaitSignal(
                commandId = "commandId",
                stage = CommandStage.SENT,
                result = mapOf("sent" to "value")
            )
        )
        val waitSignal = COMMAND_GATEWAY_FUNCTION.toWaitSignal(
            commandId = "commandId",
            stage = CommandStage.PROCESSED,
            result = mapOf("result" to "value")
        )
        assertThat(
            waitStrategy.toString(),
            equalTo("WaitingFor(stage=PROCESSED, contextName='WaitingForTest', processorName='')")
        )
        waitStrategy.waiting()
            .test()
            .consumeSubscriptionWith {
                waitStrategy.next(waitSignal)
            }
            .expectNext(waitSignal.copyResult(mapOf("sent" to "value", "result" to "value")))
            .verifyComplete()
    }

    @Test
    fun stage() {
        val waitStrategy = WaitingFor.stage("PROCESSED", contextName)
        val waitSignal = SimpleWaitSignal(
            commandId = "commandId",
            stage = CommandStage.PROCESSED,
            function = COMMAND_GATEWAY_FUNCTION,
        )
        waitStrategy.waiting()
            .test()
            .consumeSubscriptionWith {
                waitStrategy.next(waitSignal)
            }
            .expectNext(waitSignal)
            .verifyComplete()
    }

    @Test
    fun snapshot() {
        val waitStrategy = WaitingFor.snapshot(contextName)
        val waitSignal = SimpleWaitSignal(
            commandId = "commandId",
            stage = CommandStage.SNAPSHOT,
            function = COMMAND_GATEWAY_FUNCTION,
        )
        waitStrategy.waiting()
            .test()
            .consumeSubscriptionWith {
                waitStrategy.next(waitSignal)
            }
            .expectNext(waitSignal)
            .verifyComplete()
    }

    @Test
    fun snapshotFailFast() {
        val waitStrategy = WaitingFor.snapshot(contextName)
        val waitSignal = SimpleWaitSignal(
            commandId = "commandId",
            stage = CommandStage.PROCESSED,
            function = COMMAND_GATEWAY_FUNCTION,
            errorCode = "ERROR_CODE"
        )
        waitStrategy.waiting()
            .test()
            .consumeSubscriptionWith {
                waitStrategy.next(waitSignal)
            }
            .expectNext(waitSignal)
            .verifyComplete()
    }

    @Test
    fun projected() {
        val waitStrategy = WaitingFor.projected(contextName)
        val waitSignal = SimpleWaitSignal(
            commandId = "commandId",
            stage = CommandStage.PROJECTED,
            function = COMMAND_GATEWAY_FUNCTION.copy(contextName = contextName),
            isLastProjection = true
        )
        waitStrategy.waiting()
            .test()
            .consumeSubscriptionWith {
                waitStrategy.next(waitSignal)
            }
            .expectNext(waitSignal)
            .verifyComplete()
    }

    @Test
    fun waitingForProjectedProcessor() {
        val waitStrategy = WaitingFor.projected(contextName, "processor")
        val waitSignal = SimpleWaitSignal(
            commandId = "commandId",
            stage = CommandStage.PROJECTED,
            function = COMMAND_GATEWAY_FUNCTION.copy(contextName = contextName, processorName = "processor"),
            isLastProjection = true
        )
        waitStrategy.waiting()
            .test()
            .consumeSubscriptionWith {
                waitStrategy.next(waitSignal)
            }
            .expectNext(waitSignal)
            .verifyComplete()
    }

    @Test
    fun waitingForProjectedWhenNotLast() {
        val waitStrategy = WaitingFor.projected(contextName)
        val waitSignal = SimpleWaitSignal(
            commandId = "commandId",
            stage = CommandStage.PROJECTED,
            function = COMMAND_GATEWAY_FUNCTION.copy(contextName = contextName),
            isLastProjection = false
        )
        waitStrategy.waiting()
            .test()
            .consumeSubscriptionWith {
                waitStrategy.next(waitSignal)
            }
            .expectNextCount(0)
            .expectTimeout(Duration.ofMillis(100))
            .verify()
    }

    @Test
    fun waitingForEventHandled() {
        val waitStrategy = WaitingFor.eventHandled(contextName)
        val waitSignal = SimpleWaitSignal(
            commandId = "commandId",
            stage = CommandStage.EVENT_HANDLED,
            function = COMMAND_GATEWAY_FUNCTION.copy(contextName = contextName),
            isLastProjection = true
        )
        waitStrategy.waiting()
            .test()
            .consumeSubscriptionWith {
                waitStrategy.next(waitSignal)
            }
            .expectNext(waitSignal)
            .verifyComplete()
    }

    @Test
    fun waitingForSagaHandled() {
        val waitStrategy = WaitingFor.sagaHandled(contextName)
        val waitSignal = SimpleWaitSignal(
            commandId = "commandId",
            stage = CommandStage.SAGA_HANDLED,
            function = COMMAND_GATEWAY_FUNCTION.copy(contextName = contextName),
            isLastProjection = true
        )
        waitStrategy.waiting()
            .test()
            .consumeSubscriptionWith {
                waitStrategy.next(waitSignal)
            }
            .expectNext(waitSignal)
            .verifyComplete()
    }

    @Test
    fun waitingWhenFailure() {
        val waitStrategy = WaitingFor.processed(contextName)
        val waitSignal = SimpleWaitSignal(
            commandId = "commandId",
            stage = CommandStage.PROCESSED,
            function = COMMAND_GATEWAY_FUNCTION.copy(contextName = contextName),
            isLastProjection = true,
            errorCode = ErrorCodes.ILLEGAL_ARGUMENT,
            errorMsg = "",
        )
        waitStrategy.waiting()
            .test()
            .consumeSubscriptionWith {
                waitStrategy.next(
                    waitSignal,
                )
            }
            .expectNext(waitSignal)
            .verifyComplete()
    }

    @Test
    fun waitingWhenNoMatchedStage() {
        val waitStrategy = WaitingFor.projected(contextName)
        waitStrategy.waiting()
            .test()
            .consumeSubscriptionWith {
                waitStrategy.next(
                    SimpleWaitSignal(
                        "commandId",
                        CommandStage.PROCESSED,
                        function = COMMAND_GATEWAY_FUNCTION,
                    )
                )
            }
            .expectNextCount(0)
            .expectTimeout(Duration.ofMillis(100))
            .verify()
    }

    @Test
    fun waitingWhenNoMatchedContext() {
        val waitStrategy = WaitingFor.projected(contextName)
        waitStrategy.waiting()
            .test()
            .consumeSubscriptionWith {
                waitStrategy.next(
                    SimpleWaitSignal(
                        "commandId",
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
        waitStrategy.waiting()
            .test()
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }
}
