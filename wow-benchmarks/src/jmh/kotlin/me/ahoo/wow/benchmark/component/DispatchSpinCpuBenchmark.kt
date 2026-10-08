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

package me.ahoo.wow.benchmark.component

import me.ahoo.wow.execution.KeyedExecutor
import org.openjdk.jmh.annotations.AuxCounters
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Threads
import java.lang.management.ManagementFactory
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The CPU a dispatch worker spends waiting for messages (`wow.dispatch.spin`), for message patterns a closed-loop
 * benchmark does not show. One [KeyedExecutor] worker gets a no-op task per message; the JMH thread paces the messages
 * by busy-waiting on [System.nanoTime] (a sleep would overshoot short intervals):
 * - `steady-N`: one message every N µs;
 * - `burst-N`: two messages 10 µs apart, then N µs without any.
 *
 * The score is the pattern rate; `workerCpuNanos` is the worker thread's CPU time (`ThreadMXBean`) per second of the
 * run, so `workerCpuNanos / 1e9` is the share of one core the worker uses. Compare [spinMicros] `0` (always park)
 * with the default.
 */
@State(Scope.Benchmark)
@Threads(1)
@Suppress("VarCouldBeVal") // JMH injects @Param fields via reflection, so they must be `var`.
open class DispatchSpinCpuBenchmark {
    @Param("0", "20")
    var spinMicros: Long = 20

    @Param("steady-10", "steady-15", "steady-30", "steady-60", "steady-90", "burst-200", "burst-1000", "burst-5000")
    lateinit var pattern: String

    private lateinit var executor: KeyedExecutor
    private lateinit var steadyOrBurst: String
    private var gapNanos: Long = 0
    private var workerId: Long = 0
    private val noop = Runnable { }

    @Setup(Level.Trial)
    fun setup() {
        val (kind, micros) = pattern.split('-')
        steadyOrBurst = kind
        gapNanos = TimeUnit.MICROSECONDS.toNanos(micros.toLong())
        executor = KeyedExecutor(workers = 1, name = "spin-cpu", spin = Duration.ofNanos(spinMicros * 1_000))
        val worker = AtomicReference<Thread>()
        val started = CountDownLatch(1)
        submit {
            worker.set(Thread.currentThread())
            started.countDown()
        }
        check(started.await(5, TimeUnit.SECONDS)) { "The dispatch worker did not start." }
        @Suppress("DEPRECATION") // Thread.threadId() needs JDK 19; CI runs the benchmarks on 17.
        workerId = worker.get().id
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        executor.close()
    }

    private fun submit(task: Runnable) {
        executor.coroutineDispatcher.dispatch(EmptyCoroutineContext, task)
    }

    @Benchmark
    fun pattern(counters: WorkerCpu) {
        val start = System.nanoTime()
        submit(noop)
        if (steadyOrBurst == "burst") {
            awaitSince(start, BURST_SPACING_NANOS)
            submit(noop)
            awaitSince(start, BURST_SPACING_NANOS + gapNanos)
        } else {
            awaitSince(start, gapNanos)
        }
        counters.record(workerId)
    }

    private fun awaitSince(start: Long, nanos: Long) {
        while (System.nanoTime() - start < nanos) {
            Thread.onSpinWait()
        }
    }

    /** The worker's CPU time since the previous invocation; JMH reports it per second (ns of CPU per second). */
    @State(Scope.Thread)
    @AuxCounters(AuxCounters.Type.OPERATIONS)
    open class WorkerCpu {
        @JvmField
        var workerCpuNanos: Long = 0
        private var last: Long = -1

        @Setup(Level.Iteration)
        fun reset() {
            workerCpuNanos = 0
            last = -1
        }

        fun record(workerId: Long) {
            val now = THREADS.getThreadCpuTime(workerId)
            if (last >= 0) {
                workerCpuNanos += now - last
            }
            last = now
        }
    }

    private companion object {
        val BURST_SPACING_NANOS: Long = TimeUnit.MICROSECONDS.toNanos(10)
        val THREADS = ManagementFactory.getThreadMXBean()
    }
}
