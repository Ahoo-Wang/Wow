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
import reactor.core.Disposable
import reactor.core.scheduler.Scheduler
import reactor.test.StepVerifier
import reactor.test.scheduler.VirtualTimeScheduler
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DefaultRuntimeContextTest {

    @Test
    fun `quiet period must fit in nanoseconds`() {
        assertThrows<IllegalArgumentException> {
            DefaultRuntimeContext(Duration.ofSeconds(Long.MAX_VALUE))
        }
            .message
            .assert()
            .contains("shutdownQuietPeriod must fit in nanoseconds")
    }

    @Test
    fun `quiesce waits for the complete active operation chain`() {
        val context = DefaultRuntimeContext()

        val firstActivity = context.tryAcquire()
        firstActivity.assert().isNotNull()
        val quiescence = context.quiesce()

        StepVerifier.create(quiescence)
            .expectSubscription()
            .then {
                val chainedActivity = context.tryAcquire()
                chainedActivity.assert().isNotNull()
                chainedActivity!!.close()
            }
            .expectNoEvent(Duration.ofMillis(100))
            .then { firstActivity!!.close() }
            .verifyComplete()

        context.activeOperationCount.assert().isZero()
        context.tryAcquire().assert().isNull()
    }

    @Test
    fun `quiesce closes admission immediately when runtime is idle`() {
        val context = DefaultRuntimeContext()

        StepVerifier.create(context.quiesce())
            .verifyComplete()

        context.tryAcquire().assert().isNull()
    }

    @Test
    fun `quiesce requires a complete quiet period when runtime is already idle`() {
        val scheduler = VirtualTimeScheduler.create()
        val context = DefaultRuntimeContext(Duration.ofSeconds(1), scheduler)

        StepVerifier.create(context.quiesce())
            .expectSubscription()
            .then { scheduler.advanceTimeBy(Duration.ofMillis(999)) }
            .then { context.isAdmissionClosed.assert().isFalse() }
            .then { scheduler.advanceTimeBy(Duration.ofMillis(1)) }
            .verifyComplete()

        context.isAdmissionClosed.assert().isTrue()
    }

    @Test
    fun `new activity resets the shutdown quiet period`() {
        val scheduler = VirtualTimeScheduler.create()
        val context = DefaultRuntimeContext(Duration.ofSeconds(1), scheduler)
        context.tryAcquire()!!.close()

        StepVerifier.create(context.quiesce())
            .expectSubscription()
            .then { scheduler.advanceTimeBy(Duration.ofMillis(500)) }
            .then {
                val activity = context.tryAcquire()
                activity.assert().isNotNull()
                activity!!.close()
            }
            .then { scheduler.advanceTimeBy(Duration.ofMillis(500)) }
            .then { context.isAdmissionClosed.assert().isFalse() }
            .then { scheduler.advanceTimeBy(Duration.ofMillis(500)) }
            .verifyComplete()

        context.isAdmissionClosed.assert().isTrue()
        context.tryAcquire().assert().isNull()
    }

    @Test
    fun `new activity invalidates a quiet timer whose publication is delayed`() {
        val virtualTimeScheduler = VirtualTimeScheduler.create()
        val scheduler = BlockingFirstScheduleScheduler(virtualTimeScheduler)
        val context = DefaultRuntimeContext(Duration.ofSeconds(1), scheduler)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val quiescence = CompletableFuture.supplyAsync(context::quiesce, executor)
            scheduler.firstScheduleStarted.await(1, TimeUnit.SECONDS).assert().isTrue()
            val activity = CompletableFuture.runAsync(
                {
                    val acquired = context.tryAcquire()
                    acquired.assert().isNotNull()
                    acquired!!.close()
                },
                executor,
            )

            activity.get(1, TimeUnit.SECONDS)
            virtualTimeScheduler.advanceTimeBy(Duration.ofMillis(999))
            context.isAdmissionClosed.assert().isFalse()

            scheduler.allowFirstScheduleReturn.countDown()
            val quiescentSignal = quiescence.get(1, TimeUnit.SECONDS)
            StepVerifier.create(quiescentSignal)
                .then { virtualTimeScheduler.advanceTimeBy(Duration.ofMillis(1)) }
                .verifyComplete()
        } finally {
            scheduler.allowFirstScheduleReturn.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `quiescence closes every registered intake`() {
        val context = DefaultRuntimeContext()
        var closedIntakeCount = 0
        context.onAdmissionClose {
            closedIntakeCount++
        }

        StepVerifier.create(context.quiesce()).verifyComplete()

        closedIntakeCount.assert().isEqualTo(1)
    }

    @Test
    fun `admission is closed before intake close actions run`() {
        val context = DefaultRuntimeContext()
        val closeActionStarted = CountDownLatch(1)
        val allowCloseAction = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        context.onAdmissionClose {
            closeActionStarted.countDown()
            allowCloseAction.await()
        }

        try {
            val shutdown = CompletableFuture.runAsync(
                { context.quiesce().block() },
                executor,
            )
            closeActionStarted.await(1, TimeUnit.SECONDS).assert().isTrue()

            context.isAdmissionClosed.assert().isTrue()
            context.tryAcquire().assert().isNull()

            allowCloseAction.countDown()
            shutdown.get(1, TimeUnit.SECONDS)
        } finally {
            allowCloseAction.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `close action registered concurrently with closing runs exactly once`() {
        val context = DefaultRuntimeContext()
        val firstActionStarted = CountDownLatch(1)
        val allowFirstAction = CountDownLatch(1)
        val secondActionInvocations = AtomicInteger()
        val secondActionInvoked = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        context.onAdmissionClose {
            firstActionStarted.countDown()
            allowFirstAction.await()
        }

        try {
            val shutdown = CompletableFuture.runAsync(
                { context.quiesce().block() },
                executor,
            )
            firstActionStarted.await(1, TimeUnit.SECONDS).assert().isTrue()

            context.onAdmissionClose {
                secondActionInvocations.incrementAndGet()
                secondActionInvoked.countDown()
            }

            secondActionInvoked.await(1, TimeUnit.SECONDS).assert().isTrue()
            secondActionInvocations.get().assert().isEqualTo(1)
            allowFirstAction.countDown()
            shutdown.get(1, TimeUnit.SECONDS)
            secondActionInvocations.get().assert().isEqualTo(1)
        } finally {
            allowFirstAction.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `close action registered after closure is dispatched off the caller`() {
        val closeExecutor = Executors.newSingleThreadExecutor()
        val registrationExecutor = Executors.newSingleThreadExecutor()
        val context = DefaultRuntimeContext(closeExecutor = closeExecutor)
        val closeActionStarted = CountDownLatch(1)
        val allowCloseAction = CountDownLatch(1)

        try {
            context.quiesce().block()

            val registration = CompletableFuture.runAsync(
                {
                    context.onAdmissionClose {
                        closeActionStarted.countDown()
                        allowCloseAction.await()
                    }
                },
                registrationExecutor,
            )

            registration.get(1, TimeUnit.SECONDS)
            closeActionStarted.await(1, TimeUnit.SECONDS).assert().isTrue()
        } finally {
            allowCloseAction.countDown()
            closeExecutor.shutdownNow()
            registrationExecutor.shutdownNow()
        }
    }

    @Test
    fun `blocked close actions are isolated to their own runtime context`() {
        val blockedContextCount = 4
        val blockedActionsEntered = CountDownLatch(blockedContextCount)
        val releaseBlockedActions = CountDownLatch(1)
        val blockedContexts = List(blockedContextCount) {
            DefaultRuntimeContext().also { context ->
                context.onAdmissionClose {
                    blockedActionsEntered.countDown()
                    awaitIgnoringInterrupt(releaseBlockedActions)
                }
                context.quiesce()
            }
        }
        val healthyActionInvoked = CountDownLatch(1)
        val healthyContext = DefaultRuntimeContext().also { context ->
            context.onAdmissionClose(healthyActionInvoked::countDown)
        }

        try {
            blockedActionsEntered.await(1, TimeUnit.SECONDS).assert().isTrue()

            val healthyTermination = healthyContext.quiesce().toFuture()

            healthyActionInvoked.await(1, TimeUnit.SECONDS).assert().isTrue()
            healthyTermination.get(1, TimeUnit.SECONDS)
        } finally {
            releaseBlockedActions.countDown()
            blockedContexts.forEach { context ->
                context.quiesce().block(Duration.ofSeconds(1))
            }
        }
    }

    @Test
    fun `process cleanup executor bounds blocked runtimes and releases cancelled queue entries`() {
        val contextCount = RuntimeCleanupExecutor.THREAD_CAPACITY * 3
        val blockedActionsEntered = CountDownLatch(RuntimeCleanupExecutor.THREAD_CAPACITY)
        val releaseBlockedActions = CountDownLatch(1)
        val initialQueueSize = RuntimeCleanupExecutor.queuedTaskCount
        val contexts = List(contextCount) {
            DefaultRuntimeContext().also { context ->
                context.onAdmissionClose {
                    blockedActionsEntered.countDown()
                    awaitIgnoringInterrupt(releaseBlockedActions)
                }
            }
        }

        try {
            contexts.forEach(DefaultRuntimeContext::quiesce)
            blockedActionsEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            val expectedQueuedTaskCount =
                initialQueueSize + contextCount - RuntimeCleanupExecutor.THREAD_CAPACITY
            val queueFillDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (
                RuntimeCleanupExecutor.queuedTaskCount < expectedQueuedTaskCount &&
                System.nanoTime() < queueFillDeadline
            ) {
                Thread.onSpinWait()
            }
            RuntimeCleanupExecutor.queuedTaskCount.assert().isGreaterThanOrEqualTo(expectedQueuedTaskCount)

            contexts.forEach(DefaultRuntimeContext::forceClose)

            val queueDrainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (
                RuntimeCleanupExecutor.queuedTaskCount > initialQueueSize &&
                System.nanoTime() < queueDrainDeadline
            ) {
                Thread.onSpinWait()
            }
            RuntimeCleanupExecutor.queuedTaskCount.assert().isLessThanOrEqualTo(initialQueueSize)
            Thread.getAllStackTraces()
                .keys
                .count { thread ->
                    thread.isAlive && thread.name.startsWith("wow-runtime-cleanup-")
                }.assert()
                .isLessThanOrEqualTo(RuntimeCleanupExecutor.THREAD_CAPACITY)
        } finally {
            releaseBlockedActions.countDown()
        }
    }

    @Test
    fun `force close cancels a previously queued late close action`() {
        val closeExecutor = Executors.newSingleThreadExecutor()
        val firstActionEntered = CountDownLatch(1)
        val releaseFirstAction = CountDownLatch(1)
        val lateActionInvocations = AtomicInteger()
        val context = DefaultRuntimeContext(closeExecutor = closeExecutor)
        context.onAdmissionClose {
            firstActionEntered.countDown()
            awaitIgnoringInterrupt(releaseFirstAction)
        }

        try {
            val termination = context.quiesce().toFuture()
            firstActionEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            context.onAdmissionClose(lateActionInvocations::incrementAndGet)

            context.forceClose()
            releaseFirstAction.countDown()

            termination.get(1, TimeUnit.SECONDS)
            lateActionInvocations.get().assert().isZero()
        } finally {
            releaseFirstAction.countDown()
            closeExecutor.shutdownNow()
        }
    }

    @Test
    fun `force close wins the quiet boundary and runs close actions exactly once`() {
        val scheduler = VirtualTimeScheduler.create()
        val context = DefaultRuntimeContext(Duration.ofSeconds(1), scheduler)
        val closeActionInvocations = AtomicInteger()
        context.onAdmissionClose(closeActionInvocations::incrementAndGet)
        val quiescence = context.quiesce()

        context.forceClose()

        context.isAdmissionClosed.assert().isTrue()
        context.tryAcquire().assert().isNull()
        StepVerifier.create(quiescence)
            .then { scheduler.advanceTimeBy(Duration.ofNanos(1)) }
            .verifyComplete()
        scheduler.advanceTimeBy(Duration.ofSeconds(1))
        closeActionInvocations.get().assert().isEqualTo(1)
    }

    @Test
    fun `activity lease closes exactly once`() {
        val context = DefaultRuntimeContext()
        val activity = context.tryAcquire()!!

        activity.close()
        activity.close()

        context.activeOperationCount.assert().isZero()
        StepVerifier.create(context.quiesce()).verifyComplete()
    }

    @Test
    fun `quiet period scheduling failure fails quiescence`() {
        val schedulingFailure = RejectedExecutionException("rejected")
        val scheduler = object : Scheduler by VirtualTimeScheduler.create() {
            override fun schedule(
                task: Runnable,
                delay: Long,
                unit: TimeUnit,
            ): Disposable {
                throw schedulingFailure
            }
        }
        val context = DefaultRuntimeContext(Duration.ofSeconds(1), scheduler)

        StepVerifier.create(context.quiesce())
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(schedulingFailure)
            }
            .verify()
    }

    @Test
    fun `close executor rejection fails quiescence`() {
        val closeFailure = RejectedExecutionException("close-rejected")
        val context = DefaultRuntimeContext(
            closeExecutor = Executor {
                throw closeFailure
            },
        )
        context.onAdmissionClose {}

        StepVerifier.create(context.quiesce())
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(closeFailure)
            }
            .verify(Duration.ofSeconds(1))
    }

    @Test
    fun `late close action failure is reported after quiescence`() {
        val closeFailure = IllegalStateException("late-close")
        val context = DefaultRuntimeContext(
            closeExecutor = Executor(Runnable::run),
        )
        context.quiesce().block(Duration.ofSeconds(1))

        StepVerifier.create(context.failureSignal)
            .then {
                context.onAdmissionClose {
                    throw closeFailure
                }
            }
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(closeFailure)
            }
            .verify(Duration.ofSeconds(1))
    }

    @Test
    fun `subsequent reported failures are suppressed by the first failure`() {
        val context = DefaultRuntimeContext()
        val firstFailure = IllegalStateException("first")
        val secondFailure = IllegalArgumentException("second")
        val thirdFailure = UnsupportedOperationException("third")

        context.reportFailure(firstFailure)
        context.reportFailure(secondFailure)
        context.reportFailure(thirdFailure)

        firstFailure.suppressedExceptions.assert().containsExactly(secondFailure, thirdFailure)
    }

    @Test
    fun `concurrent duplicate failures are suppressed only once`() {
        val context = DefaultRuntimeContext()
        val firstFailure = IllegalStateException("first")
        val duplicateFailure = IllegalArgumentException("duplicate")
        val executor = Executors.newFixedThreadPool(8)
        context.reportFailure(firstFailure)

        try {
            val reporters = (1..32).map {
                CompletableFuture.runAsync(
                    { context.reportFailure(duplicateFailure) },
                    executor,
                )
            }
            CompletableFuture.allOf(*reporters.toTypedArray()).get(1, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        firstFailure.suppressedExceptions.assert().containsExactly(duplicateFailure)
    }

    private class BlockingFirstScheduleScheduler(
        private val delegate: Scheduler,
    ) : Scheduler by delegate {
        val firstScheduleStarted = CountDownLatch(1)
        val allowFirstScheduleReturn = CountDownLatch(1)
        private val firstSchedule = AtomicBoolean(true)

        override fun schedule(
            task: Runnable,
            delay: Long,
            unit: TimeUnit,
        ): Disposable {
            val scheduledTask = delegate.schedule(task, delay, unit)
            if (firstSchedule.compareAndSet(true, false)) {
                firstScheduleStarted.countDown()
                allowFirstScheduleReturn.await()
            }
            return scheduledTask
        }
    }

    private fun awaitIgnoringInterrupt(latch: CountDownLatch) {
        while (true) {
            try {
                latch.await()
                return
            } catch (_: InterruptedException) {
                // A broken callback can ignore interruption; isolation must
                // prevent it from exhausting other runtime control lanes.
            }
        }
    }
}
