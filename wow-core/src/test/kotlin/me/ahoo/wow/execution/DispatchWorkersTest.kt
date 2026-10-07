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

package me.ahoo.wow.execution

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.scheduler.Schedulers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DispatchWorkersTest {

    /**
     * The park/unpark protocol: producers race with workers going idle. A submission that finds its worker parked
     * unparks it; one that arrives while the worker is about to park is seen by the worker's re-check. A lost wake-up
     * would leave a task queued forever and the latch short.
     */
    @Test
    fun `no wake-up is lost when producers race with workers going idle`() {
        val workers = DispatchWorkers(4, "dispatch-workers-race")
        val producers = Executors.newFixedThreadPool(4)
        try {
            repeat(20) { round ->
                val tasks = 4 * 5_000
                val ran = CountDownLatch(tasks)
                repeat(4) { producer ->
                    producers.execute {
                        repeat(tasks / 4) { index ->
                            workers.execute({ ran.countDown() }, producer * 7 + index)
                            // Let workers drain and park now and then, so submissions meet parking workers.
                            if (index % 64 == 0) {
                                Thread.yield()
                            }
                        }
                    }
                }
                ran.await(10, TimeUnit.SECONDS).assert().describedAs("round $round").isTrue()
            }
        } finally {
            producers.shutdownNow()
            workers.close()
        }
    }

    @Test
    fun `one affinity runs its tasks in submission order on one thread`() {
        val workers = DispatchWorkers(3, "dispatch-workers-affinity")
        try {
            val order = CopyOnWriteArrayList<Int>()
            val threads = ConcurrentHashMap.newKeySet<String>()
            val done = CountDownLatch(1_000)
            repeat(1_000) { index ->
                workers.execute({
                    order += index
                    threads += Thread.currentThread().name
                    done.countDown()
                }, 5)
            }
            done.await(5, TimeUnit.SECONDS).assert().isTrue()
            order.assert().isEqualTo((0 until 1_000).toList())
            threads.assert().hasSize(1)
        } finally {
            workers.close()
        }
    }

    @Test
    fun `workers are non-blocking threads, survive a failing task and spread round robin`() {
        val workers = DispatchWorkers(2, "dispatch-workers-misc")
        try {
            val nonBlocking = AtomicInteger()
            val threads = ConcurrentHashMap.newKeySet<String>()
            val done = CountDownLatch(4)
            workers.execute { throw IllegalStateException("failing task") }
            repeat(4) {
                workers.execute {
                    if (Schedulers.isInNonBlockingThread()) {
                        nonBlocking.incrementAndGet()
                    }
                    threads += Thread.currentThread().name
                    done.countDown()
                }
            }
            done.await(5, TimeUnit.SECONDS).assert().isTrue()
            nonBlocking.get().assert().isEqualTo(4)
            threads.assert().hasSize(2)
            // A worker that has just counted down may still be running with an empty queue, and nextAffinity prefers
            // such a worker, so both calls could pick it. With both parked, consecutive calls start one worker apart.
            awaitParked("dispatch-workers-misc", 2)
            (workers.nextAffinity() != workers.nextAffinity()).assert().isTrue()
        } finally {
            workers.close()
        }
    }

    /**
     * `close` racing submissions: every task is either run or rejected. Before the fix a task queued just after its
     * worker saw `closed` with an empty queue (and exited) was neither — its completion never fired.
     */
    @Test
    fun `a task submitted while the workers close is run or rejected, never stranded`() {
        repeat(200) { round ->
            val workers = DispatchWorkers(2, "dispatch-workers-close-race")
            val submitters = Executors.newFixedThreadPool(3)
            val ran = AtomicInteger()
            val rejected = AtomicInteger()
            val submitted = 3 * 200
            val done = CountDownLatch(3)
            repeat(3) { submitter ->
                submitters.execute {
                    repeat(submitted / 3) { index ->
                        try {
                            workers.execute({ ran.incrementAndGet() }, submitter + index)
                        } catch (_: RejectedExecutionException) {
                            rejected.incrementAndGet()
                        }
                    }
                    done.countDown()
                }
            }
            Thread.yield()
            workers.close()
            done.await(5, TimeUnit.SECONDS).assert().isTrue()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (ran.get() + rejected.get() < submitted && System.nanoTime() < deadline) {
                Thread.sleep(1)
            }
            (ran.get() + rejected.get()).assert().describedAs("round $round").isEqualTo(submitted)
            submitters.shutdownNow()
        }
    }

    @Test
    fun `forceClose discards queued tasks on the calling thread and rejects new ones`() {
        val workers = DispatchWorkers(1, "dispatch-workers-force")
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ran = AtomicInteger()
        val discarded = CopyOnWriteArrayList<String>()
        workers.execute {
            running.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        running.await(5, TimeUnit.SECONDS).assert().isTrue()
        repeat(5) {
            workers.execute(object : Runnable, DispatchWorkers.Discardable {
                override fun run() {
                    ran.incrementAndGet()
                }

                override fun discard() {
                    discarded += Thread.currentThread().name
                }
            })
        }
        // A failing discard is reported to the calling thread's handler and does not stop the others being discarded.
        workers.execute(object : Runnable, DispatchWorkers.Discardable {
            override fun run() {
                ran.incrementAndGet()
            }

            override fun discard() {
                throw IllegalStateException("discard failed")
            }
        })
        workers.execute { ran.incrementAndGet() }
        val reported = CopyOnWriteArrayList<Throwable>()
        val caller = Thread.currentThread()
        val previousHandler = caller.uncaughtExceptionHandler
        caller.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> reported += error }
        try {
            workers.forceClose()
        } finally {
            caller.uncaughtExceptionHandler = previousHandler
        }

        discarded.assert().hasSize(5)
        discarded.toSet().assert().containsExactly(Thread.currentThread().name)
        reported.map { it.message }.assert().containsExactly("discard failed")
        workers.forced.assert().isTrue()
        assertThrows<RejectedExecutionException> { workers.execute {} }
        release.countDown()
        Thread.sleep(50)
        ran.get().assert().isZero()
    }

    @Test
    fun `close runs the queued tasks, then rejects new ones`() {
        val workers = DispatchWorkers(1, "dispatch-workers-close")
        val release = CountDownLatch(1)
        val ran = AtomicInteger()
        val drained = CountDownLatch(10)
        workers.execute { release.await(5, TimeUnit.SECONDS) }
        repeat(10) {
            workers.execute {
                ran.incrementAndGet()
                drained.countDown()
            }
        }
        workers.close()
        workers.closed.assert().isTrue()
        assertThrows<RejectedExecutionException> { workers.execute {} }
        release.countDown()
        drained.await(5, TimeUnit.SECONDS).assert().isTrue()
        ran.get().assert().isEqualTo(10)
        workers.close()
    }

    @Test
    fun `a worker thread starts on its first submission`() {
        val prefix = "dispatch-workers-lazy-"
        val workers = DispatchWorkers(3, prefix.dropLast(1))
        try {
            workerThreads(prefix).assert().isZero()
            val ran = CountDownLatch(1)
            workers.execute({ ran.countDown() }, 0)
            ran.await(5, TimeUnit.SECONDS).assert().isTrue()
            workerThreads(prefix).assert().isEqualTo(1)
        } finally {
            workers.close()
        }
    }

    @Test
    fun `closing workers that never ran a task starts no thread`() {
        val prefix = "dispatch-workers-unused-"
        val workers = DispatchWorkers(2, prefix.dropLast(1))
        workers.close()
        workers.forceClose()
        workerThreads(prefix).assert().isZero()
        assertThrows<RejectedExecutionException> { workers.execute {} }
    }

    @Test
    fun `a worker survives a fatal error and keeps running the tasks pinned to it`() {
        val workers = DispatchWorkers(1, "dispatch-workers-fatal")
        try {
            val threads = CopyOnWriteArrayList<Thread>()
            val ran = CountDownLatch(2)
            workers.execute({
                threads += Thread.currentThread()
                throw LinkageError("fatal")
            }, 0)
            workers.execute({ throw StackOverflowError("fatal") }, 0)
            repeat(2) {
                workers.execute({
                    threads += Thread.currentThread()
                    ran.countDown()
                }, 0)
            }
            ran.await(5, TimeUnit.SECONDS).assert().isTrue()
            threads.toSet().assert().hasSize(1)
            threads.first().isAlive.assert().isTrue()
            (workers.nextAffinity() == 0).assert().isTrue()
        } finally {
            workers.close()
        }
    }

    /**
     * Containment itself failing (here: logging a fatal error whose message throws) is the only way a worker can end.
     * The ended worker is then never chosen for a new mailbox, and a task pinned to it is rejected, not stranded.
     */
    @Test
    fun `a worker that ends abnormally is no longer selected and rejects its tasks`() {
        val workers = DispatchWorkers(2, "dispatch-workers-dead")
        try {
            val ran = CountDownLatch(1)
            workers.execute({ throw UnloggableFatalError() }, 0)
            workers.execute({ ran.countDown() }, 1)
            ran.await(5, TimeUnit.SECONDS).assert().isTrue()
            awaitTrue {
                Thread.getAllStackTraces().keys.none { it.name == "dispatch-workers-dead-1" }
            }
            repeat(4) {
                workers.nextAffinity().assert().isEqualTo(1)
            }
            assertThrows<RejectedExecutionException> { workers.execute({}, 0) }
        } finally {
            workers.close()
        }
    }

    @Test
    fun `a worker whose thread cannot start is dead and rejects instead of queueing`() {
        val starts = AtomicInteger()
        val workers = DispatchWorkers(2, "dispatch-workers-no-thread") { thread ->
            if (starts.getAndIncrement() == 0) {
                throw OutOfMemoryError("unable to create native thread")
            }
            thread.start()
        }
        try {
            val rejected = assertThrows<RejectedExecutionException> { workers.execute({}, 0) }
            rejected.cause.assert().isInstanceOf(OutOfMemoryError::class.java)
            // Started at most once: later submissions to the dead worker are rejected, not queued forever.
            assertThrows<RejectedExecutionException> { workers.execute({}, 0) }

            // Keep the live worker busy with a queued task, so no live worker is idle or empty: the fallback still
            // never returns the dead worker.
            val release = CountDownLatch(1)
            val ran = CountDownLatch(2)
            workers.execute({
                release.await(5, TimeUnit.SECONDS)
                ran.countDown()
            }, 1)
            workers.execute({ ran.countDown() }, 1)
            repeat(4) {
                workers.nextAffinity().assert().isEqualTo(1)
            }
            release.countDown()
            ran.await(5, TimeUnit.SECONDS).assert().isTrue()
        } finally {
            workers.close()
        }
    }

    @Test
    fun `a failing uncaught exception handler does not end the worker`() {
        val workers = DispatchWorkers(1, "dispatch-workers-handler")
        try {
            val ran = CountDownLatch(1)
            workers.execute({
                Thread.currentThread().uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ ->
                    throw IllegalStateException("handler")
                }
            }, 0)
            workers.execute({ throw IllegalArgumentException("task") }, 0)
            workers.execute({ ran.countDown() }, 0)
            ran.await(5, TimeUnit.SECONDS).assert().isTrue()
        } finally {
            workers.close()
        }
    }

    @Test
    fun `a fatal error while discarding is contained`() {
        val workers = DispatchWorkers(1, "dispatch-workers-discard-fatal")
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        workers.execute({
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        }, 0)
        started.await(5, TimeUnit.SECONDS).assert().isTrue()
        val discarded = AtomicInteger()
        val failingDiscard = object : Runnable, DispatchWorkers.Discardable {
            override fun run() = Unit
            override fun discard() {
                throw LinkageError("fatal")
            }
        }
        val countingDiscard = object : Runnable, DispatchWorkers.Discardable {
            override fun run() = Unit
            override fun discard() {
                discarded.incrementAndGet()
            }
        }
        workers.execute(failingDiscard, 0)
        workers.execute(countingDiscard, 0)
        workers.forceClose()
        release.countDown()
        discarded.get().assert().isEqualTo(1)
    }
}

/** A JVM-fatal error that cannot be logged: reading its message throws. */
private class UnloggableFatalError : LinkageError() {
    override val message: String
        get() = throw IllegalStateException("unloggable")
}

private fun awaitTrue(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!condition()) {
        check(System.nanoTime() < deadline) { "Condition not met in time." }
        Thread.sleep(5)
    }
}

private fun workerThreads(prefix: String): Int =
    Thread.getAllStackTraces().keys.count { it.name.startsWith(prefix) && it.isAlive }

/** Waits until [count] worker threads named with [prefix] are parked, waiting for a task. */
private fun awaitParked(prefix: String, count: Int) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (true) {
        val parked = Thread.getAllStackTraces().keys.count {
            it.name.startsWith(prefix) && it.state == Thread.State.WAITING
        }
        if (parked >= count) {
            return
        }
        check(System.nanoTime() < deadline) { "Only $parked of $count [$prefix] workers parked." }
        Thread.sleep(1)
    }
}
