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

package me.ahoo.wow.infra.batch

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class BatchResultDispatcherTest {

    @Test
    fun `graceful shutdown dispatches detached signals on its isolated worker`() {
        val primaryEntered = CountDownLatch(1)
        val releasePrimary = CountDownLatch(1)
        val detachedEntered = CountDownLatch(1)
        val releaseDetached = CountDownLatch(1)
        val terminated = CountDownLatch(1)
        val detachedThread = AtomicReference<String>()
        val detachedInterrupted = AtomicBoolean()
        val shutdownExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "test-close-caller")
        }
        val dispatcher = BatchResultDispatcher(
            name = "soft-force",
            maxPendingItems = 1,
            onTerminated = terminated::countDown,
        )
        dispatcher.dispatch {
            primaryEntered.countDown()
            awaitIgnoringInterrupt(releasePrimary)
        }
        primaryEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
        dispatcher.shutdown()

        try {
            val shutdown = shutdownExecutor.submit {
                dispatcher.shutdown(
                    listOf {
                        detachedThread.set(Thread.currentThread().name)
                        detachedInterrupted.set(Thread.currentThread().isInterrupted)
                        detachedEntered.countDown()
                        awaitIgnoringInterrupt(releaseDetached)
                    },
                )
            }

            shutdown.get(250, TimeUnit.MILLISECONDS)
            detachedEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            detachedThread.get().assert()
                .startsWith("soft-force-batch-detached-result-")
            detachedInterrupted.get().assert().isFalse()

            releasePrimary.countDown()
            terminated.await(1, TimeUnit.SECONDS).assert().isTrue()
            releaseDetached.count.assert().isEqualTo(1)
        } finally {
            releasePrimary.countDown()
            releaseDetached.countDown()
            shutdownExecutor.shutdownNow()
        }
    }

    @Test
    fun `prepared fallback is registered before graceful shutdown and starts explicitly`() {
        val primaryEntered = CountDownLatch(1)
        val releasePrimary = CountDownLatch(1)
        val fallbackInvoked = CountDownLatch(1)
        val terminated = CountDownLatch(1)
        val dispatcher = BatchResultDispatcher(
            name = "prepared-fallback",
            maxPendingItems = 1,
            onTerminated = terminated::countDown,
        )
        dispatcher.dispatch {
            primaryEntered.countDown()
            releasePrimary.await()
        }
        primaryEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
        dispatcher.shutdown()

        val preparedSignal = dispatcher.prepareDispatch(fallbackInvoked::countDown)

        preparedSignal.accepted.assert().isTrue()
        fallbackInvoked.count.assert().isEqualTo(1)
        releasePrimary.countDown()
        terminated.await(100, TimeUnit.MILLISECONDS).assert().isFalse()

        preparedSignal.startFallbackIfNeeded()

        fallbackInvoked.await(1, TimeUnit.SECONDS).assert().isTrue()
        terminated.await(1, TimeUnit.SECONDS).assert().isTrue()
    }

    @Test
    fun `nested dispatch after shutdown should preserve callback context`() {
        val nestedDispatched = CountDownLatch(1)
        val terminated = CountDownLatch(1)
        lateinit var dispatcher: BatchResultDispatcher
        dispatcher = BatchResultDispatcher(
            name = "test",
            maxPendingItems = 2,
            onTerminated = terminated::countDown,
        )

        dispatcher.isDispatchingResult.assert().isFalse()
        dispatcher.dispatch {
            dispatcher.isDispatchingResult.assert().isTrue()
            dispatcher.shutdown()
            dispatcher.dispatch {
                dispatcher.isDispatchingResult.assert().isTrue()
                nestedDispatched.countDown()
            }
        }

        nestedDispatched.await(1, TimeUnit.SECONDS).assert().isTrue()
        terminated.await(1, TimeUnit.SECONDS).assert().isTrue()
    }

    @Test
    fun `callback context should remain local to its dispatcher`() {
        val callbackFinished = CountDownLatch(1)
        val firstTerminated = CountDownLatch(1)
        val secondTerminated = CountDownLatch(1)
        val observedContext = AtomicReference<Boolean>()
        val first = BatchResultDispatcher(
            name = "first",
            maxPendingItems = 1,
            onTerminated = firstTerminated::countDown,
        )
        val second = BatchResultDispatcher(
            name = "second",
            maxPendingItems = 1,
            onTerminated = secondTerminated::countDown,
        )

        first.dispatch {
            observedContext.set(second.isDispatchingResult)
            callbackFinished.countDown()
        }
        callbackFinished.await(1, TimeUnit.SECONDS).assert().isTrue()
        observedContext.get().assert().isFalse()

        first.shutdown()
        second.shutdown()
        firstTerminated.await(1, TimeUnit.SECONDS).assert().isTrue()
        secondTerminated.await(1, TimeUnit.SECONDS).assert().isTrue()
    }

    @Test
    fun `force shutdown should abandon owned work and dispatch detached signals`() {
        val workerCount = 4
        val blockingCallbacksEntered = CountDownLatch(workerCount)
        val blockingCallbacksInterrupted = CountDownLatch(workerCount)
        val releaseBlockingCallbacks = CountDownLatch(1)
        val detachedSignals = CountDownLatch(workerCount)
        val terminated = CountDownLatch(1)
        val abandonedInvocations = AtomicInteger()
        val dispatcher = BatchResultDispatcher(
            name = "test",
            maxPendingItems = workerCount * 2,
            onTerminated = terminated::countDown,
        )

        repeat(workerCount) {
            dispatcher.dispatch {
                blockingCallbacksEntered.countDown()
                try {
                    releaseBlockingCallbacks.await()
                } catch (_: InterruptedException) {
                    blockingCallbacksInterrupted.countDown()
                }
            }
        }
        blockingCallbacksEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
        repeat(workerCount) {
            dispatcher.dispatch(abandonedInvocations::incrementAndGet)
        }

        try {
            dispatcher.forceShutdown(
                List(workerCount) {
                    detachedSignals::countDown
                },
            )

            blockingCallbacksInterrupted.await(1, TimeUnit.SECONDS).assert().isTrue()
            detachedSignals.await(1, TimeUnit.SECONDS).assert().isTrue()
            terminated.await(1, TimeUnit.SECONDS).assert().isTrue()
            abandonedInvocations.get().assert().isZero()
        } finally {
            releaseBlockingCallbacks.countDown()
        }
    }

    @Test
    fun `force shutdown should bound migrated callback threads`() {
        val workerCount = 4
        val queuedCount = 64
        val primaryCallbacksEntered = CountDownLatch(workerCount)
        val releasePrimaryCallbacks = CountDownLatch(1)
        val forceWorkersEntered = CountDownLatch(workerCount)
        val tooManyForceWorkers = CountDownLatch(1)
        val releaseForceCallbacks = CountDownLatch(1)
        val forceCallbacksFinished = CountDownLatch(queuedCount)
        val terminated = CountDownLatch(1)
        val activeForceCallbacks = AtomicInteger()
        val dispatcher = BatchResultDispatcher(
            name = "bounded-force",
            maxPendingItems = queuedCount,
            onTerminated = terminated::countDown,
        )

        repeat(workerCount) {
            dispatcher.dispatch {
                primaryCallbacksEntered.countDown()
                awaitIgnoringInterrupt(releasePrimaryCallbacks)
            }
        }
        primaryCallbacksEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
        val forcedSignals = List(queuedCount) {
            {
                val active = activeForceCallbacks.incrementAndGet()
                if (active > workerCount) {
                    tooManyForceWorkers.countDown()
                }
                forceWorkersEntered.countDown()
                try {
                    awaitIgnoringInterrupt(releaseForceCallbacks)
                } finally {
                    activeForceCallbacks.decrementAndGet()
                    forceCallbacksFinished.countDown()
                }
            }
        }

        try {
            dispatcher.forceShutdown(forcedSignals)

            forceWorkersEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            tooManyForceWorkers.await(250, TimeUnit.MILLISECONDS).assert().isFalse()
            terminated.await(1, TimeUnit.SECONDS).assert().isTrue()
            Thread.getAllStackTraces().keys
                .count { thread ->
                    thread.name.startsWith("bounded-force-batch-detached-result-")
                }
                .assert()
                .isLessThanOrEqualTo(workerCount)
        } finally {
            releasePrimaryCallbacks.countDown()
            releaseForceCallbacks.countDown()
        }
        forceCallbacksFinished.await(1, TimeUnit.SECONDS).assert().isTrue()
    }

    @Test
    fun `force shutdown should retain every detached signal beyond worker capacity`() {
        val signalCount = 69
        val workersEntered = CountDownLatch(4)
        val releaseSignals = CountDownLatch(1)
        val signalsFinished = CountDownLatch(signalCount)
        val terminated = CountDownLatch(1)
        val dispatcher = BatchResultDispatcher(
            name = "retained-force",
            maxPendingItems = signalCount,
            onTerminated = terminated::countDown,
        )
        val forcedSignals = List(signalCount) {
            {
                workersEntered.countDown()
                try {
                    awaitIgnoringInterrupt(releaseSignals)
                } finally {
                    signalsFinished.countDown()
                }
            }
        }

        try {
            dispatcher.forceShutdown(forcedSignals)

            workersEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            terminated.await(1, TimeUnit.SECONDS).assert().isTrue()
        } finally {
            releaseSignals.countDown()
        }
        signalsFinished.await(1, TimeUnit.SECONDS).assert().isTrue()
    }

    @Test
    fun `blocked force callbacks should not starve another coordinator`() {
        val firstCallbacksEntered = CountDownLatch(4)
        val releaseFirstCallbacks = CountDownLatch(1)
        val secondCallbackEntered = CountDownLatch(1)
        val firstTerminated = CountDownLatch(1)
        val secondTerminated = CountDownLatch(1)
        val first = BatchResultDispatcher(
            name = "first-force",
            maxPendingItems = 4,
            onTerminated = firstTerminated::countDown,
        )
        val second = BatchResultDispatcher(
            name = "second-force",
            maxPendingItems = 1,
            onTerminated = secondTerminated::countDown,
        )

        try {
            first.forceShutdown(
                List(4) {
                    {
                        firstCallbacksEntered.countDown()
                        awaitIgnoringInterrupt(releaseFirstCallbacks)
                    }
                },
            )
            firstCallbacksEntered.await(1, TimeUnit.SECONDS).assert().isTrue()

            second.forceShutdown(listOf(secondCallbackEntered::countDown))

            secondCallbackEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            firstTerminated.await(1, TimeUnit.SECONDS).assert().isTrue()
            secondTerminated.await(1, TimeUnit.SECONDS).assert().isTrue()
        } finally {
            releaseFirstCallbacks.countDown()
        }
    }

    @Test
    fun `hard force interrupts soft callbacks before dispatching its batch`() {
        val primaryEntered = CountDownLatch(1)
        val releasePrimary = CountDownLatch(1)
        val softSignalsEntered = CountDownLatch(4)
        val softSignalsInterrupted = CountDownLatch(4)
        val releaseSoftSignal = CountDownLatch(1)
        val hardSignalEntered = CountDownLatch(1)
        val terminated = CountDownLatch(1)
        val dispatcher = BatchResultDispatcher(
            name = "soft-hard",
            maxPendingItems = 5,
            onTerminated = terminated::countDown,
        )
        dispatcher.dispatch {
            primaryEntered.countDown()
            awaitIgnoringInterrupt(releasePrimary)
        }
        primaryEntered.await(1, TimeUnit.SECONDS).assert().isTrue()

        try {
            dispatcher.shutdown(
                List(4) {
                    {
                        softSignalsEntered.countDown()
                        try {
                            releaseSoftSignal.await()
                        } catch (_: InterruptedException) {
                            softSignalsInterrupted.countDown()
                        }
                    }
                },
            )
            softSignalsEntered.await(1, TimeUnit.SECONDS).assert().isTrue()

            dispatcher.forceShutdown(listOf(hardSignalEntered::countDown))

            softSignalsInterrupted.await(1, TimeUnit.SECONDS).assert().isTrue()
            hardSignalEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            terminated.await(1, TimeUnit.SECONDS).assert().isTrue()
        } finally {
            releasePrimary.countDown()
            releaseSoftSignal.countDown()
        }
    }

    @Test
    fun `force after logical termination interrupts a running soft callback`() {
        val softSignalEntered = CountDownLatch(1)
        val softSignalInterrupted = CountDownLatch(1)
        val releaseSoftSignal = CountDownLatch(1)
        val terminated = CountDownLatch(1)
        val dispatcher = BatchResultDispatcher(
            name = "terminal-force",
            maxPendingItems = 1,
            onTerminated = terminated::countDown,
        )

        try {
            dispatcher.shutdown(
                listOf {
                    softSignalEntered.countDown()
                    try {
                        releaseSoftSignal.await()
                    } catch (_: InterruptedException) {
                        softSignalInterrupted.countDown()
                    }
                },
            )
            softSignalEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            terminated.await(1, TimeUnit.SECONDS).assert().isTrue()

            dispatcher.forceShutdown()

            softSignalInterrupted.await(1, TimeUnit.SECONDS).assert().isTrue()
        } finally {
            releaseSoftSignal.countDown()
        }
    }

    @Test
    fun `force shutdown termination should not wait for an uncooperative primary callback`() {
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val terminated = CountDownLatch(1)
        val dispatcher = BatchResultDispatcher(
            name = "uncooperative",
            maxPendingItems = 1,
            onTerminated = terminated::countDown,
        )
        dispatcher.dispatch {
            callbackEntered.countDown()
            awaitIgnoringInterrupt(releaseCallback)
        }
        callbackEntered.await(1, TimeUnit.SECONDS).assert().isTrue()

        try {
            dispatcher.forceShutdown()

            terminated.await(1, TimeUnit.SECONDS).assert().isTrue()
        } finally {
            releaseCallback.countDown()
        }
    }

    @Test
    fun `dispatch after termination should be rejected without invoking callback`() {
        val terminated = CountDownLatch(1)
        val callbackInvocations = AtomicInteger()
        val dispatcher = BatchResultDispatcher(
            name = "terminated",
            maxPendingItems = 1,
            onTerminated = terminated::countDown,
        )
        dispatcher.shutdown()
        terminated.await(1, TimeUnit.SECONDS).assert().isTrue()

        val accepted = dispatcher.dispatch(callbackInvocations::incrementAndGet)

        accepted.assert().isFalse()
        callbackInvocations.get().assert().isZero()
    }

    private fun awaitIgnoringInterrupt(latch: CountDownLatch) {
        while (true) {
            try {
                latch.await()
                return
            } catch (_: InterruptedException) {
                // Keep the callback parked so the test can measure the force
                // executor's actual concurrency bound.
            }
        }
    }
}
