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

package me.ahoo.wow.command.wait.stage

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.command.wait.COMMAND_WAIT_ENDPOINT
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.SimpleWaitSignal
import me.ahoo.wow.command.wait.stage.WaitingForStage.Companion.extractWaitingForStage
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test
import java.time.Duration

internal class WaitingForStageTest {
    private val contextName = "WaitingForTest"
    private val functionInfo = FunctionInfoData(
        functionKind = FunctionKind.COMMAND,
        contextName = "wow",
        processorName = "WaitingForStageTest",
        name = "Send"
    )

    @Test
    fun processedInject() {
        val waitStrategy = WaitingForStage.projected(generateGlobalId(), "content", "processor", "function")
        val header = DefaultHeader()
        waitStrategy.propagate("endpoint", header)
        val waitStrategyInfo = header.extractWaitingForStage()
        waitStrategyInfo.assert().isNotNull()
        waitStrategyInfo!!.stage.assert().isEqualTo(CommandStage.PROJECTED)
        waitStrategyInfo.function?.contextName.assert().isEqualTo("content")
        waitStrategyInfo.function?.processorName.assert().isEqualTo("processor")
        waitStrategyInfo.function?.name.assert().isEqualTo("function")
    }

    @Test
    fun extractWaitingForStageIfNotExistEndpoint() {
        val header = DefaultHeader()
        header.extractWaitingForStage().assert().isNull()
    }

    @Test
    fun extractWaitingForStageIfNotExistStage() {
        val header = DefaultHeader()
        header[COMMAND_WAIT_ENDPOINT] = "endpoint"
        header.extractWaitingForStage().assert().isNull()
    }

    @Test
    fun processed() {
        val waitStrategy = WaitingForStage.stage(generateGlobalId(), "PROCESSED", contextName)
        waitStrategy.cancelled.assert().isEqualTo(false)
        waitStrategy.terminated.assert().isEqualTo(false)
        val waitSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = waitStrategy.waitCommandId,
            commandId = generateGlobalId(),
            aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
            stage = CommandStage.PROCESSED,
            function = functionInfo,
        )
        waitStrategy.waitingLast()
            .test()
            .consumeSubscriptionWith {
                waitStrategy.next(waitSignal)
                waitStrategy.terminated.assert().isEqualTo(true)
            }
            .expectNext(waitSignal)
            .verifyComplete()
    }

    @Test
    fun waitingLastEmpty() {
        val waitStrategy = WaitingForStage.stage(generateGlobalId(), "PROCESSED", contextName)
        waitStrategy.cancelled.assert().isEqualTo(false)
        waitStrategy.terminated.assert().isEqualTo(false)
        waitStrategy.waitingLast()
            .test()
            .expectSubscription()
            .then { waitStrategy.complete() }
            .expectComplete()
            .verify()
    }

    @Test
    fun processedIfSnapshot() {
        val waitStrategy = WaitingForStage.stage(generateGlobalId(), "PROCESSED", contextName)
        val waitSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = waitStrategy.waitCommandId,
            commandId = generateGlobalId(),
            aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
            stage = CommandStage.SNAPSHOT,
            function = functionInfo,
        )
        waitStrategy.waitingLast()
            .test()
            .consumeSubscriptionWith {
                waitStrategy.next(waitSignal)
            }
            .verifyTimeout(Duration.ofMillis(100))
        waitStrategy.terminated.assert().isEqualTo(false)
        waitStrategy.cancelled.assert().isEqualTo(true)
    }

    @Test
    fun snapshot() {
        val waitStrategy = WaitingForStage.stage(generateGlobalId(), "SNAPSHOT", contextName)
        val processedSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = waitStrategy.waitCommandId,
            commandId = generateGlobalId(),
            aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
            stage = CommandStage.PROCESSED,
            function = functionInfo,
        )
        val waitSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = waitStrategy.waitCommandId,
            commandId = generateGlobalId(),
            aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
            stage = CommandStage.SNAPSHOT,
            function = functionInfo,
        )
        waitStrategy.waitingLast()
            .test()
            .consumeSubscriptionWith {
                waitStrategy.next(processedSignal)
                waitStrategy.next(waitSignal)
            }
            .consumeNextWith {
                it.commandId.assert().isEqualTo(waitSignal.commandId)
            }
            .verifyComplete()
    }

    @Test
    fun snapshotFailFast() {
        val waitStrategy = WaitingForStage.snapshot(generateGlobalId())
        val waitSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = waitStrategy.waitCommandId,
            commandId = generateGlobalId(),
            aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
            stage = CommandStage.PROCESSED,
            function = functionInfo,
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
        val waitStrategy = WaitingForStage.stage(generateGlobalId(), "PROJECTED", contextName)
        val processedSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = waitStrategy.waitCommandId,
            commandId = generateGlobalId(),
            aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
            stage = CommandStage.PROCESSED,
            function = functionInfo,
        )
        val waitSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = waitStrategy.waitCommandId,
            commandId = generateGlobalId(),
            aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
            stage = CommandStage.PROJECTED,
            function = functionInfo.copy(contextName = contextName),
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
        val waitStrategy = WaitingForStage.projected(generateGlobalId(), contextName, "processor")
        val processedSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = waitStrategy.waitCommandId,
            commandId = generateGlobalId(),
            aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
            stage = CommandStage.PROCESSED,
            function = functionInfo,
        )
        val waitSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = waitStrategy.waitCommandId,
            commandId = generateGlobalId(),
            aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
            stage = CommandStage.PROJECTED,
            function = functionInfo.copy(contextName = contextName, processorName = "processor"),
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
    fun waitingForProjectedProcessorNotEq() {
        val waitStrategy = WaitingForStage.projected(generateGlobalId(), "processor")
        val processedSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = waitStrategy.waitCommandId,
            commandId = generateGlobalId(),
            aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
            stage = CommandStage.PROCESSED,
            function = functionInfo,
        )
        val waitSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = waitStrategy.waitCommandId,
            commandId = generateGlobalId(),
            aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
            stage = CommandStage.PROJECTED,
            function = functionInfo.copy(contextName = contextName, processorName = "hi"),
            isLastProjection = true
        )
        waitStrategy.waitingLast()
            .test()
            .consumeSubscriptionWith {
                waitStrategy.next(processedSignal)
                waitStrategy.next(waitSignal)
            }
            .verifyTimeout(Duration.ofMillis(500))
    }

    @Test
    fun waitingForProjectedFunction() {
        val waitStrategy = WaitingForStage.projected(generateGlobalId(), contextName, "processor", "function")
        val processedSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = waitStrategy.waitCommandId,
            commandId = generateGlobalId(),
            aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
            stage = CommandStage.PROCESSED,
            function = functionInfo,
        )
        val waitSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = waitStrategy.waitCommandId,
            commandId = generateGlobalId(),
            aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
            stage = CommandStage.PROJECTED,
            function = functionInfo.copy(
                contextName = contextName,
                processorName = "processor",
                name = "function"
            ),
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
        val waitStrategy = WaitingForStage.projected(generateGlobalId(), contextName)
        val processedSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = waitStrategy.waitCommandId,
            commandId = generateGlobalId(),
            aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
            stage = CommandStage.PROCESSED,
            function = functionInfo,
        )
        val waitSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = waitStrategy.waitCommandId,
            commandId = generateGlobalId(),
            aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
            stage = CommandStage.PROJECTED,
            function = functionInfo.copy(contextName = contextName),
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
        val waitStrategy = WaitingForStage.stage(generateGlobalId(), "EVENT_HANDLED", contextName)
        val processedSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = waitStrategy.waitCommandId,
            commandId = generateGlobalId(),
            aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
            stage = CommandStage.PROCESSED,
            function = functionInfo,
        )
        val waitSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = waitStrategy.waitCommandId,
            commandId = generateGlobalId(),
            aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
            stage = CommandStage.EVENT_HANDLED,
            function = functionInfo.copy(contextName = contextName),
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
        val waitStrategy = WaitingForStage.stage(generateGlobalId(), "SAGA_HANDLED", contextName)
        val processedSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = waitStrategy.waitCommandId,
            commandId = generateGlobalId(),
            aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
            stage = CommandStage.PROCESSED,
            function = functionInfo,
        )
        val waitSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = waitStrategy.waitCommandId,
            commandId = generateGlobalId(),
            aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
            stage = CommandStage.SAGA_HANDLED,
            function = functionInfo.copy(contextName = contextName)
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
        val waitStrategy = WaitingForStage.projected(generateGlobalId(), contextName)
        waitStrategy.waitingLast()
            .test()
            .consumeSubscriptionWith {
                waitStrategy.next(
                    SimpleWaitSignal(
                        id = generateGlobalId(),
                        waitCommandId = waitStrategy.waitCommandId,
                        commandId = generateGlobalId(),
                        aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
                        stage = CommandStage.PROJECTED,
                        function = functionInfo,
                    )
                )
            }
            .expectNextCount(0)
            .expectTimeout(Duration.ofMillis(100))
            .verify()
    }

    @Test
    fun waitingWhenError() {
        val waitStrategy = WaitingForStage.projected(generateGlobalId(), contextName)
        waitStrategy.error(IllegalArgumentException())
        waitStrategy.waitingLast()
            .test()
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun waitingForSent() {
        val waitStrategy = WaitingForStage.stage(generateGlobalId(), "SENT", contextName)
        waitStrategy.error(IllegalArgumentException())
        waitStrategy.waitingLast()
            .test()
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun doFinallyError() {
        val waitStrategy = WaitingForStage.projected(generateGlobalId(), contextName)
        waitStrategy.onFinally {
            throw IllegalArgumentException()
        }
        waitStrategy.error(IllegalArgumentException())
        waitStrategy.waitingLast()
            .test()
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun doFinallySetTwice() {
        val waitStrategy = WaitingForStage.projected(generateGlobalId(), contextName)
        waitStrategy.onFinally {
        }
        Assertions.assertThrows(IllegalStateException::class.java) {
            waitStrategy.onFinally {
            }
        }
    }
}
