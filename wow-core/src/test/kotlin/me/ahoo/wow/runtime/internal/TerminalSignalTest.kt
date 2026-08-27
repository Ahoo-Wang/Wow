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
import org.junit.jupiter.api.assertThrows
import org.reactivestreams.Subscription
import reactor.core.CoreSubscriber
import reactor.core.publisher.BaseSubscriber
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class TerminalSignalTest {

    @Test
    fun `trusted terminal callback preserves completion and failure`() {
        val completedWith = AtomicReference<Throwable?>(IllegalStateException("not-completed"))
        val terminalFailure = IllegalStateException("terminal")
        val failedWith = AtomicReference<Throwable?>()

        val completionSubscription = Mono.empty<Void>().subscribeTerminalSignal(
            ImmediateTerminalSignalDispatcher,
            completedWith::set,
        )
        val failureSubscription = Mono.error<Void>(terminalFailure).subscribeTerminalSignal(
            ImmediateTerminalSignalDispatcher,
            failedWith::set,
        )

        completedWith.get().assert().isNull()
        failedWith.get().assert().isSameAs(terminalFailure)
        completionSubscription.isDisposed.assert().isTrue()
        failureSubscription.isDisposed.assert().isTrue()
    }

    @Test
    fun `trusted terminal callback rejects when admission is saturated`() {
        val dispatcher = newTerminalSignalDispatcher(
            threadNamePrefix = "wow-terminal-signal-test-trusted-rejection",
            threadCap = 1,
            queuedTaskCapacity = 0,
        )
        val heldPermit = checkNotNull(dispatcher.tryAcquire())

        try {
            val error = assertThrows<RejectedExecutionException> {
                Mono.never<Void>().subscribeTerminalSignal(
                    dispatcher = dispatcher,
                    onTermination = {},
                )
            }

            error.message.assert().contains("dispatcher is saturated")
        } finally {
            heldPermit.dispose()
            dispatcher.dispose()
        }
    }

    @Test
    fun `terminal subscription failure releases reserved admission`() {
        val dispatcher = newTerminalSignalDispatcher(
            threadNamePrefix = "wow-terminal-signal-test-subscribe-failure",
            threadCap = 1,
            queuedTaskCapacity = 0,
        )
        val subscriptionFailure = IllegalStateException("subscribe")
        val source = object : Mono<Void>() {
            override fun subscribe(actual: CoreSubscriber<in Void>) {
                throw subscriptionFailure
            }
        }

        try {
            assertThrows<IllegalStateException> {
                source.subscribeTerminalSignal(
                    dispatcher = dispatcher,
                    onTermination = {},
                )
            }.assert().isSameAs(subscriptionFailure)
            checkNotNull(dispatcher.tryAcquire()).dispose()

            assertThrows<IllegalStateException> {
                source.publishTerminalSignal(dispatcher)
                    .subscribe(object : BaseSubscriber<Void>() {})
            }.assert().isSameAs(subscriptionFailure)
            checkNotNull(dispatcher.tryAcquire()).dispose()
        } finally {
            dispatcher.dispose()
        }
    }

    @Test
    fun `dispatcher disposal before upstream subscription cancels upstream`() {
        val dispatcher = newTerminalSignalDispatcher(
            threadNamePrefix = "wow-terminal-signal-test-dispose-before-upstream",
            threadCap = 1,
            queuedTaskCapacity = 0,
        )
        val upstreamCancelled = AtomicBoolean()
        val source = object : Mono<Void>() {
            override fun subscribe(actual: CoreSubscriber<in Void>) {
                dispatcher.dispose()
                actual.onSubscribe(recordingSubscription(upstreamCancelled))
            }
        }

        try {
            source.publishTerminalSignal(dispatcher).subscribe()
            upstreamCancelled.get().assert().isTrue()
        } finally {
            dispatcher.dispose()
        }
    }

    @Test
    fun `refused terminal dispatch cancels upstream without notifying downstream`() {
        val dispatchCount = AtomicInteger()
        val downstreamInvocationCount = AtomicInteger()
        val permitDisposed = AtomicBoolean()
        val upstreamCancelled = AtomicBoolean()
        val dispatcher = object : TerminalSignalDispatcher {
            override fun tryAcquire(): TerminalSignalPermit =
                object : TerminalSignalPermit {
                    override fun dispatch(action: Runnable): Boolean {
                        dispatchCount.incrementAndGet()
                        return false
                    }

                    override fun onDispatcherDisposed(action: Runnable) = Unit

                    override fun dispose() {
                        permitDisposed.set(true)
                    }

                    override fun isDisposed(): Boolean = permitDisposed.get()
                }

            override fun dispose() = Unit

            override fun isDisposed(): Boolean = false
        }
        val source = object : Mono<Void>() {
            override fun subscribe(actual: CoreSubscriber<in Void>) {
                actual.onSubscribe(recordingSubscription(upstreamCancelled))
                actual.onComplete()
            }
        }

        val subscription = source.publishTerminalSignal(dispatcher).subscribe(
            { downstreamInvocationCount.incrementAndGet() },
            { downstreamInvocationCount.incrementAndGet() },
            { downstreamInvocationCount.incrementAndGet() },
        )
        try {
            dispatchCount.get().assert().isOne()
            downstreamInvocationCount.get().assert().isZero()
            permitDisposed.get().assert().isTrue()
            upstreamCancelled.get().assert().isTrue()
        } finally {
            subscription.dispose()
        }
    }

    @Test
    fun `dispatcher disposal is idempotent and late disposal hooks run immediately`() {
        val dispatcher = newTerminalSignalDispatcher(
            threadNamePrefix = "wow-terminal-signal-test-late-hook",
            threadCap = 1,
            queuedTaskCapacity = 0,
        )
        val permit = checkNotNull(dispatcher.tryAcquire())
        val lateHookInvoked = AtomicBoolean()

        dispatcher.dispose()
        dispatcher.dispose()
        permit.onDispatcherDisposed {
            lateHookInvoked.set(true)
        }

        lateHookInvoked.get().assert().isTrue()
        permit.dispatch {}.assert().isFalse()
        permit.dispose()
        permit.isDisposed.assert().isTrue()
    }

    @Test
    fun `different terminal signals share bounded dispatcher admission`() {
        val dispatcher = newTerminalSignalDispatcher(
            threadNamePrefix = "wow-terminal-signal-test-shared-capacity",
            threadCap = 1,
            queuedTaskCapacity = 0,
        )
        val firstSignal = Sinks.empty<Void>()
        val firstSubscription = firstSignal.asMono()
            .publishTerminalSignal(dispatcher)
            .subscribe()

        try {
            StepVerifier.create(
                Sinks.empty<Void>().asMono().publishTerminalSignal(dispatcher),
            )
                .expectError(RejectedExecutionException::class.java)
                .verify(Duration.ofSeconds(1))
        } finally {
            firstSubscription.dispose()
            dispatcher.dispose()
        }
    }

    @Test
    fun `dispatcher disposal invalidates and releases active permits`() {
        val dispatcher = newTerminalSignalDispatcher(
            threadNamePrefix = "wow-terminal-signal-test-dispose",
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
        val dispatcher = newTerminalSignalDispatcher(
            threadNamePrefix = "wow-terminal-signal-test-cancel-failure",
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
        val subscription = source.publishTerminalSignal(dispatcher).subscribe()

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
        val dispatcher = newTerminalSignalDispatcher(
            threadNamePrefix = "wow-terminal-signal-test-hook-failure",
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
        val dispatcher = newTerminalSignalDispatcher(
            threadNamePrefix = "wow-terminal-signal-test-running-cancel",
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
        val dispatcher = newTerminalSignalDispatcher(
            threadNamePrefix = "wow-terminal-signal-test-cancel",
            threadCap = 1,
            queuedTaskCapacity = 1,
        )
        val terminalSink = Sinks.empty<Void>()
        val termination = terminalSink.asMono().publishTerminalSignal(dispatcher)
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
        val dispatcher = newTerminalSignalDispatcher(
            threadNamePrefix = "wow-terminal-signal-test-error",
            threadCap = 1,
            queuedTaskCapacity = 0,
        )
        val terminalFailure = IllegalStateException("terminal")
        val terminalSink = Sinks.empty<Void>()
        terminalSink.tryEmitError(terminalFailure).orThrow()

        try {
            StepVerifier.create(
                terminalSink.asMono().publishTerminalSignal(dispatcher),
            )
                .expectErrorSatisfies { error ->
                    error.assert().isSameAs(terminalFailure)
                }
                .verify(Duration.ofSeconds(1))
        } finally {
            dispatcher.dispose()
        }
    }

    private fun TerminalSignalDispatcher.awaitPermit(): TerminalSignalPermit {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (System.nanoTime() < deadline) {
            tryAcquire()?.let { return it }
            Thread.onSpinWait()
        }
        error("Terminal signal permit was not released.")
    }

    private fun recordingSubscription(cancelled: AtomicBoolean): Subscription =
        object : Subscription {
            override fun request(n: Long) = Unit

            override fun cancel() {
                cancelled.set(true)
            }
        }
}
