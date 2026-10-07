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
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.kotlin.test.test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class BatchCoordinatorForceStopTest {
    @Test
    fun `force stop fails a hanging graceful stop and its pending callers without waiting`() {
        val writerSubscribed = CountDownLatch(1)
        val coordinator = coordinator(maxPendingItems = 2) {
            writerSubscribed.countDown()
            Mono.never()
        }
        // Two items fill the window, so the writer starts and hangs.
        val pending = coordinator.submit(1).materialize().toFuture()
        val second = coordinator.submit(2).materialize().toFuture()
        writerSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()
        val graceful = coordinator.stopGracefully().materialize().toFuture()

        coordinator.forceStop()

        pending.get(1, TimeUnit.SECONDS)!!.throwable.assert().isInstanceOf(BatchClosedException::class.java)
        second.get(1, TimeUnit.SECONDS)!!.throwable.assert().isInstanceOf(BatchClosedException::class.java)
        graceful.get(1, TimeUnit.SECONDS)!!.throwable.assert().isInstanceOf(BatchClosedException::class.java)
        coordinator.submit(3).test().expectError(BatchClosedException::class.java).verify()
    }

    @Test
    fun `close after a force stop returns quietly, every time`() {
        val writerSubscribed = CountDownLatch(1)
        val coordinator = coordinator(maxPendingItems = 2) {
            writerSubscribed.countDown()
            Mono.never()
        }
        val pending = coordinator.submit(1).materialize().toFuture()
        coordinator.submit(2).subscribe({}, {})
        writerSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()

        coordinator.forceStop()
        coordinator.forceStop()

        // Spring closes the store and the appender it wraps: the same coordinator twice.
        coordinator.close()
        coordinator.close()
        coordinator.stop()
        pending.get(1, TimeUnit.SECONDS)!!.throwable.assert().isInstanceOf(BatchClosedException::class.java)
    }

    @Test
    fun `close after another failure still throws it`() {
        val coordinator = coordinator(maxPendingItems = 2) { Mono.never() }
        coordinator.submit(1).subscribe({}, {})
        coordinator.submit(2).subscribe({}, {})

        assertThrows<BatchCloseTimeoutException> { coordinator.close(Duration.ofMillis(50)) }
        coordinator.forceStop()

        assertThrows<BatchCloseTimeoutException> { coordinator.close() }
    }

    @Test
    fun `force stop after a completed graceful stop changes nothing`() {
        val coordinator = coordinator { items -> Mono.just(items.map { BatchItemResult.Success }) }
        val result = coordinator.submit(1).materialize().toFuture()
        coordinator.stopGracefully().test().verifyComplete()

        coordinator.forceStop()

        result.get(1, TimeUnit.SECONDS)!!.isOnComplete.assert().isTrue()
        coordinator.close()
    }

    @Test
    fun `force stop fails an admission that was in progress when the failure was installed`() {
        val firstWriteStarted = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        // Two items fill the first window. Its write runs inline on the single window thread and blocks it there, so
        // the lane cancellation that a force stop schedules on that thread cannot run before the test releases it.
        val coordinator = coordinator { items ->
            if (1 in items) {
                firstWriteStarted.countDown()
                releaseFirstWrite.await(10, TimeUnit.SECONDS)
            }
            Mono.just(items.map { BatchItemResult.Success })
        }
        try {
            val first = coordinator.submit(1).materialize().toFuture()
            val second = coordinator.submit(2).materialize().toFuture()
            firstWriteStarted.await(1, TimeUnit.SECONDS).assert().isTrue()

            val admissionEntered = CountDownLatch(1)
            val releaseAdmission = CountDownLatch(1)
            coordinator.admissionProbe = {
                admissionEntered.countDown()
                releaseAdmission.await(10, TimeUnit.SECONDS)
            }
            // The admission sees the lifecycle open and then blocks while it holds its lane's admission lock.
            val inProgress = coordinator.submit(3).materialize()
                .subscribeOn(Schedulers.boundedElastic())
                .toFuture()
            admissionEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            coordinator.admissionProbe = null

            val forceStop = thread(name = "force-stop") { coordinator.forceStop() }
            // With the fence, the force stop waits on the lane lock; without it, it finishes first.
            awaitBlockedOrTerminated(forceStop)
            // The failure is installed before the fence: a new submit fails at once instead of queueing on the lane.
            coordinator.submit(4).subscribeOn(Schedulers.boundedElastic()).test()
                .expectError(BatchClosedException::class.java)
                .verify(Duration.ofSeconds(1))

            releaseAdmission.countDown()
            forceStop.join(1000)
            forceStop.isAlive.assert().isFalse()

            inProgress.get(1, TimeUnit.SECONDS)!!.throwable.assert().isInstanceOf(BatchClosedException::class.java)
            first.get(1, TimeUnit.SECONDS)!!.throwable.assert().isInstanceOf(BatchClosedException::class.java)
            second.get(1, TimeUnit.SECONDS)!!.throwable.assert().isInstanceOf(BatchClosedException::class.java)
        } finally {
            releaseFirstWrite.countDown()
        }
    }

    private fun awaitBlockedOrTerminated(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (thread.state != Thread.State.BLOCKED && thread.state != Thread.State.TERMINATED) {
            check(System.nanoTime() < deadline) { "${thread.name} neither blocked nor finished: ${thread.state}" }
            Thread.onSpinWait()
        }
    }
}
