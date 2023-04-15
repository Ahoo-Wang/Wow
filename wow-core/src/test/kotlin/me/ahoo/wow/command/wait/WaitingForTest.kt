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

import me.ahoo.wow.api.exception.ErrorCodes
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test
import java.time.Duration

internal class WaitingForTest {
    @Test
    fun waiting() {
        val waitStrategy = WaitingFor.processed()
        val waitSignal = SimpleWaitSignal("commandId", CommandStage.PROCESSED)
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
        val waitStrategy = WaitingFor.projected()
        val waitSignal = SimpleWaitSignal("commandId", CommandStage.PROJECTED, isLastProjection = true)
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
        val waitStrategy = WaitingFor.projected()
        val waitSignal = SimpleWaitSignal("commandId", CommandStage.PROJECTED, isLastProjection = false)
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
        val waitStrategy = WaitingFor.processed()
        val waitSignal = SimpleWaitSignal(
            "commandId",
            CommandStage.PROCESSED,
            isLastProjection = true,
            ErrorCodes.ILLEGAL_ARGUMENT,
            "",
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
    fun waitingWhenNoMatchedSignal() {
        val waitStrategy = WaitingFor.projected()

        waitStrategy.waiting()
            .test()
            .consumeSubscriptionWith {
                waitStrategy.next(SimpleWaitSignal("commandId", CommandStage.PROCESSED))
            }
            .expectNextCount(0)
            .expectTimeout(Duration.ofMillis(100))
            .verify()
    }
}
