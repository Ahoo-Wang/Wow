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

import me.ahoo.wow.command.wait.SimpleWaitSignal.Companion.asWaitSignal
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.messaging.processor.ProcessorInfoData
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test
import java.time.Duration

internal class WaitingForTest {
    private val contextName = "WaitingForTest"

    @Test
    fun waiting() {
        val waitStrategy = WaitingFor.processed(contextName)
        val waitSignal = ProcessorInfoData(contextName, "processorName").asWaitSignal(
            commandId = "commandId",
            stage = CommandStage.PROCESSED,
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
    fun waitingForProjected() {
        val waitStrategy = WaitingFor.projected(contextName)
        val waitSignal = SimpleWaitSignal(
            commandId = "commandId",
            stage = CommandStage.PROJECTED,
            contextName = contextName,
            processorName = "",
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
            contextName = contextName,
            processorName = "",
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
    fun waitingWhenFailure() {
        val waitStrategy = WaitingFor.processed(contextName)
        val waitSignal = SimpleWaitSignal(
            commandId = "commandId",
            stage = CommandStage.PROCESSED,
            contextName = contextName,
            processorName = "",
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
                        contextName = contextName,
                        processorName = "",
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
                        CommandStage.PROCESSED,
                        contextName = "no-matched-context",
                        processorName = "",
                    )
                )
            }
            .expectNextCount(0)
            .expectTimeout(Duration.ofMillis(100))
            .verify()
    }
}
