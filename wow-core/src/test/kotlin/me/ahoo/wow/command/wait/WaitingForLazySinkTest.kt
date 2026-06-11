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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.wait.stage.WaitingForStage
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WaitingForLazySinkTest {

    @Test
    fun `waitingLast emits target stage signal merged from buffered signals`() {
        val strategy = WaitingForStage.processed("wait-id")
        val sentSignal = testSignal(
            stage = CommandStage.SENT,
            waitCommandId = "wait-id",
            result = mapOf("sent" to 1),
            signalTime = 1
        )
        val processedSignal = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "wait-id",
            result = mapOf("processed" to 2),
            signalTime = 2
        )

        StepVerifier.create(strategy.waitingLast())
            .then {
                strategy.next(sentSignal)
                strategy.next(processedSignal)
            }
            .consumeNextWith {
                it.stage.assert().isEqualTo(CommandStage.PROCESSED)
                it.result.assert().isEqualTo(mapOf("sent" to 1, "processed" to 2))
            }
            .verifyComplete()
        strategy.terminated.assert().isTrue()
    }

    @Test
    fun `waitingLast subscribed after completion emits final signal immediately`() {
        val strategy = WaitingForStage.processed("wait-id")
        val processedSignal = testSignal(stage = CommandStage.PROCESSED, waitCommandId = "wait-id")
        strategy.next(processedSignal)
        strategy.terminated.assert().isTrue()

        StepVerifier.create(strategy.waitingLast())
            .consumeNextWith {
                it.stage.assert().isEqualTo(CommandStage.PROCESSED)
            }
            .verifyComplete()
    }

    @Test
    fun `waitingLast completes empty when completed without signals`() {
        val strategy = WaitingForStage.processed("wait-id")
        strategy.complete()

        StepVerifier.create(strategy.waitingLast())
            .verifyComplete()
    }

    @Test
    fun `waitingLast emits empty when subscriber materializes before empty completion`() {
        val strategy = WaitingForStage.processed("wait-id")

        StepVerifier.create(strategy.waitingLast())
            .then {
                strategy.complete()
            }
            .verifyComplete()
    }

    @Test
    fun `waitingLast swallows finally hook failure`() {
        val strategy = WaitingForStage.processed("wait-id")
        strategy.onFinally {
            error("boom")
        }

        StepVerifier.create(strategy.waitingLast())
            .then {
                strategy.complete()
            }
            .verifyComplete()
    }

    @Test
    fun `cancelled waitingLast marks strategy cancelled`() {
        val strategy = WaitingForStage.processed("wait-id")

        StepVerifier.create(strategy.waitingLast())
            .thenCancel()
            .verify()
        strategy.cancelled.assert().isTrue()
    }

    @Test
    fun `waitingLast returns single signal without copying`() {
        val strategy = WaitingForStage.processed("wait-id")
        val processedSignal = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "wait-id",
            result = mapOf("key" to "value")
        )

        StepVerifier.create(strategy.waitingLast())
            .then {
                strategy.next(processedSignal)
            }
            .consumeNextWith {
                it.assert().isSameAs(processedSignal)
            }
            .verifyComplete()
    }

    @Test
    fun `after processed wait falls back to last signal when manually completed before target stage`() {
        val strategy = WaitingForStage.projected("wait-id", TEST_CONTEXT)
        val sentSignal = testSignal(
            stage = CommandStage.SENT,
            waitCommandId = "wait-id",
            signalTime = 1
        )
        val processedSignal = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "wait-id",
            signalTime = 2
        )

        StepVerifier.create(strategy.waitingLast())
            .then {
                strategy.next(sentSignal)
                strategy.next(processedSignal)
                strategy.complete()
            }
            .expectNext(processedSignal)
            .verifyComplete()
    }

    @Test
    fun `waiting replays signals buffered before subscription`() {
        val strategy = WaitingForStage.processed("wait-id")
        val sentSignal = testSignal(stage = CommandStage.SENT, waitCommandId = "wait-id")
        strategy.next(sentSignal)

        StepVerifier.create(strategy.waiting())
            .expectNext(sentSignal)
            .then {
                strategy.next(testSignal(stage = CommandStage.PROCESSED, waitCommandId = "wait-id"))
            }
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun `waiting subscribed after completion replays all signals and completes`() {
        val strategy = WaitingForStage.processed("wait-id")
        val sentSignal = testSignal(stage = CommandStage.SENT, waitCommandId = "wait-id")
        val processedSignal = testSignal(stage = CommandStage.PROCESSED, waitCommandId = "wait-id")
        strategy.next(sentSignal)
        strategy.next(processedSignal)

        StepVerifier.create(strategy.waiting())
            .expectNext(sentSignal, processedSignal)
            .verifyComplete()
    }

    @Test
    fun `error before subscription propagates to waitingLast subscriber`() {
        val strategy = WaitingForStage.processed("wait-id")
        val error = IllegalStateException("boom")
        strategy.error(error)
        strategy.terminated.assert().isTrue()

        StepVerifier.create(strategy.waitingLast())
            .verifyErrorMatches { it === error }
    }

    @Test
    fun `error before subscription propagates to waiting subscriber after replay`() {
        val strategy = WaitingForStage.processed("wait-id")
        val sentSignal = testSignal(stage = CommandStage.SENT, waitCommandId = "wait-id")
        val error = IllegalStateException("boom")
        strategy.next(sentSignal)
        strategy.error(error)

        StepVerifier.create(strategy.waiting())
            .expectNext(sentSignal)
            .verifyErrorMatches { it === error }
    }

    @Test
    fun `error after subscription propagates to waitingLast subscriber`() {
        val strategy = WaitingForStage.processed("wait-id")
        val error = IllegalStateException("boom")

        StepVerifier.create(strategy.waitingLast())
            .then {
                strategy.error(error)
            }
            .verifyErrorMatches { it === error }
    }

    @Test
    fun `error after waiting subscription propagates to waiting subscriber`() {
        val strategy = WaitingForStage.processed("wait-id")
        val error = IllegalStateException("boom")

        StepVerifier.create(strategy.waiting())
            .then {
                strategy.error(error)
            }
            .verifyErrorMatches { it === error }
    }

    @Test
    fun `second subscriber is rejected`() {
        val strategy = WaitingForStage.processed("wait-id")
        strategy.waiting().subscribe()

        StepVerifier.create(strategy.waitingLast())
            .verifyError(IllegalStateException::class.java)

        StepVerifier.create(strategy.waiting())
            .verifyError(IllegalStateException::class.java)
    }

    @Test
    fun `failed prerequisite signal fail-fast completes strategy`() {
        val strategy = WaitingForStage.processed("wait-id")
        val failedSentSignal = testSignal(stage = CommandStage.SENT, waitCommandId = "wait-id", errorCode = "ERROR")

        StepVerifier.create(strategy.waitingLast())
            .then {
                strategy.next(failedSentSignal)
            }
            .consumeNextWith {
                it.errorCode.assert().isEqualTo("ERROR")
            }
            .verifyComplete()
        strategy.terminated.assert().isTrue()
    }

    @Test
    fun `signals after completion are ignored`() {
        val strategy = WaitingForStage.processed("wait-id")
        strategy.complete()
        strategy.next(testSignal(stage = CommandStage.PROCESSED, waitCommandId = "wait-id"))
        strategy.complete()
        strategy.error(IllegalStateException("ignored"))

        StepVerifier.create(strategy.waitingLast())
            .verifyComplete()
    }

    @Test
    fun `resolveLastSignal prefers greatest signal time`() {
        val strategy = WaitingForStage.processed("wait-id")
        val lateSignal = testSignal(stage = CommandStage.SENT, waitCommandId = "wait-id", signalTime = 10)
        val processedSignal = testSignal(stage = CommandStage.PROCESSED, waitCommandId = "wait-id", signalTime = 5)

        StepVerifier.create(strategy.waitingLast())
            .then {
                strategy.next(lateSignal)
                strategy.next(processedSignal)
            }
            .consumeNextWith {
                it.stage.assert().isEqualTo(CommandStage.SENT)
                it.signalTime.assert().isEqualTo(10)
            }
            .verifyComplete()
    }

    @Test
    fun `concurrent next signals are serialized without loss`() {
        val strategy = WaitingForStage.processed("wait-id")
        val received = CopyOnWriteArrayList<WaitSignal>()
        strategy.waiting().subscribe { received.add(it) }
        val threadCount = 8
        val signalsPerThread = 100
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val failures = AtomicInteger()
        repeat(threadCount) {
            Thread {
                try {
                    startLatch.await()
                    repeat(signalsPerThread) {
                        strategy.next(testSignal(stage = CommandStage.SENT, waitCommandId = "wait-id"))
                    }
                } catch (ignored: Throwable) {
                    failures.incrementAndGet()
                } finally {
                    doneLatch.countDown()
                }
            }.start()
        }
        startLatch.countDown()
        doneLatch.await(10, TimeUnit.SECONDS).assert().isTrue()
        failures.get().assert().isZero()
        received.size.assert().isEqualTo(threadCount * signalsPerThread)
        strategy.complete()
        strategy.terminated.assert().isTrue()
    }
}
