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
import org.junit.jupiter.api.Test
import reactor.core.Disposable
import reactor.core.Disposables
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import reactor.test.scheduler.VirtualTimeScheduler
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class WowRuntimeTest {

    @Test
    fun `start prepares every component before processing opens`() {
        val calls = CopyOnWriteArrayList<String>()
        val first = RecordingComponent("first", calls)
        val second = RecordingComponent("second", calls)
        val runtime = runtimeOf(first, second)

        StepVerifier.create(runtime.start())
            .verifyComplete()

        calls.assert().containsExactly(
            "prepare:first",
            "prepare:second",
            "start:first",
            "start:second",
        )
    }

    @Test
    fun `startup failure rolls prepared components back in reverse order`() {
        val calls = CopyOnWriteArrayList<String>()
        val startFailure = IllegalStateException("start failed")
        val first = RecordingComponent("first", calls)
        val second = RecordingComponent("second", calls, startFailure = startFailure)
        val runtime = runtimeOf(first, second)

        StepVerifier.create(runtime.start())
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(startFailure)
            }
            .verify()

        calls.assert().containsExactly(
            "prepare:first",
            "prepare:second",
            "start:first",
            "start:second",
            "stop:second",
            "stop:first",
        )
    }

    @Test
    fun `prepare failure force stops the failing component and gracefully rolls back prepared components`() {
        val calls = CopyOnWriteArrayList<String>()
        val prepareFailure = IllegalStateException("prepare failed")
        val first = RecordingComponent("first", calls)
        val failing = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext) {
                calls += "prepare:failing"
                throw prepareFailure
            }

            override fun start() = error("A component that failed preparation must not start.")

            override fun stopGracefully(): Mono<Void> =
                Mono.defer {
                    calls += "stop:failing"
                    Mono.never()
                }

            override fun forceStop() {
                calls += "force:failing"
            }
        }
        val runtime = WowRuntime(
            components = listOf(first, failing),
            shutdownTimeout = Duration.ofMillis(100),
            shutdownQuietPeriod = Duration.ZERO,
        )

        StepVerifier.create(runtime.start())
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(prepareFailure)
            }
            .verify(Duration.ofSeconds(1))

        calls.assert().containsExactly(
            "prepare:first",
            "prepare:failing",
            "force:failing",
            "stop:first",
        )
    }

    @Test
    fun `graceful stop requested during preparation waits for preparation to return`() {
        val prepareEntered = CountDownLatch(1)
        val releasePrepare = CountDownLatch(1)
        val preparing = AtomicBoolean()
        val stoppedDuringPrepare = AtomicBoolean()
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext) {
                preparing.set(true)
                prepareEntered.countDown()
                releasePrepare.await()
                preparing.set(false)
            }

            override fun start() = error("Component must not start after stop is requested.")

            override fun stopGracefully(): Mono<Void> =
                Mono.fromRunnable {
                    stoppedDuringPrepare.set(preparing.get())
                }

            override fun forceStop() = Unit
        }
        val runtime = runtimeOf(component)
        val startFuture = runtime.start()
            .subscribeOn(Schedulers.boundedElastic())
            .toFuture()
        val entered = prepareEntered.await(1, TimeUnit.SECONDS)
        if (!entered) {
            releasePrepare.countDown()
        }
        entered.assert().isTrue()

        val stopFuture = runtime.stopGracefully().toFuture()
        stopFuture.isDone.assert().isFalse()
        releasePrepare.countDown()

        stopFuture.get(1, TimeUnit.SECONDS)
        startFuture.get(1, TimeUnit.SECONDS)
        stoppedDuringPrepare.get().assert().isFalse()
    }

    @Test
    fun `shutdown deadline force stops cleanup that blocks its subscription thread`() {
        val forceStopCount = AtomicInteger()
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext) = Unit

            override fun start() = Unit

            override fun stopGracefully(): Mono<Void> =
                Mono.fromRunnable {
                    Thread.sleep(300)
                }

            override fun forceStop() {
                forceStopCount.incrementAndGet()
            }
        }
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofMillis(50),
            shutdownQuietPeriod = Duration.ZERO,
        )
        StepVerifier.create(runtime.start()).verifyComplete()

        StepVerifier.create(runtime.stopGracefully())
            .expectError(TimeoutException::class.java)
            .verify(Duration.ofSeconds(1))

        forceStopCount.get().assert().isEqualTo(1)
    }

    @Test
    fun `shutdown timeout during preparation replays force cleanup after preparation exits`() {
        val prepareEntered = CountDownLatch(1)
        val releasePrepare = CountDownLatch(1)
        val firstForceStop = CountDownLatch(1)
        val resourceOpen = AtomicBoolean()
        val forceStopCount = AtomicInteger()
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext) {
                prepareEntered.countDown()
                releasePrepare.await()
                resourceOpen.set(true)
            }

            override fun start() = error("Component must not start after stop is requested.")

            override fun stopGracefully(): Mono<Void> = Mono.empty()

            override fun forceStop() {
                forceStopCount.incrementAndGet()
                resourceOpen.set(false)
                firstForceStop.countDown()
            }
        }
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofMillis(50),
            shutdownQuietPeriod = Duration.ZERO,
        )
        val startFuture = runtime.start()
            .subscribeOn(Schedulers.boundedElastic())
            .toFuture()
        prepareEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
        val stopFuture = runtime.stopGracefully().toFuture()

        try {
            firstForceStop.await(1, TimeUnit.SECONDS).assert().isTrue()
            stopFuture.isDone.assert().isFalse()
        } finally {
            releasePrepare.countDown()
        }

        assertTimeoutFailure(stopFuture::get)
        assertTimeoutFailure(startFuture::get)
        forceStopCount.get().assert().isEqualTo(2)
        resourceOpen.get().assert().isFalse()
    }

    @Test
    fun `force stop wins while graceful cleanup is queued but not started`() {
        val quiescenceScheduler = QueuedScheduler()
        val deadlineScheduler = VirtualTimeScheduler.create()
        val stopCount = AtomicInteger()
        val forceStopCount = AtomicInteger()
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext) = Unit

            override fun start() = Unit

            override fun stopGracefully(): Mono<Void> =
                Mono.fromRunnable {
                    stopCount.incrementAndGet()
                }

            override fun forceStop() {
                forceStopCount.incrementAndGet()
            }
        }
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofSeconds(10),
            shutdownQuietPeriod = Duration.ZERO,
            quiescenceScheduler = quiescenceScheduler,
            deadlineScheduler = deadlineScheduler,
        )
        try {
            StepVerifier.create(runtime.start()).verifyComplete()
            val stopFuture = runtime.stopGracefully().toFuture()
            quiescenceScheduler.runNext()

            stopCount.get().assert().isEqualTo(0)
            runtime.forceStop()
            quiescenceScheduler.runAll()

            stopFuture.get(1, TimeUnit.SECONDS)
            stopCount.get().assert().isEqualTo(0)
            forceStopCount.get().assert().isEqualTo(1)
        } finally {
            runtime.forceStop()
            quiescenceScheduler.dispose()
            deadlineScheduler.dispose()
        }
    }

    @Test
    fun `graceful stop waits for complete runtime activity then stops in reverse order`() {
        val calls = CopyOnWriteArrayList<String>()
        val first = RecordingComponent("first", calls)
        val second = RecordingComponent("second", calls)
        val runtime = runtimeOf(first, second)
        StepVerifier.create(runtime.start()).verifyComplete()
        val activity = first.runtimeContext.tryAcquire()
        checkNotNull(activity)

        val stopFuture = runtime.stopGracefully().toFuture()

        stopFuture.isDone.assert().isFalse()
        calls.assert().doesNotContain("stop:first", "stop:second")

        activity.close()
        stopFuture.get(2, TimeUnit.SECONDS)

        calls.takeLast(2).assert().containsExactly("stop:second", "stop:first")
        runtime.isRunning.assert().isFalse()
    }

    @Test
    fun `quiet period resets when new runtime activity is admitted`() {
        val calls = CopyOnWriteArrayList<String>()
        val component = RecordingComponent("component", calls)
        val scheduler = VirtualTimeScheduler.create()
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofSeconds(10),
            shutdownQuietPeriod = Duration.ofSeconds(1),
            scheduler = scheduler,
        )
        StepVerifier.create(runtime.start()).verifyComplete()

        val stopFuture = runtime.stopGracefully().toFuture()
        scheduler.advanceTimeBy(Duration.ofMillis(500))
        val activity = component.runtimeContext.tryAcquire()
        checkNotNull(activity)
        scheduler.advanceTimeBy(Duration.ofSeconds(1))

        calls.assert().doesNotContain("stop:component")
        activity.close()
        scheduler.advanceTimeBy(Duration.ofMillis(999))
        calls.assert().doesNotContain("stop:component")
        scheduler.advanceTimeBy(Duration.ofMillis(1))

        stopFuture.isDone.assert().isTrue()
        calls.assert().contains("stop:component")
        component.runtimeContext.tryAcquire().assert().isNull()
    }

    @Test
    fun `shutdown timeout force stops every component in reverse order`() {
        val calls = CopyOnWriteArrayList<String>()
        val first = RecordingComponent("first", calls, gracefulStop = Mono.never())
        val second = RecordingComponent("second", calls, gracefulStop = Mono.never())
        val runtime = WowRuntime(
            components = listOf(first, second),
            shutdownTimeout = Duration.ofMillis(100),
            shutdownQuietPeriod = Duration.ZERO,
        )
        StepVerifier.create(runtime.start()).verifyComplete()

        StepVerifier.create(runtime.stopGracefully())
            .expectError(TimeoutException::class.java)
            .verify(Duration.ofSeconds(2))

        calls.filter { it.startsWith("force:") }
            .assert()
            .containsExactly("force:second", "force:first")
    }

    @Test
    fun `reported component failure terminates the complete runtime`() {
        val calls = CopyOnWriteArrayList<String>()
        val component = RecordingComponent("component", calls)
        val runtime = runtimeOf(component)
        val failure = IllegalStateException("pipeline failed")
        StepVerifier.create(runtime.start()).verifyComplete()

        component.runtimeContext.reportFailure(failure)

        StepVerifier.create(runtime.terminationSignal)
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(failure)
            }
            .verify(Duration.ofSeconds(2))
        calls.assert().contains("stop:component")
    }

    @Test
    fun `reported component failures preserve the first and suppress the rest`() {
        val calls = CopyOnWriteArrayList<String>()
        val component = RecordingComponent("component", calls)
        val scheduler = VirtualTimeScheduler.create()
        val runtime = WowRuntime(
            components = listOf(component),
            shutdownTimeout = Duration.ofSeconds(10),
            shutdownQuietPeriod = Duration.ZERO,
            scheduler = scheduler,
        )
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")
        StepVerifier.create(runtime.start()).verifyComplete()
        val activity = checkNotNull(component.runtimeContext.tryAcquire())

        component.runtimeContext.reportFailure(first)
        component.runtimeContext.reportFailure(second)
        activity.close()
        scheduler.advanceTime()

        StepVerifier.create(runtime.terminationSignal)
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(first)
                error.suppressed.toList().assert().containsExactly(second)
            }
            .verify()
    }

    @Test
    fun `graceful cleanup continues after a component fails`() {
        val calls = CopyOnWriteArrayList<String>()
        val stopFailure = IllegalStateException("stop failed")
        val first = RecordingComponent("first", calls)
        val second = RecordingComponent(
            "second",
            calls,
            gracefulStop = Mono.error(stopFailure),
        )
        val runtime = runtimeOf(first, second)
        StepVerifier.create(runtime.start()).verifyComplete()

        StepVerifier.create(runtime.stopGracefully())
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(stopFailure)
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
    fun `cancelling a stop observer does not cancel runtime owned cleanup`() {
        val calls = CopyOnWriteArrayList<String>()
        val releaseStop = Sinks.empty<Void>()
        val component = RecordingComponent(
            "component",
            calls,
            gracefulStop = releaseStop.asMono(),
        )
        val runtime = runtimeOf(component)
        StepVerifier.create(runtime.start()).verifyComplete()

        val cancelledObserver = runtime.stopGracefully().subscribe()
        cancelledObserver.dispose()
        releaseStop.tryEmitEmpty().orThrow()

        StepVerifier.create(runtime.terminationSignal)
            .verifyComplete()
        calls.count { it == "stop:component" }.assert().isEqualTo(1)
    }

    private fun runtimeOf(vararg components: RuntimeComponent): WowRuntime =
        WowRuntime(
            components = components.toList(),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )

    private fun assertTimeoutFailure(await: () -> Unit) {
        try {
            await()
            error("Expected shutdown timeout.")
        } catch (error: ExecutionException) {
            error.cause.assert().isInstanceOf(TimeoutException::class.java)
        }
    }

    private class RecordingComponent(
        private val name: String,
        private val calls: MutableList<String>,
        private val startFailure: Throwable? = null,
        private val gracefulStop: Mono<Void> = Mono.empty(),
    ) : RuntimeComponent {
        lateinit var runtimeContext: RuntimeContext

        override fun prepare(runtimeContext: RuntimeContext) {
            this.runtimeContext = runtimeContext
            calls += "prepare:$name"
        }

        override fun start() {
            calls += "start:$name"
            startFailure?.let { throw it }
        }

        override fun stopGracefully(): Mono<Void> =
            Mono.defer {
                calls += "stop:$name"
                gracefulStop
            }

        override fun forceStop() {
            calls += "force:$name"
        }
    }

    private class QueuedScheduler : Scheduler {
        private val tasks = ConcurrentLinkedQueue<Runnable>()
        private val disposed = AtomicBoolean()

        override fun schedule(task: Runnable): Disposable {
            if (disposed.get()) {
                return Disposables.disposed()
            }
            val registration = Disposables.single()
            tasks += Runnable {
                if (!disposed.get() && !registration.isDisposed) {
                    task.run()
                }
            }
            return registration
        }

        override fun schedule(task: Runnable, delay: Long, unit: TimeUnit): Disposable =
            schedule(task)

        override fun createWorker(): Scheduler.Worker =
            object : Scheduler.Worker {
                private val workerDisposed = AtomicBoolean()

                override fun schedule(task: Runnable): Disposable {
                    if (workerDisposed.get()) {
                        return Disposables.disposed()
                    }
                    return this@QueuedScheduler.schedule {
                        if (!workerDisposed.get()) {
                            task.run()
                        }
                    }
                }

                override fun schedule(task: Runnable, delay: Long, unit: TimeUnit): Disposable =
                    schedule(task)

                override fun dispose() {
                    workerDisposed.set(true)
                }

                override fun isDisposed(): Boolean = workerDisposed.get()
            }

        fun runNext() {
            checkNotNull(tasks.poll()).run()
        }

        fun runAll() {
            while (true) {
                val task = tasks.poll() ?: return
                task.run()
            }
        }

        override fun dispose() {
            disposed.set(true)
            tasks.clear()
        }

        override fun isDisposed(): Boolean = disposed.get()
    }
}
