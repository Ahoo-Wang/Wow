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
import reactor.core.publisher.Mono
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RuntimeLifecyclePoolTest {
    @Test
    fun `a lane whose tasks block cannot occupy another lane's threads`() {
        val pool = RuntimeLifecyclePool("test-lifecycle", 3)
        val observers = pool.lane("test-observer", 2, 4)
        val control = pool.lane("test-control", 1, 0)
        val release = CountDownLatch(1)
        val blocked = CountDownLatch(2)
        try {
            repeat(4) {
                observers.execute {
                    blocked.countDown()
                    release.await()
                }
            }
            blocked.await(1, TimeUnit.SECONDS).assert().isTrue()

            val controlled = CountDownLatch(1)
            val controlThread = AtomicReference<String>()
            control.execute {
                controlThread.set(Thread.currentThread().name)
                controlled.countDown()
            }

            controlled.await(1, TimeUnit.SECONDS).assert().isTrue()
            controlThread.get().assert().startsWith("test-control-")
        } finally {
            release.countDown()
            observers.dispose()
            control.dispose()
        }
    }

    @Test
    fun `a lane rejects beyond its concurrency and queue and runs its queue in order`() {
        val lane = RuntimeLifecyclePool("test-lifecycle", 1).lane("test-lane", 1, 1)
        val release = CountDownLatch(1)
        val order = mutableListOf<Int>()
        val done = CountDownLatch(2)
        try {
            lane.execute {
                release.await()
                synchronized(order) { order += 1 }
                done.countDown()
            }
            lane.execute {
                synchronized(order) { order += 2 }
                done.countDown()
            }
            assertThrows<RejectedExecutionException> { lane.execute {} }

            release.countDown()
            done.await(1, TimeUnit.SECONDS).assert().isTrue()
            synchronized(order) { order.assert().containsExactly(1, 2) }
        } finally {
            release.countDown()
            lane.dispose()
        }
    }

    @Test
    fun `a cancelled queued task frees its slot and never runs`() {
        val lane = RuntimeLifecyclePool("test-lifecycle", 1).lane("test-lane", 1, 1)
        val release = CountDownLatch(1)
        val cancelledRan = AtomicBoolean()
        val after = CountDownLatch(1)
        try {
            lane.execute { release.await() }
            val cancelled = Runnable { cancelledRan.set(true) }
            lane.execute(cancelled)

            lane.cancel(cancelled).assert().isTrue()
            lane.execute { after.countDown() }
            release.countDown()

            after.await(1, TimeUnit.SECONDS).assert().isTrue()
            cancelledRan.get().assert().isFalse()
        } finally {
            release.countDown()
            lane.dispose()
        }
    }

    @Test
    fun `disposal interrupts running tasks, drops queued ones and rejects later ones`() {
        val lane = RuntimeLifecyclePool("test-lifecycle", 1).lane("test-lane", 1, 1)
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val queuedRan = AtomicBoolean()
        lane.execute {
            started.countDown()
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(10))
            } catch (_: InterruptedException) {
                interrupted.countDown()
            }
        }
        lane.execute { queuedRan.set(true) }
        started.await(1, TimeUnit.SECONDS).assert().isTrue()

        lane.dispose()

        interrupted.await(1, TimeUnit.SECONDS).assert().isTrue()
        assertThrows<RejectedExecutionException> { lane.execute {} }
        Thread.sleep(50)
        queuedRan.get().assert().isFalse()
    }

    @Test
    fun `a full lane that frees a slot is never rejected by the pool`() {
        // A lane frees its slot just before its pool thread returns. A task submitted in that instant must wait for
        // the thread, not be rejected by the pool although its lane has room.
        val lane = RuntimeLifecyclePool("test-lifecycle", 2).lane("test-lane", 2, 0)
        val poolRejections = AtomicInteger()
        try {
            repeat(SUBMISSIONS) {
                var submitted = false
                while (!submitted) {
                    try {
                        lane.execute {}
                        submitted = true
                    } catch (error: RejectedExecutionException) {
                        // The lane itself is full: retry. Any other rejection came from the pool.
                        submitted = error.message?.endsWith("is saturated.") != true
                        if (submitted) {
                            poolRejections.incrementAndGet()
                        }
                    }
                }
            }
        } finally {
            lane.dispose()
        }

        poolRejections.get().assert().isZero()
    }

    @Test
    fun `lanes cannot reserve more threads than the pool has`() {
        val pool = RuntimeLifecyclePool("test-lifecycle", 2)
        pool.lane("first", 2, 0)

        assertThrows<IllegalStateException> { pool.lane("second", 1, 0) }
    }

    @Test
    fun `the default resources run every lifecycle role on one pool and deadlines on one timer`() {
        val shutdownThread = AtomicReference<String>()
        Mono.fromRunnable<Void> { shutdownThread.set(Thread.currentThread().name) }
            .subscribeOn(DefaultRuntimeExecutionResources.shutdownScheduler)
            .block()
        shutdownThread.get().assert().startsWith("wow-runtime-shutdown-")

        val cleaned = CountDownLatch(1)
        val cleanupThread = AtomicReference<String>()
        DefaultRuntimeExecutionResources.dispatchCleanup {
            cleanupThread.set(Thread.currentThread().name)
            cleaned.countDown()
        }.assert().isTrue()
        cleaned.await(1, TimeUnit.SECONDS).assert().isTrue()
        cleanupThread.get().assert().startsWith("wow-runtime-cleanup-")

        val deadlineThread = AtomicReference<String>()
        val fired = CountDownLatch(1)
        DefaultRuntimeExecutionResources.deadlineScheduler.schedule(
            {
                deadlineThread.set(Thread.currentThread().name)
                fired.countDown()
            },
            1,
            TimeUnit.MILLISECONDS,
        )
        fired.await(1, TimeUnit.SECONDS).assert().isTrue()
        deadlineThread.get().assert().startsWith("wow-runtime-deadline-")
    }

    private companion object {
        const val SUBMISSIONS = 100_000
    }
}
