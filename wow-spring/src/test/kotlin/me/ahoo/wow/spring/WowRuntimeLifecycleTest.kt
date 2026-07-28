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

package me.ahoo.wow.spring

import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.RuntimeComponent
import me.ahoo.wow.runtime.RuntimeContext
import me.ahoo.wow.runtime.RuntimeOwnershipClaim
import me.ahoo.wow.runtime.WowRuntime
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class WowRuntimeLifecycleTest {

    @Test
    fun `public termination observers cannot starve lifecycle control plane`() {
        val observerWorkers = 8
        val observersEntered = CountDownLatch(observerWorkers)
        val releaseObservers = CountDownLatch(1)
        val blockerRuntime = WowRuntime(emptyList(), Duration.ofSeconds(1), Duration.ZERO)
        blockerRuntime.start()
        repeat(observerWorkers) {
            blockerRuntime.terminationSignal.subscribe(
                {},
                {},
                {
                    observersEntered.countDown()
                    releaseObservers.await()
                },
            )
        }
        blockerRuntime.forceStop()
        observersEntered.await(1, TimeUnit.SECONDS).assert().isTrue()

        val stopGate = Sinks.empty<Void>()
        val controlledRuntime = WowRuntime(
            listOf(RecordingLifecycle(stopGate)),
            Duration.ofSeconds(1),
            Duration.ZERO,
        )
        val lifecycle = WowRuntimeLifecycle(controlledRuntime)
        val stopped = CountDownLatch(1)
        try {
            lifecycle.start()
            lifecycle.stop(stopped::countDown)
            stopGate.tryEmitEmpty().orThrow()

            stopped.await(1, TimeUnit.SECONDS).assert().isTrue()
        } finally {
            releaseObservers.countDown()
            controlledRuntime.forceStop()
        }
    }

    @Test
    fun `lifecycle delegates to runtime and completes callback asynchronously`() {
        val stopGate = Sinks.empty<Void>()
        val component = RecordingLifecycle(stopGate)
        val runtime = WowRuntime(listOf(component), Duration.ofSeconds(1), Duration.ZERO)
        val lifecycle = WowRuntimeLifecycle(runtime)
        val stopped = CountDownLatch(1)

        lifecycle.start()
        lifecycle.start()
        lifecycle.isRunning.assert().isTrue()
        component.startCount.get().assert().isEqualTo(1)
        lifecycle.stop(stopped::countDown)
        stopped.await(100, TimeUnit.MILLISECONDS).assert().isFalse()

        stopGate.tryEmitEmpty().orThrow()

        stopped.await(1, TimeUnit.SECONDS).assert().isTrue()
        lifecycle.isRunning.assert().isFalse()
        lifecycle.isPauseable.assert().isFalse()
        lifecycle.phase.assert().isEqualTo(WOW_RUNTIME_PHASE)
    }

    @Test
    fun `lifecycle rejects restart after shutdown begins`() {
        val stopGate = Sinks.empty<Void>()
        val runtime = WowRuntime(
            listOf(RecordingLifecycle(stopGate)),
            Duration.ofSeconds(1),
            Duration.ZERO,
        )
        val lifecycle = WowRuntimeLifecycle(runtime)
        lifecycle.start()
        lifecycle.stop {}

        val error = assertThrows<IllegalStateException>(lifecycle::start)

        error.message.assert().contains("Create a new ApplicationContext")
        stopGate.tryEmitEmpty().orThrow()
    }

    @Test
    fun `lifecycle retains stop callback while runtime is starting`() {
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val component = object : RuntimeComponent {
            override fun claimRuntimeOwnership(): RuntimeOwnershipClaim =
                RuntimeOwnershipClaim.shared(this)

            override fun prepare(runtimeContext: RuntimeContext) = Unit

            override fun start() {
                startEntered.countDown()
                releaseStart.await()
            }

            override fun stopGracefully(): Mono<Void> = Mono.empty()

            override fun forceStop() {
                releaseStart.countDown()
            }
        }
        val runtime = WowRuntime(listOf(component), Duration.ofSeconds(1), Duration.ZERO)
        val lifecycle = WowRuntimeLifecycle(runtime)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val startup = CompletableFuture.supplyAsync(
                { runCatching(lifecycle::start).exceptionOrNull() },
                executor,
            )
            startEntered.await(1, TimeUnit.SECONDS).assert().isTrue()

            lifecycle.stop(stopped::countDown)
            releaseStart.countDown()

            startup.get(1, TimeUnit.SECONDS).assert().isNotNull()
            stopped.await(1, TimeUnit.SECONDS).assert().isTrue()
            lifecycle.isRunning.assert().isFalse()
        } finally {
            releaseStart.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `unexpected runtime failure dispatches fail-fast callback asynchronously exactly once`() {
        val stopGate = Sinks.empty<Void>()
        val component = RecordingLifecycle(stopGate)
        val runtime = WowRuntime(listOf(component), Duration.ofSeconds(1), Duration.ZERO)
        val executor = ManualExecutor()
        val reportedFailure = AtomicReference<Throwable?>()
        val reportCount = AtomicInteger()
        var forceStopReturned = false
        val lifecycle = WowRuntimeLifecycle(runtime, executor) { error ->
            forceStopReturned.assert().isTrue()
            reportedFailure.set(error)
            reportCount.incrementAndGet()
        }
        lifecycle.start()

        runtime.forceStop()
        forceStopReturned = true
        runtime.forceStop()

        executor.awaitPendingTask()
        reportCount.get().assert().isZero()
        executor.pendingTaskCount.assert().isEqualTo(1)
        executor.runAll()

        reportCount.get().assert().isEqualTo(1)
        reportedFailure.get().assert()
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Wow runtime terminated unexpectedly.")
        lifecycle.isRunning.assert().isFalse()
    }

    @Test
    fun `rejected termination dispatch falls back without losing callback`() {
        val stopGate = Sinks.empty<Void>()
        val runtime = WowRuntime(
            listOf(RecordingLifecycle(stopGate)),
            Duration.ofSeconds(1),
            Duration.ZERO,
        )
        val reportCount = AtomicInteger()
        val reported = CountDownLatch(1)
        val lifecycle = WowRuntimeLifecycle(runtime, RejectingExecutor) {
            reportCount.incrementAndGet()
            reported.countDown()
        }
        lifecycle.start()

        runtime.forceStop()
        runtime.forceStop()

        reported.await(1, TimeUnit.SECONDS).assert().isTrue()
        reportCount.get().assert().isEqualTo(1)
    }

    private class RecordingLifecycle(
        private val stopGate: Sinks.Empty<Void>,
    ) : RuntimeComponent {
        val startCount = AtomicInteger()

        override fun claimRuntimeOwnership(): RuntimeOwnershipClaim =
            RuntimeOwnershipClaim.shared(this)

        override fun prepare(runtimeContext: RuntimeContext) = Unit

        override fun start() {
            startCount.incrementAndGet()
        }

        override fun stopGracefully(): Mono<Void> = stopGate.asMono()

        override fun forceStop() {
            stopGate.tryEmitEmpty().orThrow()
        }
    }

    private class ManualExecutor : Executor {
        private val tasks = ConcurrentLinkedQueue<Runnable>()
        private val taskAdded = CountDownLatch(1)

        val pendingTaskCount: Int
            get() = tasks.size

        override fun execute(command: Runnable) {
            tasks.add(command)
            taskAdded.countDown()
        }

        fun awaitPendingTask() {
            taskAdded.await(1, TimeUnit.SECONDS).assert().isTrue()
        }

        fun runAll() {
            while (true) {
                val task = tasks.poll() ?: return
                task.run()
            }
        }
    }

    private object RejectingExecutor : Executor {
        override fun execute(command: Runnable) {
            throw RejectedExecutionException("rejected-for-test")
        }
    }
}
