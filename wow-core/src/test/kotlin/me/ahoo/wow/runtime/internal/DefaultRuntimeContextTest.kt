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
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    fun `force close wins the quiet boundary and completes quiescence`() {
        val scheduler = VirtualTimeScheduler.create()
        val context = DefaultRuntimeContext(Duration.ofSeconds(1), scheduler)
        val quiescence = context.quiesce()

        context.forceClose()

        context.isAdmissionClosed.assert().isTrue()
        context.tryAcquire().assert().isNull()
        StepVerifier.create(quiescence)
            .then { scheduler.advanceTimeBy(Duration.ofNanos(1)) }
            .verifyComplete()
        scheduler.advanceTimeBy(Duration.ofSeconds(1))
    }

    @Test
    fun `fatal close rejects new activity and drains admitted work without a quiet period`() {
        val scheduler = VirtualTimeScheduler.create()
        val context = DefaultRuntimeContext(Duration.ofSeconds(1), scheduler)
        val activity = context.tryAcquire()!!
        val quiescence = context.quiesce()
        val admissionClosed = context.admissionClosed().toFuture()

        context.closeAdmissionAndDrain()

        admissionClosed.get(1, TimeUnit.SECONDS)
        context.isAdmissionClosed.assert().isTrue()
        context.tryAcquire().assert().isNull()
        StepVerifier.create(quiescence)
            .expectSubscription()
            .expectNoEvent(Duration.ofMillis(100))
            .then(activity::close)
            .verifyComplete()
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
    fun `reported failures are delegated to the runtime owner`() {
        val firstFailure = IllegalStateException("first")
        val secondFailure = IllegalArgumentException("second")
        val reportedFailures = mutableListOf<Throwable>()
        val context = DefaultRuntimeContext(failureHandler = { reportedFailures += it })

        context.reportFailure(firstFailure)
        context.reportFailure(secondFailure)

        reportedFailures.assert().containsExactly(firstFailure, secondFailure)
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
}
