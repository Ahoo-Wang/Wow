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

package me.ahoo.wow.runtime

import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.internal.DefaultRuntimeExecutionResources
import me.ahoo.wow.runtime.internal.ImmediateTerminalSignalDispatcher
import me.ahoo.wow.runtime.internal.RuntimeExecutionResources
import me.ahoo.wow.runtime.internal.TerminalSignalDispatcher
import me.ahoo.wow.runtime.internal.newTerminalSignalDispatcher
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.reactivestreams.Subscription
import reactor.core.Disposable
import reactor.core.Exceptions
import reactor.core.publisher.BaseSubscriber
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Suppress("LargeClass")
class WowRuntimeTest {

    @Test
    fun `close uses the runtime-owned terminal boundary`() {
        val terminationDispatcher = newTerminalSignalDispatcher(
            "wow-runtime-test-rejected-public-terminal",
        ).also(TerminalSignalDispatcher::dispose)
        val runtime = WowRuntime(
            components = emptyList(),
            shutdownTimeout = Duration.ofSeconds(60),
            shutdownQuietPeriod = Duration.ZERO,
            executionResources = immediateExecutionResources(terminationDispatcher),
        )

        assertTimeoutPreemptively(Duration.ofSeconds(1)) {
            runtime.close()
        }
        StepVerifier.create(runtime.terminationSignal)
            .expectError(RejectedExecutionException::class.java)
            .verify()
    }

    @Test
    fun `timed stop uses the runtime-owned terminal boundary`() {
        val terminationDispatcher = newTerminalSignalDispatcher(
            "wow-runtime-test-rejected-timed-stop-terminal",
        ).also(TerminalSignalDispatcher::dispose)
        val runtime = WowRuntime(
            components = emptyList(),
            shutdownTimeout = Duration.ofSeconds(60),
            shutdownQuietPeriod = Duration.ZERO,
            executionResources = immediateExecutionResources(terminationDispatcher),
        )

        assertTimeoutPreemptively(Duration.ofSeconds(1)) {
            runtime.stop(Duration.ofSeconds(1))
        }
        StepVerifier.create(runtime.terminationSignal)
            .expectError(RejectedExecutionException::class.java)
            .verify()
    }

    @Test
    fun `synchronous stop waits for the runtime deadline owner`() {
        val stopSubscribed = CountDownLatch(1)
        val component = RecordingLifecycle(
            name = "component",
            calls = mutableListOf(),
            stopGate = Sinks.empty(),
            onStop = stopSubscribed::countDown,
        )
        val deadlineScheduler = ControllableDeadlineScheduler()
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofMillis(20),
            shutdownQuietPeriod = Duration.ZERO,
        ).also {
            it.shutdownDeadlineScheduler = deadlineScheduler
        }
        runtime.start().block()
        val executor = Executors.newSingleThreadExecutor()

        try {
            val stopFailure = executor.submit<Throwable?> {
                runCatching { runtime.stop() }.exceptionOrNull()
            }
            stopSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()

            assertThrows<TimeoutException> {
                stopFailure.get(100, TimeUnit.MILLISECONDS)
            }

            deadlineScheduler.runScheduled()
            Exceptions.unwrap(checkNotNull(stopFailure.get(1, TimeUnit.SECONDS)))
                .assert()
                .isInstanceOf(TimeoutException::class.java)
        } finally {
            runtime.forceStop()
            executor.shutdownNow()
        }
    }

    @Test
    fun `inline deadline force wins before graceful subscription attachment`() {
        val calls = mutableListOf<String>()
        val component = RecordingLifecycle("component", calls)
        val deadlineScheduler = InlineDeadlineScheduler()
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        ).also {
            it.shutdownDeadlineScheduler = deadlineScheduler
        }
        runtime.start().block()

        StepVerifier.create(runtime.stopGracefully())
            .expectError(TimeoutException::class.java)
            .verify()

        calls.assert().containsExactly(
            "prepare:component",
            "start:component",
            "force:component",
        )
        deadlineScheduler.deadlineTaskDisposed.get().assert().isTrue()
    }

    @Test
    fun `duration overflow fails before runtime construction`() {
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext) = Mono.empty<Void>()

            override fun start() = Unit

            override fun stopGracefully(): Mono<Void> = Mono.empty()

            override fun forceStop() = Unit
        }

        assertThrows<IllegalArgumentException> {
            WowRuntime(
                components = listOf(component),
                shutdownTimeout = Duration.ofSeconds(Long.MAX_VALUE),
                shutdownQuietPeriod = Duration.ZERO,
            )
        }
            .message
            .assert()
            .contains("shutdownTimeout must fit in nanoseconds")
    }

    @Test
    fun `runtime starts no component until every asynchronous preparation completes`() {
        val calls = CopyOnWriteArrayList<String>()
        val firstReady = Sinks.empty<Void>()
        val secondReady = Sinks.empty<Void>()
        val firstPrepared = CountDownLatch(1)
        val secondPrepared = CountDownLatch(1)

        fun component(
            name: String,
            readiness: Sinks.Empty<Void>,
            prepared: CountDownLatch,
        ): RuntimeComponent =
            object : RuntimeComponent {
                override fun prepare(runtimeContext: RuntimeContext): Mono<Void> =
                    Mono.defer {
                        calls += "prepare:$name"
                        prepared.countDown()
                        readiness.asMono()
                    }

                override fun start() {
                    calls += "start:$name"
                }

                override fun stopGracefully(): Mono<Void> = Mono.empty()

                override fun forceStop() {
                    readiness.tryEmitError(IllegalStateException("cancelled"))
                }
            }

        val runtime = WowRuntime(
            components = listOf(
                component("first", firstReady, firstPrepared),
                component("second", secondReady, secondPrepared),
            ),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )
        val startup = runtime.start().toFuture()

        firstPrepared.await(1, TimeUnit.SECONDS).assert().isTrue()
        calls.assert().doesNotContain("start:first", "start:second")
        firstReady.tryEmitEmpty().orThrow()
        secondPrepared.await(1, TimeUnit.SECONDS).assert().isTrue()
        calls.assert().doesNotContain("start:first", "start:second")

        secondReady.tryEmitEmpty().orThrow()
        startup.get(1, TimeUnit.SECONDS)
        calls.assert().containsExactly(
            "prepare:first",
            "prepare:second",
            "start:first",
            "start:second",
        )
        StepVerifier.create(runtime.stopGracefully()).verifyComplete()
    }

    @Test
    fun `a concurrent second start cannot stop the startup owner`() {
        val prepareSubscribed = CountDownLatch(1)
        val readiness = Sinks.empty<Void>()
        val forceCount = AtomicInteger()
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext): Mono<Void> =
                readiness.asMono().doOnSubscribe {
                    prepareSubscribed.countDown()
                }

            override fun start() = Unit

            override fun stopGracefully(): Mono<Void> = Mono.empty()

            override fun forceStop() {
                forceCount.incrementAndGet()
                readiness.tryEmitError(IllegalStateException("force-stopped"))
            }
        }
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )
        val firstStartup = runtime.start().toFuture()

        try {
            prepareSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()

            val secondStartup = checkNotNull(
                runtime.start().materialize().block(Duration.ofSeconds(1)),
            )
            secondStartup.throwable.assert()
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Current state: STARTING")
            forceCount.get().assert().isZero()
            firstStartup.isDone.assert().isFalse()

            readiness.tryEmitEmpty().orThrow()
            firstStartup.get(1, TimeUnit.SECONDS)
            runtime.isRunning.assert().isTrue()
            StepVerifier.create(runtime.stopGracefully()).verifyComplete()
        } finally {
            readiness.tryEmitEmpty()
            runtime.forceStop()
        }
    }

    @Test
    fun `cancelling a rejected second start cannot stop the startup owner`() {
        val prepareSubscribed = CountDownLatch(1)
        val readiness = Sinks.empty<Void>()
        val forceCount = AtomicInteger()
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext): Mono<Void> =
                readiness.asMono().doOnSubscribe {
                    prepareSubscribed.countDown()
                }

            override fun start() = Unit

            override fun stopGracefully(): Mono<Void> = Mono.empty()

            override fun forceStop() {
                forceCount.incrementAndGet()
                readiness.tryEmitError(IllegalStateException("force-stopped"))
            }
        }
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )
        val firstStartup = runtime.start().toFuture()

        try {
            prepareSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()

            runtime.start().subscribe(
                object : BaseSubscriber<Void>() {
                    override fun hookOnSubscribe(subscription: Subscription) {
                        subscription.cancel()
                    }
                },
            )

            forceCount.get().assert().isZero()
            firstStartup.isDone.assert().isFalse()

            readiness.tryEmitEmpty().orThrow()
            firstStartup.get(1, TimeUnit.SECONDS)
            runtime.isRunning.assert().isTrue()
            StepVerifier.create(runtime.stopGracefully()).verifyComplete()
        } finally {
            readiness.tryEmitEmpty()
            runtime.forceStop()
        }
    }

    @Test
    fun `asynchronous preparation failure rolls back prepared components in reverse order`() {
        val calls = mutableListOf<String>()
        val failure = IllegalStateException("readiness")

        fun component(name: String, preparation: Mono<Void>): RuntimeComponent =
            object : RuntimeComponent {
                override fun prepare(runtimeContext: RuntimeContext): Mono<Void> =
                    Mono.defer {
                        calls += "prepare:$name"
                        preparation
                    }

                override fun start() {
                    calls += "start:$name"
                }

                override fun stopGracefully(): Mono<Void> =
                    Mono.fromRunnable {
                        calls += "stop:$name"
                    }

                override fun forceStop() {
                    calls += "force:$name"
                }
            }

        val runtime = WowRuntime(
            components = listOf(
                component("first", Mono.empty()),
                component("failing", Mono.error(failure)),
            ),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )

        runtime.awaitStartFailure().assert().isSameAs(failure)
        calls.assert().containsExactly(
            "prepare:first",
            "prepare:failing",
            "stop:failing",
            "stop:first",
        )
    }

    @Test
    fun `cancelling startup force stops before propagating cancellation`() {
        val calls = CopyOnWriteArrayList<String>()
        val prepareSubscribed = CountDownLatch(1)
        val forceInvoked = CountDownLatch(1)
        val cancellationObserved = CountDownLatch(1)
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext): Mono<Void> =
                Mono.never<Void>()
                    .doOnSubscribe {
                        calls += "prepare"
                        prepareSubscribed.countDown()
                    }
                    .doOnCancel {
                        forceInvoked.await(1, TimeUnit.SECONDS).assert().isTrue()
                        cancellationObserved.countDown()
                    }

            override fun start() {
                calls += "start"
            }

            override fun stopGracefully(): Mono<Void> =
                Mono.fromRunnable {
                    calls += "stop"
                }

            override fun forceStop() {
                calls += "force"
                forceInvoked.countDown()
            }
        }
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )

        val startup = runtime.start().subscribe()
        prepareSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()
        startup.dispose()

        cancellationObserved.await(1, TimeUnit.SECONDS).assert().isTrue()
        StepVerifier.create(runtime.terminationSignal)
            .expectComplete()
            .verify(Duration.ofSeconds(1))
        calls.assert().containsExactly("prepare", "force", "force")
        runtime.isRunning.assert().isFalse()
    }

    @Test
    fun `termination observers use bounded workers without blocking runtime completion`() {
        val componentForced = AtomicBoolean()
        val blockedObserverCount = 4
        val observersEntered = CountDownLatch(blockedObserverCount)
        val releaseObserver = CountDownLatch(1)
        val healthyObserver = CountDownLatch(1)
        val replayObserver = CountDownLatch(1)
        val terminationDispatcher = newTerminalSignalDispatcher(
            "wow-runtime-test-termination",
            threadCap = blockedObserverCount,
            queuedTaskCapacity = 16,
        )
        val executionResources = immediateExecutionResources(terminationDispatcher)
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext) = Mono.empty<Void>()

            override fun start() = Unit

            override fun stopGracefully(): Mono<Void> = Mono.empty()

            override fun forceStop() {
                componentForced.set(true)
            }
        }
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
            executionResources = executionResources,
        )
        runtime.start().block()
        repeat(blockedObserverCount) {
            runtime.terminationSignal.subscribe(
                {},
                {},
                {
                    componentForced.get().assert().isTrue()
                    observersEntered.countDown()
                    releaseObserver.await()
                },
            )
        }
        runtime.terminationSignal.subscribe({}, {}, healthyObserver::countDown)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val forceStop = executor.submit(runtime::forceStop)

            forceStop.get(1, TimeUnit.SECONDS)
            observersEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            healthyObserver.await(100, TimeUnit.MILLISECONDS).assert().isFalse()

            releaseObserver.countDown()
            healthyObserver.await(1, TimeUnit.SECONDS).assert().isTrue()

            runtime.terminationSignal.subscribe({}, {}, replayObserver::countDown)
            replayObserver.await(1, TimeUnit.SECONDS).assert().isTrue()
        } finally {
            releaseObserver.countDown()
            executor.shutdownNow()
            terminationDispatcher.dispose()
        }
    }

    @Test
    fun `termination subscriber admission fails fast before bounded delivery saturates`() {
        val runningObserverEntered = CountDownLatch(1)
        val releaseRunningObserver = CountDownLatch(1)
        val queuedObserverCompleted = CountDownLatch(1)
        val rejectedFailure = AtomicReference<Throwable?>()
        val rejectionThread = AtomicReference<Thread?>()
        val terminationDispatcher = newTerminalSignalDispatcher(
            threadNamePrefix = "wow-runtime-test-saturated-termination",
            threadCap = 1,
            queuedTaskCapacity = 1,
        )
        val executionResources = immediateExecutionResources(terminationDispatcher)
        val runtime = WowRuntime(
            components = emptyList(),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
            executionResources = executionResources,
        )
        runtime.start().block()
        runtime.terminationSignal.subscribe(
            {},
            {},
            {
                runningObserverEntered.countDown()
                releaseRunningObserver.await()
            },
        )
        runtime.terminationSignal.subscribe({}, {}, queuedObserverCompleted::countDown)
        val subscribingThread = Thread.currentThread()
        runtime.terminationSignal.subscribe(
            {},
            { error ->
                rejectionThread.set(Thread.currentThread())
                rejectedFailure.set(error)
            },
        )
        rejectedFailure.get().assert().isInstanceOf(RejectedExecutionException::class.java)
        rejectionThread.get().assert().isSameAs(subscribingThread)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val forceStop = executor.submit(runtime::forceStop)

            forceStop.get(1, TimeUnit.SECONDS)
            runningObserverEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            queuedObserverCompleted.await(100, TimeUnit.MILLISECONDS).assert().isFalse()

            releaseRunningObserver.countDown()
            queuedObserverCompleted.await(1, TimeUnit.SECONDS).assert().isTrue()
        } finally {
            releaseRunningObserver.countDown()
            executor.shutdownNow()
            terminationDispatcher.dispose()
        }
    }

    @Test
    fun `termination control replays the sealed failure and remains exclusively claimed`() {
        val controlDispatcher = newTerminalSignalDispatcher(
            threadNamePrefix = "wow-runtime-test-control-replay",
            threadCap = 1,
            queuedTaskCapacity = 0,
        )
        val forceFailure = IllegalStateException("force")
        val terminalFailure = AtomicReference<Throwable?>()
        val terminated = CountDownLatch(1)
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext) = Mono.empty<Void>()

            override fun start() = Unit

            override fun stopGracefully(): Mono<Void> = Mono.empty()

            override fun forceStop() {
                throw forceFailure
            }
        }
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
            executionResources = immediateExecutionResources(
                dispatcher = ImmediateTerminalSignalDispatcher,
                controlDispatcher = controlDispatcher,
            ),
        )
        runtime.start().block()
        runtime.forceStop()

        try {
            val control = runtime.claimTerminationControl { error ->
                terminalFailure.set(error)
                terminated.countDown()
            }
            terminated.await(1, TimeUnit.SECONDS).assert().isTrue()
            terminalFailure.get().assert().isSameAs(forceFailure)
            control.dispose()

            assertThrows<IllegalStateException> {
                runtime.claimTerminationControl {}
            }
                .message
                .assert()
                .contains("already been claimed")
        } finally {
            controlDispatcher.dispose()
        }
    }

    @Test
    fun `failed termination control admission rolls back the exclusive claim`() {
        val controlDispatcher = newTerminalSignalDispatcher(
            threadNamePrefix = "wow-runtime-test-control-admission",
            threadCap = 1,
            queuedTaskCapacity = 0,
        )
        val occupiedPermit = checkNotNull(controlDispatcher.tryAcquire())
        val runtime = WowRuntime(
            components = emptyList(),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
            executionResources = immediateExecutionResources(
                dispatcher = ImmediateTerminalSignalDispatcher,
                controlDispatcher = controlDispatcher,
            ),
        )

        try {
            assertThrows<RejectedExecutionException> {
                runtime.claimTerminationControl {}
            }
            occupiedPermit.dispose()
            val terminated = CountDownLatch(1)
            runtime.claimTerminationControl {
                terminated.countDown()
            }
            runtime.forceStop()

            terminated.await(1, TimeUnit.SECONDS).assert().isTrue()
        } finally {
            occupiedPermit.dispose()
            controlDispatcher.dispose()
        }
    }

    @Test
    fun `cleanup rejection seals force failure before late upstream signals`() {
        val stopSubscribed = CountDownLatch(1)
        val stopSignal = Sinks.one<Void>()
        val cleanupDispatches = AtomicInteger()
        val physicalCancellations = AtomicInteger()
        val forceFailure = IllegalStateException("force")
        val lateFailure = IllegalArgumentException("late")
        val executionResources =
            object : RuntimeExecutionResources {
                override val terminationDispatcher: TerminalSignalDispatcher =
                    ImmediateTerminalSignalDispatcher
                override val terminationControlDispatcher: TerminalSignalDispatcher =
                    ImmediateTerminalSignalDispatcher
                override val shutdownScheduler: Scheduler = Schedulers.immediate()
                override val quiescenceScheduler: Scheduler = Schedulers.parallel()

                override fun dispatchCleanup(action: Runnable): Boolean {
                    cleanupDispatches.incrementAndGet()
                    return false
                }
            }
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext) = Mono.empty<Void>()

            override fun start() = Unit

            override fun stopGracefully(): Mono<Void> =
                Mono.defer {
                    stopSubscribed.countDown()
                    stopSignal.asMono()
                        .doOnCancel(physicalCancellations::incrementAndGet)
                }

            override fun forceStop() {
                throw forceFailure
            }
        }
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
            executionResources = executionResources,
        )
        runtime.start().block()

        val termination = runtime.stopGracefully()
        stopSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()
        runtime.forceStop()

        cleanupDispatches.get().assert().isEqualTo(1)
        physicalCancellations.get().assert().isZero()
        StepVerifier.create(termination)
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(forceFailure)
                error.suppressedExceptions.assert().isEmpty()
            }
            .verify()

        stopSignal.tryEmitError(lateFailure).orThrow()
        forceFailure.suppressedExceptions.assert().isEmpty()
        StepVerifier.create(runtime.terminationSignal)
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(forceFailure)
                error.suppressedExceptions.assert().isEmpty()
            }
            .verify()
    }

    @Test
    fun `late graceful failure cannot mutate a sealed runtime failure`() {
        val stopSubscribed = CountDownLatch(1)
        val stopSignal = Sinks.one<Void>()
        val physicalCancellations = AtomicInteger()
        val runtimeFailure = IllegalStateException("runtime")
        val lateFailure = IllegalArgumentException("late-stop")
        val executionResources =
            object : RuntimeExecutionResources {
                override val terminationDispatcher: TerminalSignalDispatcher =
                    ImmediateTerminalSignalDispatcher
                override val terminationControlDispatcher: TerminalSignalDispatcher =
                    ImmediateTerminalSignalDispatcher
                override val shutdownScheduler: Scheduler = Schedulers.immediate()
                override val quiescenceScheduler: Scheduler = Schedulers.parallel()

                override fun dispatchCleanup(action: Runnable): Boolean = false
            }
        var capturedRuntimeContext: RuntimeContext? = null
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext): Mono<Void> =
                Mono.fromRunnable {
                    capturedRuntimeContext = runtimeContext
                }

            override fun start() = Unit

            override fun stopGracefully(): Mono<Void> =
                Mono.defer {
                    stopSubscribed.countDown()
                    stopSignal.asMono()
                        .doOnCancel(physicalCancellations::incrementAndGet)
                }

            override fun forceStop() = Unit
        }
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
            executionResources = executionResources,
        )
        runtime.start().block()

        checkNotNull(capturedRuntimeContext).reportFailure(runtimeFailure)
        stopSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()
        runtime.forceStop()

        StepVerifier.create(runtime.terminationSignal)
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(runtimeFailure)
                error.suppressedExceptions.assert().isEmpty()
            }
            .verify()
        physicalCancellations.get().assert().isZero()

        stopSignal.tryEmitError(lateFailure).orThrow()
        runtimeFailure.suppressedExceptions.assert().isEmpty()
    }

    @Test
    fun `shutdown owner sees boundary before pipeline subscription blocks`() {
        val stopEntered = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        val stopReturned = CountDownLatch(1)
        val cleanupDispatches = AtomicInteger()
        val deadlineScheduler = ControllableDeadlineScheduler()
        val executionResources =
            object : RuntimeExecutionResources {
                override val terminationDispatcher: TerminalSignalDispatcher =
                    ImmediateTerminalSignalDispatcher
                override val terminationControlDispatcher: TerminalSignalDispatcher =
                    ImmediateTerminalSignalDispatcher
                override val shutdownScheduler: Scheduler = Schedulers.immediate()
                override val quiescenceScheduler: Scheduler = Schedulers.parallel()

                override fun dispatchCleanup(action: Runnable): Boolean {
                    cleanupDispatches.incrementAndGet()
                    action.run()
                    return true
                }
            }
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext) = Mono.empty<Void>()

            override fun start() = Unit

            override fun stopGracefully(): Mono<Void> {
                stopEntered.countDown()
                awaitIgnoringInterrupt(releaseStop)
                stopReturned.countDown()
                return Mono.never()
            }

            override fun forceStop() = Unit
        }
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
            executionResources = executionResources,
        ).also {
            it.shutdownDeadlineScheduler = deadlineScheduler
        }
        val executor = Executors.newSingleThreadExecutor()
        runtime.start().block()

        try {
            val gracefulStop = executor.submit<Mono<Void>>(runtime::stopGracefully)
            stopEntered.await(5, TimeUnit.SECONDS).assert().isTrue()

            assertTimeoutPreemptively(Duration.ofSeconds(5), runtime::forceStop)
            cleanupDispatches.get().assert().isEqualTo(1)
            StepVerifier.create(runtime.terminationSignal)
                .expectComplete()
                .verify(Duration.ofSeconds(5))

            releaseStop.countDown()
            stopReturned.await(5, TimeUnit.SECONDS).assert().isTrue()
            gracefulStop.get(5, TimeUnit.SECONDS)
        } finally {
            releaseStop.countDown()
            runtime.forceStop()
            executor.shutdownNow()
        }
    }

    @Test
    fun `start prepares every component before opening processing`() {
        val calls = mutableListOf<String>()
        val first = RecordingLifecycle("first", calls)
        val second = RecordingLifecycle("second", calls)
        val runtime = WowRuntime(listOf(first, second), Duration.ofSeconds(1), Duration.ZERO)

        runtime.start().block()

        calls.assert().containsExactly(
            "prepare:first",
            "prepare:second",
            "start:first",
            "start:second",
        )
        runtime.isRunning.assert().isTrue()

        StepVerifier.create(runtime.stopGracefully()).verifyComplete()
    }

    @Test
    fun `start failure rolls prepared components back in reverse order`() {
        val calls = mutableListOf<String>()
        val startFailure = IllegalStateException("start")
        val cleanupFailure = IllegalArgumentException("cleanup")
        val first = RecordingLifecycle("first", calls, stopFailure = cleanupFailure)
        val second = RecordingLifecycle("second", calls, startFailure = startFailure)
        val runtime = WowRuntime(listOf(first, second), Duration.ofSeconds(1), Duration.ZERO)

        val thrown = runtime.awaitStartFailure()

        thrown.assert().isSameAs(startFailure)
        thrown.suppressedExceptions.assert().containsExactly(cleanupFailure)
        calls.assert().containsExactly(
            "prepare:first",
            "prepare:second",
            "start:first",
            "start:second",
            "stop:second",
            "stop:first",
            "force:second",
            "force:first",
        )
        runtime.isRunning.assert().isFalse()
    }

    @Test
    fun `startup failure closes intake before rollback`() {
        val calls = CopyOnWriteArrayList<String>()
        val quiesced = CountDownLatch(1)
        val startFailure = IllegalStateException("start")
        val first = RecordingLifecycle(
            name = "first",
            calls = calls,
            onQuiesce = quiesced::countDown,
        )
        val failing = RecordingLifecycle(
            name = "failing",
            calls = calls,
            startFailure = startFailure,
        )
        val runtime = WowRuntime(
            components = listOf(first, failing),
            shutdownTimeout = Duration.ofSeconds(60),
            shutdownQuietPeriod = Duration.ofSeconds(30),
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            val startup = runtime.startAsync(executor)

            quiesced.await(1, TimeUnit.SECONDS).assert().isTrue()
            first.runtimeContext!!.tryAcquire().assert().isNull()
            startup.get(1, TimeUnit.SECONDS).assert().isSameAs(startFailure)
        } finally {
            runtime.forceStop()
            executor.shutdownNow()
        }
    }

    @Test
    fun `startup rollback does not block its Reactor worker`() {
        val startFailure = IllegalStateException("start")
        val forceInvocations = AtomicInteger()
        val startupScheduler = Schedulers.newSingle("wow-runtime-startup-test")
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext) = Mono.empty<Void>()

            override fun start() {
                throw startFailure
            }

            override fun stopGracefully(): Mono<Void> =
                Mono.delay(Duration.ZERO, startupScheduler).then()

            override fun forceStop() {
                forceInvocations.incrementAndGet()
            }
        }
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )
        val startupResult = CompletableFuture<Throwable>()

        try {
            startupScheduler.schedule {
                runtime.start().subscribe(
                    {},
                    startupResult::complete,
                    {
                        startupResult.complete(
                            AssertionError("Startup unexpectedly completed."),
                        )
                    },
                )
            }

            startupResult.get(250, TimeUnit.MILLISECONDS).assert().isSameAs(startFailure)
            StepVerifier.create(runtime.terminationSignal)
                .expectErrorSatisfies { error ->
                    error.assert().isSameAs(startFailure)
                }
                .verify(Duration.ofSeconds(1))
            forceInvocations.get().assert().isZero()
        } finally {
            startupScheduler.dispose()
        }
    }

    @Test
    fun `prepare failure gracefully rolls back prepared and force stops all components`() {
        val calls = mutableListOf<String>()
        val prepareFailure = IllegalStateException("prepare")
        val cleanupFailure = IllegalArgumentException("cleanup")
        val first = RecordingLifecycle("first", calls, stopFailure = cleanupFailure)
        val second = RecordingLifecycle("second", calls, prepareFailure = prepareFailure)
        val third = RecordingLifecycle("third", calls)
        val runtime = WowRuntime(
            components = listOf(first, second, third),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )

        val thrown = runtime.awaitStartFailure()

        thrown.assert().isSameAs(prepareFailure)
        thrown.suppressedExceptions.assert().containsExactly(cleanupFailure)
        calls.assert().containsExactly(
            "prepare:first",
            "prepare:second",
            "stop:second",
            "stop:first",
            "force:third",
            "force:second",
            "force:first",
        )
        runtime.isRunning.assert().isFalse()
    }

    @Test
    fun `failure reported during prepare prevents preparing the next component`() {
        val calls = mutableListOf<String>()
        val prepareFailure = IllegalStateException("prepare-runtime")
        val first = RecordingLifecycle(
            name = "first",
            calls = calls,
            onPrepare = {
                it.reportFailure(prepareFailure)
                it.tryAcquire().assert().isNull()
            },
        )
        val second = RecordingLifecycle("second", calls)
        val runtime = WowRuntime(listOf(first, second), Duration.ofSeconds(1), Duration.ZERO)

        val thrown = runtime.awaitStartFailure()

        thrown.assert().isSameAs(prepareFailure)
        calls.assert().containsExactly(
            "prepare:first",
            "stop:first",
        )
    }

    @Test
    fun `failure reported during start prevents starting the next component`() {
        val calls = mutableListOf<String>()
        val startFailure = IllegalStateException("start-runtime")
        val first = RecordingLifecycle(
            name = "first",
            calls = calls,
            onStart = { it.reportFailure(startFailure) },
        )
        val second = RecordingLifecycle("second", calls)
        val runtime = WowRuntime(listOf(first, second), Duration.ofSeconds(1), Duration.ZERO)

        val thrown = runtime.awaitStartFailure()

        thrown.assert().isSameAs(startFailure)
        calls.assert().containsExactly(
            "prepare:first",
            "prepare:second",
            "start:first",
            "stop:second",
            "stop:first",
        )
    }

    @Test
    fun `reported startup failure remains primary when start then throws`() {
        val calls = mutableListOf<String>()
        val reportedFailure = IllegalStateException("reported")
        val thrownFailure = IllegalArgumentException("thrown")
        val component = RecordingLifecycle(
            name = "component",
            calls = calls,
            startFailure = thrownFailure,
            onStart = { it.reportFailure(reportedFailure) },
        )
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )

        val thrown = runtime.awaitStartFailure()

        thrown.assert().isSameAs(reportedFailure)
        thrown.suppressedExceptions.assert().containsExactly(thrownFailure)
        thrownFailure.suppressedExceptions.assert().isEmpty()
        StepVerifier.create(runtime.terminationSignal)
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(reportedFailure)
            }
            .verify()
    }

    @Test
    fun `failure reported during a blocked start is force stopped by the startup deadline`() {
        val calls = CopyOnWriteArrayList<String>()
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val runtimeFailure = IllegalStateException("start-runtime")
        val component = RecordingLifecycle(
            name = "component",
            calls = calls,
            onStart = { runtimeContext ->
                runtimeContext.reportFailure(runtimeFailure)
                startEntered.countDown()
                releaseStart.await()
            },
            beforeForceFailure = releaseStart::countDown,
        )
        val runtime = WowRuntime(listOf(component), Duration.ofMillis(50), Duration.ZERO)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val startup = runtime.startAsync(executor)
            startEntered.await(1, TimeUnit.SECONDS).assert().isTrue()

            StepVerifier.create(runtime.terminationSignal)
                .expectErrorSatisfies { error ->
                    error.assert().isSameAs(runtimeFailure)
                    error.suppressedExceptions
                        .any { it is TimeoutException }
                        .assert()
                        .isTrue()
                }
                .verify(Duration.ofSeconds(1))

            startup.get(1, TimeUnit.SECONDS).assert().isSameAs(runtimeFailure)
            calls.count { it == "force:component" }.assert().isEqualTo(2)
        } finally {
            releaseStart.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `reported startup failure remains primary after force publishes termination`() {
        val calls = CopyOnWriteArrayList<String>()
        val startEntered = CountDownLatch(1)
        val allowLateFailure = CountDownLatch(1)
        val reportedFailure = IllegalStateException("reported")
        val lateFailure = IllegalArgumentException("late")
        val component = RecordingLifecycle(
            name = "component",
            calls = calls,
            startFailure = lateFailure,
            onStart = { runtimeContext ->
                runtimeContext.reportFailure(reportedFailure)
                startEntered.countDown()
                allowLateFailure.await()
            },
        )
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            val startup = runtime.startAsync(executor)
            startEntered.await(1, TimeUnit.SECONDS).assert().isTrue()

            runtime.forceStop()
            StepVerifier.create(runtime.terminationSignal)
                .expectErrorSatisfies { error ->
                    error.assert().isSameAs(reportedFailure)
                }
                .verify()

            allowLateFailure.countDown()
            startup.get(1, TimeUnit.SECONDS).assert().isSameAs(reportedFailure)
            reportedFailure.suppressedExceptions.assert().isEmpty()
        } finally {
            allowLateFailure.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `late force compensation cannot mutate a sealed startup failure`() {
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val forceInvocations = AtomicInteger()
        val runtimeFailure = IllegalStateException("runtime")
        val lateCompensationFailure = IllegalArgumentException("late-compensation")
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext): Mono<Void> =
                Mono.fromRunnable {
                    this.runtimeContext = runtimeContext
                }

            private lateinit var runtimeContext: RuntimeContext

            override fun start() {
                runtimeContext.reportFailure(runtimeFailure)
                startEntered.countDown()
                releaseStart.await()
                throw runtimeFailure
            }

            override fun stopGracefully(): Mono<Void> = Mono.empty()

            override fun forceStop() {
                if (forceInvocations.incrementAndGet() == 2) {
                    throw lateCompensationFailure
                }
            }
        }
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            val startup = runtime.startAsync(executor)
            startEntered.await(1, TimeUnit.SECONDS).assert().isTrue()

            runtime.forceStop()
            StepVerifier.create(runtime.terminationSignal)
                .expectErrorSatisfies { error ->
                    error.assert().isSameAs(runtimeFailure)
                    error.suppressedExceptions.assert().isEmpty()
                }
                .verify()

            releaseStart.countDown()
            startup.get(1, TimeUnit.SECONDS).assert().isSameAs(runtimeFailure)
            forceInvocations.get().assert().isEqualTo(2)
            runtimeFailure.suppressedExceptions.assert().isEmpty()
        } finally {
            releaseStart.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `successful startup rollback does not force stop components`() {
        val calls = mutableListOf<String>()
        val startFailure = IllegalStateException("start")
        val first = RecordingLifecycle("first", calls)
        val failing = RecordingLifecycle("failing", calls, startFailure = startFailure)
        val runtime = WowRuntime(
            components = listOf(first, failing),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )

        val thrown = runtime.awaitStartFailure()

        thrown.assert().isSameAs(startFailure)
        thrown.suppressedExceptions.assert().isEmpty()
        calls.assert().containsExactly(
            "prepare:first",
            "prepare:failing",
            "start:first",
            "start:failing",
            "stop:failing",
            "stop:first",
        )
        StepVerifier.create(runtime.terminationSignal)
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(startFailure)
            }
            .verify()
    }

    @Test
    fun `startup rollback deadline force stops hanging cleanup without deadlock`() {
        val calls = CopyOnWriteArrayList<String>()
        val startFailure = IllegalStateException("start")
        val hanging = RecordingLifecycle("hanging", calls, stopGate = Sinks.empty())
        val failing = RecordingLifecycle("failing", calls, startFailure = startFailure)
        val runtime = WowRuntime(
            components = listOf(hanging, failing),
            shutdownTimeout = Duration.ofMillis(50),
            shutdownQuietPeriod = Duration.ZERO,
        )

        val thrown = assertTimeoutPreemptively(Duration.ofSeconds(1)) {
            runtime.awaitStartFailure()
        }

        thrown.assert().isSameAs(startFailure)
        thrown.suppressedExceptions.assert().hasSize(1)
        thrown.suppressedExceptions.single().assert()
            .isInstanceOf(TimeoutException::class.java)
        calls.assert().containsSubsequence("force:failing", "force:hanging")
        runtime.isRunning.assert().isFalse()
    }

    @Test
    fun `shutdown waits for runtime quiescence before stopping components`() {
        val calls = mutableListOf<String>()
        val component = RecordingLifecycle("component", calls)
        val runtime = WowRuntime(listOf(component), Duration.ofSeconds(1), Duration.ZERO)
        runtime.start().block()
        val activity = checkNotNull(component.runtimeContext!!.tryAcquire())

        StepVerifier.create(runtime.stopGracefully())
            .expectSubscription()
            .expectNoEvent(Duration.ofMillis(100))
            .then {
                calls.assert().doesNotContain("stop:component")
                activity.close()
            }
            .verifyComplete()

        calls.assert().contains("stop:component")
    }

    @Test
    fun `shutdown closes admission before quiescing components in registration order`() {
        val calls = mutableListOf<String>()
        lateinit var first: RecordingLifecycle
        lateinit var second: RecordingLifecycle
        first = RecordingLifecycle(
            name = "first",
            calls = calls,
            onQuiesce = {
                first.runtimeContext!!.tryAcquire().assert().isNull()
                calls += "quiesce:first"
            },
        )
        second = RecordingLifecycle(
            name = "second",
            calls = calls,
            onQuiesce = {
                second.runtimeContext!!.tryAcquire().assert().isNull()
                calls += "quiesce:second"
            },
        )
        val runtime = WowRuntime(
            components = listOf(first, second),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )
        runtime.start().block()

        StepVerifier.create(runtime.stopGracefully()).verifyComplete()

        calls.takeLast(4).assert().containsExactly(
            "quiesce:first",
            "quiesce:second",
            "stop:second",
            "stop:first",
        )
    }

    @Test
    fun `component quiesce resumes on the shutdown scheduler after the quiet boundary`() {
        val shutdownScheduler = Schedulers.newSingle("runtime-quiesce-test-shutdown")
        val quiescenceScheduler = Schedulers.newSingle("runtime-quiesce-test-boundary")
        val quiesceThread = AtomicReference<String?>()
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext) = Mono.empty<Void>()

            override fun start() = Unit

            override fun quiesce() {
                quiesceThread.set(Thread.currentThread().name)
            }

            override fun stopGracefully(): Mono<Void> = Mono.empty()

            override fun forceStop() = Unit
        }
        val executionResources = object : RuntimeExecutionResources {
            override val terminationDispatcher: TerminalSignalDispatcher =
                ImmediateTerminalSignalDispatcher
            override val terminationControlDispatcher: TerminalSignalDispatcher =
                ImmediateTerminalSignalDispatcher
            override val shutdownScheduler: Scheduler = shutdownScheduler
            override val quiescenceScheduler: Scheduler = quiescenceScheduler

            override fun dispatchCleanup(action: Runnable): Boolean {
                action.run()
                return true
            }
        }
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ofMillis(10),
            executionResources = executionResources,
        )

        try {
            runtime.start().block()
            StepVerifier.create(runtime.stopGracefully()).verifyComplete()

            quiesceThread.get().assert().startsWith("runtime-quiesce-test-shutdown")
        } finally {
            shutdownScheduler.dispose()
            quiescenceScheduler.dispose()
        }
    }

    @Test
    fun `shutdown continues after failures and suppresses later failures`() {
        val calls = mutableListOf<String>()
        val firstFailure = IllegalStateException("first")
        val secondFailure = IllegalArgumentException("second")
        val first = RecordingLifecycle("first", calls, stopFailure = firstFailure)
        val second = RecordingLifecycle("second", calls, stopFailure = secondFailure)
        val runtime = WowRuntime(listOf(first, second), Duration.ofSeconds(1), Duration.ZERO)
        runtime.start().block()

        StepVerifier.create(runtime.stopGracefully())
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(secondFailure)
                error.suppressedExceptions.assert().containsExactly(firstFailure)
            }
            .verify()

        calls.takeLast(4).assert().containsExactly(
            "stop:second",
            "stop:first",
            "force:second",
            "force:first",
        )
    }

    @Test
    fun `shutdown observers share termination without owning cancellation`() {
        val calls = mutableListOf<String>()
        val stopGate = Sinks.empty<Void>()
        val component = RecordingLifecycle("component", calls, stopGate = stopGate)
        val runtime = WowRuntime(listOf(component), Duration.ofSeconds(1), Duration.ZERO)
        runtime.start().block()

        val cancelledObserver = runtime.stopGracefully().subscribe()
        cancelledObserver.dispose()

        StepVerifier.create(runtime.stopGracefully())
            .expectSubscription()
            .then { stopGate.tryEmitEmpty().orThrow() }
            .verifyComplete()

        calls.count { it == "stop:component" }.assert().isEqualTo(1)
    }

    @Test
    fun `concurrent shutdown observers initiate cleanup exactly once`() {
        val calls = CopyOnWriteArrayList<String>()
        val stopGate = Sinks.empty<Void>()
        val component = RecordingLifecycle("component", calls, stopGate = stopGate)
        val runtime = WowRuntime(listOf(component), Duration.ofSeconds(1), Duration.ZERO)
        val observerCount = 8
        val ready = CountDownLatch(observerCount)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(observerCount)
        runtime.start().block()

        try {
            val observers = (1..observerCount).map {
                CompletableFuture.runAsync(
                    {
                        ready.countDown()
                        start.await()
                        runtime.stopGracefully().block()
                    },
                    executor,
                )
            }
            ready.await(1, TimeUnit.SECONDS).assert().isTrue()
            start.countDown()
            val stopDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (calls.none { it == "stop:component" } && System.nanoTime() < stopDeadline) {
                Thread.onSpinWait()
            }
            calls.assert().contains("stop:component")
            stopGate.tryEmitEmpty().orThrow()
            CompletableFuture.allOf(*observers.toTypedArray()).get(1, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        calls.count { it == "stop:component" }.assert().isEqualTo(1)
    }

    @Test
    fun `global shutdown deadline force stops every component and suppresses force failures`() {
        val calls = CopyOnWriteArrayList<String>()
        val stopStarted = CountDownLatch(1)
        val deadlineScheduler = ControllableDeadlineScheduler()
        val forceFailure = IllegalStateException("force")
        val first = RecordingLifecycle(
            name = "first",
            calls = calls,
            stopGate = Sinks.empty(),
            forceFailure = forceFailure,
        )
        val second = RecordingLifecycle(
            name = "second",
            calls = calls,
            stopGate = Sinks.empty(),
            onStop = stopStarted::countDown,
        )
        val runtime = WowRuntime(listOf(first, second), Duration.ofSeconds(1), Duration.ZERO).also {
            it.shutdownDeadlineScheduler = deadlineScheduler
        }
        runtime.start().block()
        val termination = runtime.stopGracefully()
        stopStarted.await(1, TimeUnit.SECONDS).assert().isTrue()
        deadlineScheduler.runScheduled()

        StepVerifier.create(termination)
            .expectErrorSatisfies { error ->
                error.assert().isInstanceOf(TimeoutException::class.java)
                error.suppressedExceptions.assert().containsExactly(forceFailure)
            }
            .verify()

        calls.assert().contains("stop:second", "force:second", "force:first")
        calls.assert().doesNotContain("stop:first")
        runtime.isRunning.assert().isFalse()
    }

    @Test
    fun `shutdown deadline covers a blocking component quiesce`() {
        val calls = CopyOnWriteArrayList<String>()
        val quiesceStarted = CountDownLatch(1)
        val allowQuiesce = CountDownLatch(1)
        val compensationCompleted = CountDownLatch(1)
        val firstForceCount = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        val deadlineScheduler = ControllableDeadlineScheduler()
        val first = RecordingLifecycle(
            name = "first",
            calls = calls,
            onQuiesce = {
                calls += "quiesce:first"
                quiesceStarted.countDown()
                allowQuiesce.await()
            },
            beforeForceFailure = {
                if (firstForceCount.incrementAndGet() == 2) {
                    compensationCompleted.countDown()
                }
            },
        )
        val second = RecordingLifecycle(
            name = "second",
            calls = calls,
            onQuiesce = {
                calls += "quiesce:second"
            },
        )
        val runtime = WowRuntime(listOf(first, second), Duration.ofSeconds(1), Duration.ZERO).also {
            it.shutdownDeadlineScheduler = deadlineScheduler
        }
        runtime.start().block()

        try {
            val stopCall = CompletableFuture.supplyAsync(runtime::stopGracefully, executor)
            quiesceStarted.await(1, TimeUnit.SECONDS).assert().isTrue()
            val termination = stopCall.get(1, TimeUnit.SECONDS)
            deadlineScheduler.runScheduled()

            StepVerifier.create(termination)
                .expectError(TimeoutException::class.java)
                .verify()
            calls.assert().contains("force:first", "force:second")
            calls.assert().doesNotContain("quiesce:second")

            allowQuiesce.countDown()
            compensationCompleted.await(1, TimeUnit.SECONDS).assert().isTrue()
            firstForceCount.get().assert().isEqualTo(2)
            calls.count { it == "force:second" }.assert().isOne()
        } finally {
            allowQuiesce.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `quiescence scheduler rejection closes intake before force stop`() {
        val calls = CopyOnWriteArrayList<String>()
        val forceStopInvoked = CountDownLatch(1)
        val schedulingFailure = RejectedExecutionException("rejected")
        val quiesceInvocations = AtomicInteger()
        val component = RecordingLifecycle(
            name = "component",
            calls = calls,
            beforeForceFailure = forceStopInvoked::countDown,
            onQuiesce = quiesceInvocations::incrementAndGet,
        )
        val executionResources =
            object : RuntimeExecutionResources {
                override val terminationDispatcher: TerminalSignalDispatcher =
                    DefaultRuntimeExecutionResources.terminationDispatcher
                override val terminationControlDispatcher: TerminalSignalDispatcher =
                    DefaultRuntimeExecutionResources.terminationControlDispatcher
                override val shutdownScheduler: Scheduler = Schedulers.immediate()
                override val quiescenceScheduler: Scheduler =
                    RejectingScheduler(schedulingFailure)

                override fun dispatchCleanup(action: Runnable): Boolean =
                    DefaultRuntimeExecutionResources.dispatchCleanup(action)
            }
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
            executionResources = executionResources,
        )
        runtime.start().block()

        StepVerifier.create(runtime.stopGracefully())
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(schedulingFailure)
            }
            .verify()

        forceStopInvoked.await(1, TimeUnit.SECONDS).assert().isTrue()
        quiesceInvocations.get().assert().isOne()
    }

    @Test
    fun `quiesce failure does not interrupt force cleanup on the same control thread`() {
        val quiesceFailure = IllegalStateException("quiesce")
        val forceObservedInterrupted = AtomicBoolean(true)
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext) = Mono.empty<Void>()

            override fun start() = Unit

            override fun quiesce() {
                throw quiesceFailure
            }

            override fun stopGracefully(): Mono<Void> = Mono.empty()

            override fun forceStop() {
                forceObservedInterrupted.set(Thread.currentThread().isInterrupted)
            }
        }
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )
        runtime.start().block()

        StepVerifier.create(runtime.stopGracefully())
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(quiesceFailure)
            }
            .verify()

        forceObservedInterrupted.get().assert().isFalse()
    }

    @Test
    fun `shutdown deadline covers blocked quiet timer publication`() {
        val calls = CopyOnWriteArrayList<String>()
        val forceStopInvoked = CountDownLatch(1)
        val scheduler = BlockingScheduleScheduler(Schedulers.parallel())
        val component = RecordingLifecycle(
            name = "component",
            calls = calls,
            beforeForceFailure = forceStopInvoked::countDown,
        )
        val executionResources =
            object : RuntimeExecutionResources {
                override val terminationDispatcher: TerminalSignalDispatcher =
                    DefaultRuntimeExecutionResources.terminationDispatcher
                override val terminationControlDispatcher: TerminalSignalDispatcher =
                    DefaultRuntimeExecutionResources.terminationControlDispatcher
                override val shutdownScheduler: Scheduler =
                    DefaultRuntimeExecutionResources.shutdownScheduler
                override val quiescenceScheduler: Scheduler = scheduler

                override fun dispatchCleanup(action: Runnable): Boolean =
                    DefaultRuntimeExecutionResources.dispatchCleanup(action)
            }
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofMillis(50),
            shutdownQuietPeriod = Duration.ofMillis(10),
            executionResources = executionResources,
        )
        runtime.start().block()

        try {
            val termination = assertTimeoutPreemptively(Duration.ofMillis(100)) {
                runtime.stopGracefully()
            }
            scheduler.scheduleStarted.await(1, TimeUnit.SECONDS).assert().isTrue()
            scheduler.schedulingThreadName.get().assert()
                .startsWith("wow-runtime-shutdown-")

            StepVerifier.create(termination)
                .expectError(TimeoutException::class.java)
                .verify()
            forceStopInvoked.await(1, TimeUnit.SECONDS).assert().isTrue()
        } finally {
            scheduler.allowScheduleReturn.countDown()
        }
    }

    @Test
    fun `explicit force closes admission without entering graceful quiesce`() {
        val calls = CopyOnWriteArrayList<String>()
        val quiesceInvocations = AtomicInteger()
        val component = RecordingLifecycle(
            name = "component",
            calls = calls,
            onQuiesce = quiesceInvocations::incrementAndGet,
        )
        val runtime = WowRuntime(listOf(component), Duration.ofSeconds(1), Duration.ZERO)
        runtime.start().block()
        val runtimeContext = component.runtimeContext!!
        val activeOperation = runtimeContext.tryAcquire()!!
        val termination = runtime.stopGracefully()

        runtime.forceStop()

        runtimeContext.tryAcquire().assert().isNull()
        activeOperation.close()
        StepVerifier.create(termination).verifyComplete()
        quiesceInvocations.get().assert().isZero()
        calls.assert().contains("force:component")
    }

    @Test
    fun `every concurrent force caller closes admission before returning`() {
        val forceEntered = CountDownLatch(1)
        val releaseForce = CountDownLatch(1)
        var capturedRuntimeContext: RuntimeContext? = null
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext): Mono<Void> =
                Mono.fromRunnable {
                    capturedRuntimeContext = runtimeContext
                }

            override fun start() = Unit

            override fun stopGracefully(): Mono<Void> = Mono.never()

            override fun forceStop() {
                forceEntered.countDown()
                releaseForce.await()
            }
        }
        val runtime = WowRuntime(listOf(component), Duration.ofSeconds(1), Duration.ZERO)
        val executor = Executors.newSingleThreadExecutor()
        var firstForce: CompletableFuture<Void>? = null
        runtime.start().block()
        val activeOperation = checkNotNull(capturedRuntimeContext).tryAcquire()
        checkNotNull(activeOperation)

        try {
            runtime.stopGracefully()
            firstForce = CompletableFuture.runAsync(runtime::forceStop, executor)
            forceEntered.await(1, TimeUnit.SECONDS).assert().isTrue()

            runtime.forceStop()

            val admittedAfterForce = checkNotNull(capturedRuntimeContext).tryAcquire()
            try {
                admittedAfterForce.assert().isNull()
            } finally {
                admittedAfterForce?.close()
            }
        } finally {
            try {
                activeOperation.close()
            } finally {
                releaseForce.countDown()
                try {
                    firstForce?.get(1, TimeUnit.SECONDS)
                } finally {
                    executor.shutdownNow()
                }
            }
        }
    }

    @Test
    fun `deadline force closes admission without entering graceful quiesce`() {
        val calls = CopyOnWriteArrayList<String>()
        val quiesceInvocations = AtomicInteger()
        val component = RecordingLifecycle(
            name = "component",
            calls = calls,
            onQuiesce = quiesceInvocations::incrementAndGet,
        )
        val runtime = WowRuntime(listOf(component), Duration.ofMillis(50), Duration.ZERO)
        runtime.start().block()
        val runtimeContext = component.runtimeContext!!
        val activeOperation = runtimeContext.tryAcquire()!!

        StepVerifier.create(runtime.stopGracefully())
            .expectError(TimeoutException::class.java)
            .verify()

        runtimeContext.tryAcquire().assert().isNull()
        activeOperation.close()
        quiesceInvocations.get().assert().isZero()
        calls.assert().contains("force:component")
    }

    @Test
    fun `graceful failure remains primary when a later component reaches the deadline`() {
        val calls = mutableListOf<String>()
        val hangingStopStarted = CountDownLatch(1)
        val deadlineScheduler = ControllableDeadlineScheduler()
        val gracefulFailure = IllegalStateException("graceful")
        val hanging = RecordingLifecycle(
            "hanging",
            calls,
            stopGate = Sinks.empty(),
            onStop = hangingStopStarted::countDown,
        )
        val failing = RecordingLifecycle("failing", calls, stopFailure = gracefulFailure)
        val runtime = WowRuntime(
            listOf(hanging, failing),
            Duration.ofMillis(50),
            Duration.ZERO,
        ).also {
            it.shutdownDeadlineScheduler = deadlineScheduler
        }
        runtime.start().block()
        val termination = runtime.stopGracefully()
        hangingStopStarted.await(1, TimeUnit.SECONDS).assert().isTrue()
        deadlineScheduler.runScheduled()

        StepVerifier.create(termination)
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(gracefulFailure)
                error.suppressedExceptions.assert().hasSize(1)
                error.suppressedExceptions.single().assert()
                    .isInstanceOf(TimeoutException::class.java)
            }
            .verify()

        calls.assert().containsSubsequence("stop:failing", "stop:hanging")
        calls.assert().containsSubsequence("force:failing", "force:hanging")
    }

    @Test
    fun `explicit force stop cancels graceful cleanup with at most one compensation pass`() {
        val calls = CopyOnWriteArrayList<String>()
        val stopGate = Sinks.empty<Void>()
        val stopStarted = CountDownLatch(1)
        val component = RecordingLifecycle(
            "component",
            calls,
            stopGate = stopGate,
            onStop = stopStarted::countDown,
        )
        val runtime = WowRuntime(listOf(component), Duration.ofSeconds(1), Duration.ZERO)
        runtime.start().block()
        val termination = runtime.stopGracefully()
        stopStarted.await(1, TimeUnit.SECONDS).assert().isTrue()
        calls.assert().contains("stop:component")

        runtime.forceStop()
        runtime.forceStop()

        StepVerifier.create(termination).verifyComplete()
        calls.count { it == "stop:component" }.assert().isEqualTo(1)
        val forceCalls = calls.count { it == "force:component" }
        forceCalls.assert().isGreaterThanOrEqualTo(1)
        forceCalls.assert().isLessThanOrEqualTo(2)
        runtime.isRunning.assert().isFalse()
    }

    @Test
    fun `failure reported while stopping remains the terminal cause`() {
        val calls = CopyOnWriteArrayList<String>()
        val stopGate = Sinks.empty<Void>()
        val runtimeFailure = IllegalStateException("runtime-while-stopping")
        val component = RecordingLifecycle("component", calls, stopGate = stopGate)
        val runtime = WowRuntime(listOf(component), Duration.ofSeconds(1), Duration.ZERO)
        runtime.start().block()
        val termination = runtime.stopGracefully()
        calls.awaitContains("stop:component")
        calls.assert().contains("stop:component")

        component.runtimeContext!!.reportFailure(runtimeFailure)
        stopGate.tryEmitEmpty().orThrow()

        StepVerifier.create(termination)
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(runtimeFailure)
            }
            .verify()
        calls.assert().contains("force:component")
    }

    @Test
    fun `explicit force retains a graceful failure observed before a later stop hangs`() {
        val calls = CopyOnWriteArrayList<String>()
        val gracefulFailure = IllegalStateException("graceful")
        val hanging = RecordingLifecycle("hanging", calls, stopGate = Sinks.empty())
        val failing = RecordingLifecycle("failing", calls, stopFailure = gracefulFailure)
        val runtime = WowRuntime(
            components = listOf(hanging, failing),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )
        runtime.start().block()
        val termination = runtime.stopGracefully()
        calls.awaitContains("stop:hanging")
        calls.assert().containsSubsequence("stop:failing", "stop:hanging")

        runtime.forceStop()

        StepVerifier.create(termination)
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(gracefulFailure)
            }
            .verify()
    }

    @Test
    fun `explicit force owns termination when graceful terminal races with force cleanup`() {
        val calls = CopyOnWriteArrayList<String>()
        val stopGate = Sinks.empty<Void>()
        val forceStarted = CountDownLatch(1)
        val allowForceCompletion = CountDownLatch(1)
        val forceFailure = IllegalStateException("force")
        val component = RecordingLifecycle(
            name = "component",
            calls = calls,
            stopGate = stopGate,
            forceFailure = forceFailure,
            beforeForceFailure = {
                forceStarted.countDown()
                allowForceCompletion.await()
            },
        )
        val runtime = WowRuntime(listOf(component), Duration.ofSeconds(1), Duration.ZERO)
        val executor = Executors.newSingleThreadExecutor()
        runtime.start().block()
        val termination = runtime.stopGracefully()
        calls.awaitContains("stop:component")

        try {
            val force = CompletableFuture.runAsync(runtime::forceStop, executor)
            forceStarted.await(1, TimeUnit.SECONDS).assert().isTrue()

            stopGate.tryEmitEmpty().orThrow()

            StepVerifier.create(termination)
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(100))
                .then(allowForceCompletion::countDown)
                .expectErrorSatisfies { error ->
                    error.assert().isSameAs(forceFailure)
                }
                .verify()
            force.get(1, TimeUnit.SECONDS)
        } finally {
            allowForceCompletion.countDown()
            executor.shutdownNow()
        }

        calls.count { it == "stop:component" }.assert().isEqualTo(1)
        val forceCalls = calls.count { it == "force:component" }
        forceCalls.assert().isGreaterThanOrEqualTo(1)
        forceCalls.assert().isLessThanOrEqualTo(2)
        runtime.isRunning.assert().isFalse()
    }

    @Test
    fun `graceful failure retains force cleanup failure as suppressed`() {
        val calls = mutableListOf<String>()
        val gracefulFailure = IllegalStateException("graceful")
        val forceFailure = IllegalArgumentException("force")
        val component = RecordingLifecycle(
            name = "component",
            calls = calls,
            stopFailure = gracefulFailure,
            forceFailure = forceFailure,
        )
        val runtime = WowRuntime(listOf(component), Duration.ofSeconds(1), Duration.ZERO)
        runtime.start().block()

        StepVerifier.create(runtime.stopGracefully())
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(gracefulFailure)
                error.suppressedExceptions.assert().containsExactly(forceFailure)
            }
            .verify()

        calls.takeLast(2).assert().containsExactly("stop:component", "force:component")
    }

    @Test
    fun `runtime component failure terminates the complete runtime`() {
        val calls = mutableListOf<String>()
        val runtimeFailure = IllegalStateException("runtime")
        val component = RecordingLifecycle("component", calls)
        val runtime = WowRuntime(listOf(component), Duration.ofSeconds(1), Duration.ZERO)
        runtime.start().block()

        component.runtimeContext!!.reportFailure(runtimeFailure)

        StepVerifier.create(runtime.stopGracefully())
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(runtimeFailure)
            }
            .verify()
        calls.takeLast(2).assert().containsExactly("stop:component", "force:component")
        runtime.isRunning.assert().isFalse()
    }

    @Test
    fun `fatal failure closes intake before admitted work drains`() {
        val calls = CopyOnWriteArrayList<String>()
        val quiesced = CountDownLatch(1)
        val runtimeFailure = IllegalStateException("runtime")
        val component = RecordingLifecycle(
            name = "component",
            calls = calls,
            onQuiesce = quiesced::countDown,
        )
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ofMillis(500),
        )
        runtime.start().block()
        val runtimeContext = checkNotNull(component.runtimeContext)
        val activity = checkNotNull(runtimeContext.tryAcquire())

        runtimeContext.reportFailure(runtimeFailure)

        quiesced.await(1, TimeUnit.SECONDS).assert().isTrue()
        runtimeContext.tryAcquire().assert().isNull()
        calls.assert().doesNotContain("stop:component")

        activity.close()
        StepVerifier.create(runtime.terminationSignal)
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(runtimeFailure)
            }
            .verify(Duration.ofSeconds(1))
        calls.assert().containsSubsequence("stop:component", "force:component")
    }

    @Test
    fun `fatal failure escalates an ordinary quiet shutdown immediately`() {
        val calls = CopyOnWriteArrayList<String>()
        val quiesced = CountDownLatch(1)
        val runtimeFailure = IllegalStateException("runtime-while-stopping")
        val component = RecordingLifecycle(
            name = "component",
            calls = calls,
            onQuiesce = quiesced::countDown,
        )
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ofMillis(500),
        )
        runtime.start().block()
        val runtimeContext = checkNotNull(component.runtimeContext)
        val activity = checkNotNull(runtimeContext.tryAcquire())
        val termination = runtime.stopGracefully()

        runtimeContext.reportFailure(runtimeFailure)

        quiesced.await(1, TimeUnit.SECONDS).assert().isTrue()
        runtimeContext.tryAcquire().assert().isNull()
        calls.assert().doesNotContain("stop:component")

        activity.close()
        StepVerifier.create(termination)
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(runtimeFailure)
            }
            .verify(Duration.ofSeconds(1))
    }

    @Test
    fun `graceful stop requested during prepare is retained and rolls startup back`() {
        val calls = CopyOnWriteArrayList<String>()
        val prepareStarted = CountDownLatch(1)
        val allowPrepareReturn = CountDownLatch(1)
        val component = RecordingLifecycle(
            name = "component",
            calls = calls,
            onPrepare = {
                prepareStarted.countDown()
                allowPrepareReturn.await()
            },
        )
        val runtime = WowRuntime(listOf(component), Duration.ofSeconds(1), Duration.ZERO)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val startup = runtime.startAsync(executor)
            prepareStarted.await(1, TimeUnit.SECONDS).assert().isTrue()

            val termination = runtime.stopGracefully().toFuture()
            termination.isDone.assert().isFalse()
            allowPrepareReturn.countDown()

            startup.get(1, TimeUnit.SECONDS)
                .assert()
                .isInstanceOf(IllegalStateException::class.java)
            termination.get(1, TimeUnit.SECONDS)
            calls.assert().containsExactly(
                "prepare:component",
                "stop:component",
            )
            runtime.isRunning.assert().isFalse()
        } finally {
            allowPrepareReturn.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `startup cancellation retains cleanup failure diagnostics`() {
        val calls = CopyOnWriteArrayList<String>()
        val prepareStarted = CountDownLatch(1)
        val allowPrepareReturn = CountDownLatch(1)
        val cleanupFailure = IllegalStateException("cleanup")
        val component = RecordingLifecycle(
            name = "component",
            calls = calls,
            stopFailure = cleanupFailure,
            onPrepare = {
                prepareStarted.countDown()
                allowPrepareReturn.await()
            },
        )
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            val startup = runtime.startAsync(executor)
            prepareStarted.await(1, TimeUnit.SECONDS).assert().isTrue()

            val termination = runtime.stopGracefully()
            allowPrepareReturn.countDown()

            val startupFailure = startup.get(1, TimeUnit.SECONDS)
            checkNotNull(startupFailure)
            startupFailure.message.assert().contains("startup was cancelled")
            startupFailure.cause.assert().isSameAs(cleanupFailure)
            startupFailure.suppressedExceptions.assert().isEmpty()
            StepVerifier.create(termination)
                .expectErrorSatisfies { error ->
                    error.assert().isSameAs(cleanupFailure)
                }
                .verify()
        } finally {
            allowPrepareReturn.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `startup shutdown deadline force stops a blocked lifecycle action`() {
        val calls = CopyOnWriteArrayList<String>()
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val component = RecordingLifecycle(
            name = "component",
            calls = calls,
            onStart = {
                startEntered.countDown()
                releaseStart.await()
            },
            beforeForceFailure = releaseStart::countDown,
        )
        val runtime = WowRuntime(listOf(component), Duration.ofMillis(50), Duration.ZERO)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val startup = runtime.startAsync(executor)
            startEntered.await(1, TimeUnit.SECONDS).assert().isTrue()

            StepVerifier.create(runtime.stopGracefully())
                .expectError(TimeoutException::class.java)
                .verify(Duration.ofSeconds(1))

            startup.get(1, TimeUnit.SECONDS).assert().isNotNull()
            calls.count { it == "force:component" }.assert().isEqualTo(2)
            runtime.isRunning.assert().isFalse()
        } finally {
            releaseStart.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `force stop interrupts a startup blocked inside a component`() {
        val calls = CopyOnWriteArrayList<String>()
        val startInvoked = CountDownLatch(1)
        val allowStartReturn = CountDownLatch(1)
        val forceStopInvoked = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val component = RecordingLifecycle(
            name = "component",
            calls = calls,
            onStart = {
                startInvoked.countDown()
                allowStartReturn.await()
            },
            beforeForceFailure = {
                forceStopInvoked.countDown()
                allowStartReturn.countDown()
            },
        )
        val runtime = WowRuntime(listOf(component), Duration.ofSeconds(1), Duration.ZERO)

        val startCall = runtime.startAsync(executor)
        try {
            startInvoked.await(1, TimeUnit.SECONDS).assert().isTrue()
            val forceCall = CompletableFuture.supplyAsync(
                {
                    runCatching(runtime::forceStop).exceptionOrNull()
                },
                executor,
            )

            val forceStopObservedInTime = forceStopInvoked.await(1, TimeUnit.SECONDS)
            if (!forceStopObservedInTime) {
                allowStartReturn.countDown()
            }
            val forceFailure = forceCall.get(1, TimeUnit.SECONDS)
            val startFailure = startCall.get(1, TimeUnit.SECONDS)

            forceStopObservedInTime.assert().isTrue()
            forceFailure.assert().isNull()
            startFailure.assert().isNotNull()
            calls.count { it == "force:component" }.assert().isEqualTo(2)
            runtime.isRunning.assert().isFalse()
        } finally {
            allowStartReturn.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `force stop compensates resources acquired after an overlapping prepare call`() {
        val prepareStarted = CountDownLatch(1)
        val allowResourceAcquisition = CountDownLatch(1)
        val resourceOpen = AtomicBoolean()
        val forceInvocations = AtomicInteger()
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext): Mono<Void> =
                Mono.fromRunnable {
                    prepareStarted.countDown()
                    allowResourceAcquisition.await()
                    resourceOpen.set(true)
                }

            override fun start() = Unit

            override fun stopGracefully(): Mono<Void> = Mono.empty()

            override fun forceStop() {
                forceInvocations.incrementAndGet()
                resourceOpen.set(false)
            }
        }
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            val startup = runtime.startAsync(executor)
            prepareStarted.await(1, TimeUnit.SECONDS).assert().isTrue()

            runtime.forceStop()
            forceInvocations.get().assert().isEqualTo(1)

            allowResourceAcquisition.countDown()
            startup.get(1, TimeUnit.SECONDS).assert().isNotNull()

            forceInvocations.get().assert().isEqualTo(2)
            resourceOpen.get().assert().isFalse()
        } finally {
            allowResourceAcquisition.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `deadline scheduler rejection immediately force stops startup`() {
        val schedulingFailure = RejectedExecutionException("deadline-rejected")
        val prepareEntered = CountDownLatch(1)
        val releasePrepare = CountDownLatch(1)
        val forceInvoked = CountDownLatch(1)
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext): Mono<Void> =
                Mono.fromRunnable {
                    prepareEntered.countDown()
                    releasePrepare.await()
                }

            override fun start() = Unit

            override fun stopGracefully(): Mono<Void> = Mono.empty()

            override fun forceStop() {
                forceInvoked.countDown()
                releasePrepare.countDown()
            }
        }
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        ).also {
            it.shutdownDeadlineScheduler = RejectingScheduler(schedulingFailure)
        }
        val executor = Executors.newSingleThreadExecutor()

        try {
            val startup = runtime.startAsync(executor)
            prepareEntered.await(1, TimeUnit.SECONDS).assert().isTrue()

            val termination = runtime.stopGracefully()

            forceInvoked.await(1, TimeUnit.SECONDS).assert().isTrue()
            StepVerifier.create(termination)
                .expectErrorSatisfies { error ->
                    error.assert().isSameAs(schedulingFailure)
                }
                .verify()
            startup.get(1, TimeUnit.SECONDS).assert().isNotNull()
        } finally {
            releasePrepare.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `dedicated deadline terminates while graceful cancellation hook is blocked`() {
        val stopSubscribed = CountDownLatch(1)
        val cancellationEntered = CountDownLatch(1)
        val releaseCancellation = CountDownLatch(1)
        val forceInvocations = AtomicInteger()
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext) = Mono.empty<Void>()

            override fun start() = Unit

            override fun stopGracefully(): Mono<Void> =
                Mono.defer {
                    stopSubscribed.countDown()
                    Mono.never<Void>()
                        .doOnCancel {
                            cancellationEntered.countDown()
                            awaitIgnoringInterrupt(releaseCancellation)
                        }
                }

            override fun forceStop() {
                forceInvocations.incrementAndGet()
            }
        }
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofMillis(50),
            shutdownQuietPeriod = Duration.ZERO,
        )
        runtime.start().block()

        try {
            val termination = runtime.stopGracefully()
            stopSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()

            StepVerifier.create(termination)
                .expectError(TimeoutException::class.java)
                .verify()

            cancellationEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            forceInvocations.get().assert().isEqualTo(1)
        } finally {
            releaseCancellation.countDown()
        }
    }

    private class RecordingLifecycle(
        private val name: String,
        private val calls: MutableList<String>,
        private val prepareFailure: RuntimeException? = null,
        private val startFailure: RuntimeException? = null,
        private val stopFailure: RuntimeException? = null,
        private val stopGate: Sinks.Empty<Void>? = null,
        private val forceFailure: RuntimeException? = null,
        private val beforeForceFailure: (() -> Unit)? = null,
        private val onPrepare: ((RuntimeContext) -> Unit)? = null,
        private val onStart: ((RuntimeContext) -> Unit)? = null,
        private val onQuiesce: (() -> Unit)? = null,
        private val onStop: (() -> Unit)? = null,
    ) : RuntimeComponent {
        var runtimeContext: RuntimeContext? = null
        override fun prepare(runtimeContext: RuntimeContext): Mono<Void> =
            Mono.fromRunnable {
                this.runtimeContext = runtimeContext
                calls += "prepare:$name"
                onPrepare?.invoke(runtimeContext)
                prepareFailure?.let { throw it }
            }

        override fun start() {
            calls += "start:$name"
            onStart?.invoke(checkNotNull(runtimeContext))
            startFailure?.let { throw it }
        }

        override fun quiesce() {
            onQuiesce?.invoke()
        }

        override fun stopGracefully(): Mono<Void> =
            Mono.defer {
                calls += "stop:$name"
                onStop?.invoke()
                stopFailure?.let { return@defer Mono.error(it) }
                stopGate?.asMono() ?: Mono.empty()
            }

        override fun forceStop() {
            calls += "force:$name"
            beforeForceFailure?.invoke()
            stopGate?.tryEmitEmpty()
            forceFailure?.let { throw it }
        }
    }

    private fun immediateExecutionResources(
        dispatcher: TerminalSignalDispatcher,
        controlDispatcher: TerminalSignalDispatcher = dispatcher,
    ): RuntimeExecutionResources =
        object : RuntimeExecutionResources {
            override val terminationDispatcher: TerminalSignalDispatcher = dispatcher
            override val terminationControlDispatcher: TerminalSignalDispatcher =
                controlDispatcher
            override val shutdownScheduler: Scheduler = Schedulers.immediate()
            override val quiescenceScheduler: Scheduler = Schedulers.immediate()

            override fun dispatchCleanup(action: Runnable): Boolean {
                action.run()
                return true
            }
        }

    private fun WowRuntime.awaitStartFailure(): Throwable {
        val signal = checkNotNull(start().materialize().block())
        check(signal.isOnError) {
            "Runtime startup unexpectedly completed."
        }
        return checkNotNull(signal.throwable)
    }

    private fun WowRuntime.startAsync(executor: Executor): CompletableFuture<Throwable> {
        val result = CompletableFuture<Throwable>()
        executor.execute {
            try {
                start().subscribe(
                    {},
                    result::complete,
                    {
                        result.complete(
                            AssertionError("Runtime startup unexpectedly completed."),
                        )
                    },
                )
            } catch (error: Throwable) {
                result.complete(error)
            }
        }
        return result
    }

    private fun List<String>.awaitContains(expected: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (expected !in this && System.nanoTime() < deadline) {
            Thread.onSpinWait()
        }
        this.assert().contains(expected)
    }

    private fun awaitIgnoringInterrupt(latch: CountDownLatch) {
        while (true) {
            try {
                latch.await()
                return
            } catch (_: InterruptedException) {
                // A third-party cancellation hook may ignore interruption.
            }
        }
    }
}

private class RejectingScheduler(
    private val schedulingFailure: RejectedExecutionException,
) : Scheduler by Schedulers.immediate() {
    override fun schedule(task: Runnable): Disposable {
        throw schedulingFailure
    }

    override fun schedule(
        task: Runnable,
        delay: Long,
        unit: TimeUnit,
    ): Disposable {
        throw schedulingFailure
    }
}

private class ControllableDeadlineScheduler : Scheduler by Schedulers.immediate() {
    private val scheduledTask = AtomicReference<Runnable?>()

    override fun schedule(
        task: Runnable,
        delay: Long,
        unit: TimeUnit,
    ): Disposable {
        check(scheduledTask.compareAndSet(null, task))
        return Disposable {
            scheduledTask.compareAndSet(task, null)
        }
    }

    fun runScheduled() {
        checkNotNull(scheduledTask.getAndSet(null)).run()
    }
}

private class InlineDeadlineScheduler : Scheduler by Schedulers.immediate() {
    val deadlineTaskDisposed = AtomicBoolean()

    override fun schedule(
        task: Runnable,
        delay: Long,
        unit: TimeUnit,
    ): Disposable {
        task.run()
        return Disposable {
            deadlineTaskDisposed.set(true)
        }
    }
}

private class BlockingScheduleScheduler(
    private val delegate: Scheduler,
) : Scheduler by delegate {
    val scheduleStarted = CountDownLatch(1)
    val allowScheduleReturn = CountDownLatch(1)
    val schedulingThreadName = AtomicReference<String>()

    override fun schedule(
        task: Runnable,
        delay: Long,
        unit: TimeUnit,
    ): Disposable {
        val scheduled = delegate.schedule(task, delay, unit)
        schedulingThreadName.set(Thread.currentThread().name)
        scheduleStarted.countDown()
        while (true) {
            try {
                allowScheduleReturn.await()
                return scheduled
            } catch (_: InterruptedException) {
                // The runtime deadline must remain enforceable even when a
                // third-party scheduler ignores cancellation interruption.
            }
        }
    }
}
