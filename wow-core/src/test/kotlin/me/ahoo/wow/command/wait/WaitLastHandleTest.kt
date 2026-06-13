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
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class WaitLastHandleTest {
    @Test
    fun awaitFinalSignalWithoutStreamSink() {
        val handle = DefaultWaitLastHandle(
            plan = CommandWait.processed("wait-id"),
            reducer = DefaultWaitSignalReducer(),
            onTerminate = {},
        )

        StepVerifier.create(handle.await())
            .then {
                handle.next(testSignal(CommandStage.SENT, result = mapOf("sent" to true)))
                    .assert().isTrue()
                handle.next(testSignal(CommandStage.PROCESSED, result = mapOf("processed" to true)))
                    .assert().isTrue()
            }
            .assertNext { signal ->
                signal.stage.assert().isEqualTo(CommandStage.PROCESSED)
                signal.result["sent"].assert().isEqualTo(true)
                signal.result["processed"].assert().isEqualTo(true)
            }
            .verifyComplete()
    }

    @Test
    fun cancelCompletesAwait() {
        val handle = DefaultWaitLastHandle(
            plan = CommandWait.processed("wait-id"),
            reducer = DefaultWaitSignalReducer(),
            onTerminate = {},
        )

        StepVerifier.create(handle.await())
            .then { handle.cancel() }
            .verifyComplete()
    }

    @Test
    fun ignoredSignalReturnsFalse() {
        val handle = DefaultWaitLastHandle(
            plan = CommandWait.processed("wait-id"),
            reducer = DefaultWaitSignalReducer(),
            onTerminate = {},
        )

        handle.next(testSignal(CommandStage.PROJECTED))
            .assert().isFalse()

        StepVerifier.create(handle.await())
            .then { handle.cancel() }
            .verifyComplete()
    }

    @Test
    fun signalAfterTerminationReturnsFalse() {
        val handle = DefaultWaitLastHandle(
            plan = CommandWait.processed("wait-id"),
            reducer = DefaultWaitSignalReducer(),
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
        val handle = DefaultWaitLastHandle(
            plan = CommandWait.processed("wait-id"),
            reducer = DefaultWaitSignalReducer(),
            onTerminate = { terminated.incrementAndGet() },
        )

        StepVerifier.create(handle.await())
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
        val handle = DefaultWaitLastHandle(
            plan = CommandWait.processed("wait-id"),
            reducer = DefaultWaitSignalReducer(),
            onTerminate = { terminated.incrementAndGet() },
        )

        StepVerifier.create(handle.await())
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
        val handle = DefaultWaitLastHandle(
            plan = CommandWait.processed("wait-id"),
            reducer = DefaultWaitSignalReducer(),
            onTerminate = { terminated.incrementAndGet() },
        )

        StepVerifier.create(handle.await())
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
        val handle = DefaultWaitLastHandle(
            plan = CommandWait.processed("wait-id"),
            reducer = DefaultWaitSignalReducer(),
            onTerminate = { terminated.incrementAndGet() },
        )

        StepVerifier.create(handle.await())
            .then { handle.cancel() }
            .verifyComplete()

        handle.cancel()
        handle.next(testSignal(CommandStage.PROCESSED))
            .assert().isFalse()
        terminated.get().assert().isEqualTo(1)
    }

    @Test
    fun cancellingOneAwaitSubscriberKeepsHandleActiveForOthers() {
        val terminated = AtomicInteger()
        val handle = DefaultWaitLastHandle(
            plan = CommandWait.processed("wait-id"),
            reducer = DefaultWaitSignalReducer(),
            onTerminate = { terminated.incrementAndGet() },
        )

        StepVerifier.create(handle.await())
            .then {
                StepVerifier.create(handle.await())
                    .thenCancel()
                    .verify()
                terminated.get().assert().isEqualTo(0)
                handle.next(testSignal(CommandStage.PROCESSED))
                    .assert().isTrue()
            }
            .assertNext {
                it.stage.assert().isEqualTo(CommandStage.PROCESSED)
            }
            .verifyComplete()

        terminated.get().assert().isEqualTo(1)
    }

    @Test
    fun subscriberCancelTerminatesAndCompletesLateAwait() {
        val terminated = AtomicInteger()
        val handle = DefaultWaitLastHandle(
            plan = CommandWait.processed("wait-id"),
            reducer = DefaultWaitSignalReducer(),
            onTerminate = { terminated.incrementAndGet() },
        )

        StepVerifier.create(handle.await())
            .thenCancel()
            .verify()

        handle.next(testSignal(CommandStage.PROCESSED))
            .assert().isFalse()
        handle.cancel()
        terminated.get().assert().isEqualTo(1)

        StepVerifier.create(handle.await())
            .expectComplete()
            .verify(Duration.ofSeconds(1))
    }

    @Test
    fun reentrantCancelFromCancelCallbackTerminatesOnce() {
        val terminated = AtomicInteger()
        val handle = DefaultWaitLastHandle(
            plan = CommandWait.processed("wait-id"),
            reducer = DefaultWaitSignalReducer(),
            onTerminate = { terminated.incrementAndGet() },
        )

        StepVerifier.create(
            handle.await().doOnTerminate {
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
        val handle = DefaultWaitLastHandle(
            plan = CommandWait.processed("wait-id"),
            reducer = DefaultWaitSignalReducer(),
            onTerminate = { terminated.incrementAndGet() },
        )

        StepVerifier.create(
            handle.await().doOnError {
                handle.error(IllegalStateException("ignored"))
            },
        )
            .then { handle.error(error) }
            .expectErrorSatisfies { it.assert().isEqualTo(error) }
            .verify()

        terminated.get().assert().isEqualTo(1)
    }
}
