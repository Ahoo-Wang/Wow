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
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier
import java.util.concurrent.atomic.AtomicInteger

class WaitStreamHandleTest {
    @Test
    fun streamSignalsInOrderAndCompleteAtTarget() {
        val handle = DefaultWaitStreamHandle(
            plan = CommandWait.processed("wait-id"),
            onTerminate = {},
        )

        StepVerifier.create(handle.stream())
            .then {
                handle.next(testSignal(CommandStage.SENT, signalTime = 1))
                    .assert().isTrue()
                handle.next(testSignal(CommandStage.PROCESSED, signalTime = 2))
                    .assert().isTrue()
            }
            .assertNext { it.stage.assert().isEqualTo(CommandStage.SENT) }
            .assertNext { it.stage.assert().isEqualTo(CommandStage.PROCESSED) }
            .verifyComplete()
    }

    @Test
    fun bufferSignalsForFirstLateSubscriber() {
        val handle = DefaultWaitStreamHandle(
            plan = CommandWait.processed("wait-id"),
            onTerminate = {},
        )
        handle.next(testSignal(CommandStage.SENT, signalTime = 1))
            .assert().isTrue()
        handle.next(testSignal(CommandStage.PROCESSED, signalTime = 2))
            .assert().isTrue()

        StepVerifier.create(handle.stream())
            .assertNext { it.stage.assert().isEqualTo(CommandStage.SENT) }
            .assertNext { it.stage.assert().isEqualTo(CommandStage.PROCESSED) }
            .verifyComplete()
    }

    @Test
    fun bufferSignalsBeyondConfiguredQueueLinkSizeBeforeSubscriber() {
        val terminated = AtomicInteger()
        val handle = DefaultWaitStreamHandle(
            plan = CommandWait.processed("wait-id"),
            onTerminate = { terminated.incrementAndGet() },
            queueLinkSize = 1,
        )

        handle.next(testSignal(CommandStage.SENT, signalTime = 1))
            .assert().isTrue()
        handle.next(testSignal(CommandStage.PROCESSED, signalTime = 2))
            .assert().isTrue()

        terminated.get().assert().isEqualTo(1)
        StepVerifier.create(handle.stream())
            .assertNext { it.stage.assert().isEqualTo(CommandStage.SENT) }
            .assertNext { it.stage.assert().isEqualTo(CommandStage.PROCESSED) }
            .verifyComplete()
    }

    @Test
    fun cancelCompletesStream() {
        val handle = DefaultWaitStreamHandle(
            plan = CommandWait.processed("wait-id"),
            onTerminate = {},
        )

        StepVerifier.create(handle.stream())
            .then { handle.cancel() }
            .verifyComplete()
    }

    @Test
    fun ignoredSignalReturnsFalse() {
        val handle = DefaultWaitStreamHandle(
            plan = CommandWait.processed("wait-id"),
            onTerminate = {},
        )

        handle.next(testSignal(CommandStage.PROJECTED))
            .assert().isFalse()

        StepVerifier.create(handle.stream())
            .then { handle.cancel() }
            .verifyComplete()
    }

    @Test
    fun signalAfterTerminationReturnsFalse() {
        val handle = DefaultWaitStreamHandle(
            plan = CommandWait.processed("wait-id"),
            onTerminate = {},
        )

        handle.next(testSignal(CommandStage.PROCESSED))
            .assert().isTrue()
        handle.next(testSignal(CommandStage.SENT))
            .assert().isFalse()
    }

    @Test
    fun onTerminateRunsOnceWhenCompleted() {
        val terminated = AtomicInteger()
        val handle = DefaultWaitStreamHandle(
            plan = CommandWait.processed("wait-id"),
            onTerminate = { terminated.incrementAndGet() },
        )

        StepVerifier.create(handle.stream())
            .then { handle.next(testSignal(CommandStage.PROCESSED)) }
            .expectNextCount(1)
            .verifyComplete()

        handle.cancel()
        handle.error(IllegalStateException("ignored"))
        terminated.get().assert().isEqualTo(1)
    }

    @Test
    fun reentrantCancelFromFinalSignalDeliveryTerminatesOnce() {
        val terminated = AtomicInteger()
        val handle = DefaultWaitStreamHandle(
            plan = CommandWait.processed("wait-id"),
            onTerminate = { terminated.incrementAndGet() },
        )

        StepVerifier.create(handle.stream())
            .then { handle.next(testSignal(CommandStage.PROCESSED)) }
            .assertNext {
                handle.cancel()
                it.stage.assert().isEqualTo(CommandStage.PROCESSED)
            }
            .verifyComplete()

        terminated.get().assert().isEqualTo(1)
        handle.next(testSignal(CommandStage.PROCESSED))
            .assert().isFalse()
    }

    @Test
    fun onTerminateRunsOnceWhenErrored() {
        val terminated = AtomicInteger()
        val error = IllegalStateException("boom")
        val handle = DefaultWaitStreamHandle(
            plan = CommandWait.processed("wait-id"),
            onTerminate = { terminated.incrementAndGet() },
        )

        StepVerifier.create(handle.stream())
            .then { handle.error(error) }
            .expectErrorSatisfies { it.assert().isEqualTo(error) }
            .verify()

        handle.cancel()
        handle.next(testSignal(CommandStage.PROCESSED))
            .assert().isFalse()
        terminated.get().assert().isEqualTo(1)
    }

    @Test
    fun onTerminateRunsOnceWhenCancelled() {
        val terminated = AtomicInteger()
        val handle = DefaultWaitStreamHandle(
            plan = CommandWait.processed("wait-id"),
            onTerminate = { terminated.incrementAndGet() },
        )

        StepVerifier.create(handle.stream())
            .then { handle.cancel() }
            .verifyComplete()

        handle.cancel()
        handle.next(testSignal(CommandStage.PROCESSED))
            .assert().isFalse()
        terminated.get().assert().isEqualTo(1)
    }

    @Test
    fun subscriberCancelTerminatesUnicastStream() {
        val terminated = AtomicInteger()
        val handle = DefaultWaitStreamHandle(
            plan = CommandWait.processed("wait-id"),
            onTerminate = { terminated.incrementAndGet() },
        )

        StepVerifier.create(handle.stream())
            .thenCancel()
            .verify()

        terminated.get().assert().isEqualTo(1)
        handle.next(testSignal(CommandStage.PROCESSED))
            .assert().isFalse()

        StepVerifier.create(handle.stream())
            .expectErrorSatisfies {
                it.assert().isInstanceOf(IllegalStateException::class.java)
            }
            .verify()
    }

    @Test
    fun secondStreamSubscriberFailsAfterComplete() {
        val handle = DefaultWaitStreamHandle(
            plan = CommandWait.processed("wait-id"),
            onTerminate = {},
        )

        handle.next(testSignal(CommandStage.PROCESSED))
            .assert().isTrue()

        StepVerifier.create(handle.stream())
            .assertNext { it.stage.assert().isEqualTo(CommandStage.PROCESSED) }
            .verifyComplete()

        StepVerifier.create(handle.stream())
            .expectErrorSatisfies {
                it.assert().isInstanceOf(IllegalStateException::class.java)
            }
            .verify()
    }

    @Test
    fun reentrantCancelFromCancelCallbackTerminatesOnce() {
        val terminated = AtomicInteger()
        val handle = DefaultWaitStreamHandle(
            plan = CommandWait.processed("wait-id"),
            onTerminate = { terminated.incrementAndGet() },
        )

        StepVerifier.create(
            handle.stream().doOnComplete {
                handle.cancel()
            },
        )
            .then { handle.cancel() }
            .verifyComplete()

        terminated.get().assert().isEqualTo(1)
    }

    @Test
    fun reentrantErrorFromErrorCallbackTerminatesOnce() {
        val terminated = AtomicInteger()
        val error = IllegalStateException("boom")
        val handle = DefaultWaitStreamHandle(
            plan = CommandWait.processed("wait-id"),
            onTerminate = { terminated.incrementAndGet() },
        )

        StepVerifier.create(
            handle.stream().doOnError {
                handle.error(IllegalStateException("ignored"))
            },
        )
            .then { handle.error(error) }
            .expectErrorSatisfies { it.assert().isEqualTo(error) }
            .verify()

        terminated.get().assert().isEqualTo(1)
    }
}
