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

package me.ahoo.wow.leases

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder

/** What happened to the commands sent, and inside the cluster, in one wall-clock second. */
class Second {
    val sent = LongAdder()
    val succeeded = LongAdder()

    /** Succeeded as a duplicate: the command was already committed (a forward that timed out, then ran here). */
    val duplicates = LongAdder()
    val contended = LongAdder()
    val unknown = LongAdder()
    val otherFailed = LongAdder()

    /** The call threw instead of returning a result. */
    val errors = LongAdder()

    /** Appends that lost the version slot and were retried by the kernel. */
    val conflicts = LongAdder()

    /** Commands decided on a node that did not hold their partition (Local fallback, decision D71). */
    val nonOwner = LongAdder()
    val forwards = LongAdder()

    /** Forwards that failed: refused connection, refusal reply, or abandoned at the forward timeout. */
    val forwardFailures = LongAdder()

    /** Renewals that found the lease no longer held: the node stops the partition (it lost the lease). */
    val lostLeases = LongAdder()

    /** End-to-end latency of the commands sent in this second, in microseconds. */
    val latencies = ConcurrentLinkedQueue<Long>()
}

/** Per-second counters of one series, keyed by epoch second. */
class Timeline {
    private val seconds = ConcurrentHashMap<Long, Second>()

    fun at(epochMillis: Long): Second = seconds.computeIfAbsent(epochMillis / MILLIS) { Second() }

    fun now(): Second = at(System.currentTimeMillis())

    fun second(epochSecond: Long): Second? = seconds[epochSecond]

    private companion object {
        const val MILLIS = 1_000L
    }
}

/** One handling of a log entry by the projector: when, and in which partition. */
data class Handled(val at: Long, val partition: Int)

/**
 * Every handling of every entry by the projector subscription, across all nodes of a repetition. The first handling of
 * an entry (aggregate id, version) goes to [firsts]; every later one is a duplicate and goes to [repeats].
 */
class Handlings {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    val firsts = ConcurrentLinkedQueue<Handled>()
    val repeats = ConcurrentLinkedQueue<Handled>()

    fun handled(id: String, version: Int, partition: Int) {
        val handled = Handled(System.currentTimeMillis(), partition)
        if (counts.computeIfAbsent("$id@$version") { AtomicInteger() }.incrementAndGet() == 1) {
            firsts += handled
        } else {
            repeats += handled
        }
    }
}

/** The latency of each lease-store call, by operation, in microseconds, and the calls that threw. */
class LeaseMetrics {
    val latencies = ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>()
    val failures = LongAdder()

    fun record(operation: String, micros: Long) {
        latencies.computeIfAbsent(operation) { ConcurrentLinkedQueue() } += micros
    }
}

object Stats {
    /** The nearest-rank [quantile] of [values]; NaN when empty. */
    fun quantile(values: List<Double>, quantile: Double): Double {
        if (values.isEmpty()) {
            return Double.NaN
        }
        val sorted = values.sorted()
        val rank = kotlin.math.ceil(quantile * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) {
            return Double.NaN
        }
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }

    /** A percentile-bootstrap 95% interval of the median, with a fixed seed so a report is reproducible. */
    fun medianInterval(values: List<Double>, resamples: Int = 2_000): Pair<Double, Double> {
        if (values.size < 2) {
            return median(values) to median(values)
        }
        val random = java.util.Random(SEED)
        val medians = List(resamples) { median(List(values.size) { values[random.nextInt(values.size)] }) }
        return quantile(medians, LOW) to quantile(medians, HIGH)
    }

    private const val SEED = 6L
    private const val LOW = 0.025
    private const val HIGH = 0.975
}
