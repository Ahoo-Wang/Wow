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

package me.ahoo.wow.runtime.internal

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.reactivestreams.Subscription
import reactor.core.CoreSubscriber
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class RuntimeTerminationTest {

    @Test
    fun `dispatcher disposal invalidates and releases active permits`() {
        val dispatcher = newRuntimeTerminationDispatcher(
            threadNamePrefix = "wow-runtime-test-termination-dispose",
            threadCap = 1,
            queuedTaskCapacity = 0,
        )
        val invalidated = AtomicBoolean()
        val permit = checkNotNull(dispatcher.tryAcquire())
        permit.onDispatcherDisposed {
            invalidated.set(true)
        }

        dispatcher.dispose()

        invalidated.get().assert().isTrue()
        permit.isDisposed.assert().isTrue()
        dispatcher.tryAcquire().assert().isNull()
    }

    @Test
    fun `cancel releases its permit when upstream cancellation throws`() {
        val dispatcher = newRuntimeTerminationDispatcher(
            threadNamePrefix = "wow-runtime-test-termination-cancel-failure",
            threadCap = 1,
            queuedTaskCapacity = 0,
        )
        val cancellationFailure = IllegalStateException("cancel")
        val source = object : Mono<Void>() {
            override fun subscribe(actual: CoreSubscriber<in Void>) {
                actual.onSubscribe(
                    object : Subscription {
                        override fun request(n: Long) = Unit

                        override fun cancel() {
                            throw cancellationFailure
                        }
                    },
                )
            }
        }
        val subscription = source.publishRuntimeTermination(dispatcher).subscribe()

        try {
            runCatching(subscription::dispose).exceptionOrNull().assert()
                .isSameAs(cancellationFailure)
            checkNotNull(dispatcher.tryAcquire()).dispose()
        } finally {
            dispatcher.dispose()
        }
    }

    @Test
    fun `dispatcher disposal continues after a disposal hook fails`() {
        val dispatcher = newRuntimeTerminationDispatcher(
            threadNamePrefix = "wow-runtime-test-termination-hook-failure",
            threadCap = 2,
            queuedTaskCapacity = 0,
        )
        val first = checkNotNull(dispatcher.tryAcquire())
        val second = checkNotNull(dispatcher.tryAcquire())
        val secondHookInvoked = AtomicBoolean()
        first.onDispatcherDisposed {
            error("hook")
        }
        second.onDispatcherDisposed {
            secondHookInvoked.set(true)
        }

        dispatcher.dispose()

        first.isDisposed.assert().isTrue()
        second.isDisposed.assert().isTrue()
        secondHookInvoked.get().assert().isTrue()
        dispatcher.tryAcquire().assert().isNull()
    }

    @Test
    fun `cancelling a running observer retains admission until its callback returns`() {
        val dispatcher = newRuntimeTerminationDispatcher(
            threadNamePrefix = "wow-runtime-test-termination-running-cancel",
            threadCap = 1,
            queuedTaskCapacity = 0,
        )
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val callbackReturned = CountDownLatch(1)
        val permit = checkNotNull(dispatcher.tryAcquire())

        try {
            permit.dispatch(
                Runnable {
                    callbackEntered.countDown()
                    releaseCallback.await()
                    callbackReturned.countDown()
                },
            ).assert().isTrue()
            callbackEntered.await(1, TimeUnit.SECONDS).assert().isTrue()

            permit.dispose()

            dispatcher.tryAcquire().assert().isNull()
            releaseCallback.countDown()
            callbackReturned.await(1, TimeUnit.SECONDS).assert().isTrue()
            dispatcher.awaitPermit().dispose()
        } finally {
            releaseCallback.countDown()
            dispatcher.dispose()
        }
    }

    @Test
    fun `cancelling a queued observer releases admission for terminal replay`() {
        val dispatcher = newRuntimeTerminationDispatcher(
            threadNamePrefix = "wow-runtime-test-termination-cancel",
            threadCap = 1,
            queuedTaskCapacity = 1,
        )
        val terminalSink = Sinks.empty<Void>()
        val termination = terminalSink.asMono().publishRuntimeTermination(dispatcher)
        val runningObserverEntered = CountDownLatch(1)
        val releaseRunningObserver = CountDownLatch(1)
        val cancelledObserverInvoked = AtomicBoolean()
        val replayObserverCompleted = CountDownLatch(1)
        val replayObserverFailure = AtomicReference<Throwable?>()
        termination.subscribe(
            {},
            {},
            {
                runningObserverEntered.countDown()
                releaseRunningObserver.await()
            },
        )
        val queuedObserver = termination.subscribe(
            {},
            { cancelledObserverInvoked.set(true) },
            { cancelledObserverInvoked.set(true) },
        )

        try {
            terminalSink.tryEmitEmpty().orThrow()
            runningObserverEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            queuedObserver.dispose()

            termination.subscribe(
                {},
                replayObserverFailure::set,
                replayObserverCompleted::countDown,
            )
            replayObserverFailure.get().assert().isNull()

            releaseRunningObserver.countDown()
            replayObserverCompleted.await(1, TimeUnit.SECONDS).assert().isTrue()
            cancelledObserverInvoked.get().assert().isFalse()
        } finally {
            releaseRunningObserver.countDown()
            dispatcher.dispose()
        }
    }

    @Test
    fun `admitted terminal replay preserves the original failure`() {
        val dispatcher = newRuntimeTerminationDispatcher(
            threadNamePrefix = "wow-runtime-test-termination-error",
            threadCap = 1,
            queuedTaskCapacity = 0,
        )
        val terminalFailure = IllegalStateException("terminal")
        val terminalSink = Sinks.empty<Void>()
        terminalSink.tryEmitError(terminalFailure).orThrow()

        try {
            StepVerifier.create(
                terminalSink.asMono().publishRuntimeTermination(dispatcher),
            )
                .expectErrorSatisfies { error ->
                    error.assert().isSameAs(terminalFailure)
                }
                .verify(Duration.ofSeconds(1))
        } finally {
            dispatcher.dispose()
        }
    }

    private fun RuntimeTerminationDispatcher.awaitPermit(): RuntimeTerminationPermit {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (System.nanoTime() < deadline) {
            tryAcquire()?.let { return it }
            Thread.onSpinWait()
        }
        error("Runtime termination permit was not released.")
    }
}
