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

package me.ahoo.wow.benchmark.workload

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * Removable deadline buckets for bounded-open-loop timeout observation.
 *
 * Completed entries are removed from their bucket immediately, so the timeout
 * observer visits only entries that are still pending when their deadline tick
 * arrives. A sweep visits each bucket at most once even after a long observer
 * pause.
 */
class OpenLoopDeadlineWheel<T : Any>(
    val resolutionNanos: Long,
    maximumTimeoutNanos: Long,
    initialTimeNanos: Long,
    private val deadlineNanos: (T) -> Long,
) {
    private val wheelSize: Int
    private val maximumTimeoutNanos: Long
    private val buckets: Array<MutableSet<OpenLoopDeadlineRegistration<T>>>
    private val entryCount = AtomicInteger()

    @Volatile
    private var lastProcessedTick: Long

    val size: Int
        get() = entryCount.get()

    init {
        require(resolutionNanos > 0) {
            "resolutionNanos must be greater than 0."
        }
        require(maximumTimeoutNanos > 0) {
            "maximumTimeoutNanos must be greater than 0."
        }
        this.maximumTimeoutNanos = maximumTimeoutNanos
        val timeoutTicks = ceilDiv(maximumTimeoutNanos, resolutionNanos)
        require(timeoutTicks <= Int.MAX_VALUE - EXTRA_BUCKETS) {
            "maximumTimeoutNanos requires too many deadline buckets."
        }
        wheelSize = timeoutTicks.toInt() + EXTRA_BUCKETS
        buckets = Array(wheelSize) {
            ConcurrentHashMap.newKeySet()
        }
        lastProcessedTick = tickAt(initialTimeNanos)
    }

    /**
     * Registers an entry whose immutable deadline is strictly after [nowNanos]
     * and no more than the configured maximum timeout away.
     *
     * [onExpired] can run on the caller when a concurrent sweep passed the
     * entry's deadline bucket while it was being registered.
     */
    fun add(
        entry: T,
        nowNanos: Long,
        onExpired: (T) -> Unit,
    ): OpenLoopDeadlineRegistration<T> {
        val entryDeadlineNanos = deadlineNanos(entry)
        require(entryDeadlineNanos > nowNanos) {
            "deadlineNanos[$entryDeadlineNanos] must be after nowNanos[$nowNanos]."
        }
        val remainingNanos = Math.subtractExact(entryDeadlineNanos, nowNanos)
        require(remainingNanos <= maximumTimeoutNanos) {
            "deadlineNanos is $remainingNanos ns away, exceeding maximumTimeoutNanos[$maximumTimeoutNanos]."
        }
        val deadlineTick = deadlineTick(entryDeadlineNanos)
        val bucketIndex = bucketIndex(deadlineTick)
        val registration = OpenLoopDeadlineRegistration(
            entry = entry,
            deadlineNanos = entryDeadlineNanos,
            bucketIndex = bucketIndex,
        )
        entryCount.incrementAndGet()
        check(buckets[bucketIndex].add(registration)) {
            entryCount.decrementAndGet()
            "Deadline registration identity was already present."
        }
        if (deadlineTick <= lastProcessedTick && deactivate(registration)) {
            onExpired(entry)
        }
        return registration
    }

    fun remove(registration: OpenLoopDeadlineRegistration<T>): Boolean =
        deactivate(registration)

    @Suppress("TooGenericExceptionCaught")
    @Synchronized
    fun expireDue(
        nowNanos: Long,
        onExpired: (T) -> Unit,
    ): OpenLoopDeadlineSweep {
        val currentTick = tickAt(nowNanos)
        if (currentTick <= lastProcessedTick) {
            return OpenLoopDeadlineSweep.EMPTY
        }
        val elapsedTicks = Math.subtractExact(currentTick, lastProcessedTick)
        val bucketsToVisit = min(elapsedTicks, wheelSize.toLong())
        val firstTick = Math.addExact(
            currentTick,
            Math.subtractExact(1, bucketsToVisit),
        )
        /*
         * Publish the sweep watermark before visiting buckets. An add racing
         * after its bucket was visited will then observe the watermark and
         * expire itself; an add racing before publication remains visible to
         * this sweep. Registration CAS prevents duplicate callbacks.
         */
        lastProcessedTick = currentTick
        var candidatesVisited = 0L
        var expired = 0L
        var callbackFailure: Throwable? = null
        var tick = firstTick
        while (tick <= currentTick) {
            val bucket = buckets[bucketIndex(tick)]
            bucket.forEach { registration ->
                candidatesVisited++
                if (registration.deadlineNanos <= nowNanos && deactivate(registration)) {
                    expired++
                    try {
                        onExpired(registration.entry)
                    } catch (failure: Throwable) {
                        callbackFailure?.addSuppressed(failure)
                            ?: run {
                                callbackFailure = failure
                            }
                    }
                }
            }
            if (tick == Long.MAX_VALUE) {
                break
            }
            tick++
        }
        callbackFailure?.let { throw it }
        return OpenLoopDeadlineSweep(
            elapsedTicks = elapsedTicks,
            bucketsVisited = bucketsToVisit,
            candidatesVisited = candidatesVisited,
            expired = expired,
        )
    }

    private fun deactivate(registration: OpenLoopDeadlineRegistration<T>): Boolean {
        if (!registration.active.compareAndSet(true, false)) {
            return false
        }
        check(buckets[registration.bucketIndex].remove(registration)) {
            "Active deadline registration was missing from its bucket."
        }
        val remaining = entryCount.decrementAndGet()
        check(remaining >= 0) {
            "Deadline wheel size became negative."
        }
        return true
    }

    private fun deadlineTick(deadlineNanos: Long): Long {
        val floorTick = tickAt(deadlineNanos)
        return if (Math.floorMod(deadlineNanos, resolutionNanos) == 0L) {
            floorTick
        } else {
            Math.addExact(floorTick, 1)
        }
    }

    private fun tickAt(timeNanos: Long): Long =
        Math.floorDiv(timeNanos, resolutionNanos)

    private fun bucketIndex(tick: Long): Int =
        Math.floorMod(tick, wheelSize.toLong()).toInt()

    private fun ceilDiv(
        dividend: Long,
        divisor: Long,
    ): Long {
        val quotient = dividend / divisor
        return if (dividend % divisor == 0L) {
            quotient
        } else {
            Math.addExact(quotient, 1)
        }
    }

    private companion object {
        const val EXTRA_BUCKETS: Int = 2
    }
}

class OpenLoopDeadlineRegistration<T : Any> internal constructor(
    internal val entry: T,
    internal val deadlineNanos: Long,
    internal val bucketIndex: Int,
) {
    internal val active = AtomicBoolean(true)
}

data class OpenLoopDeadlineSweep(
    val elapsedTicks: Long,
    val bucketsVisited: Long,
    val candidatesVisited: Long,
    val expired: Long,
) {
    companion object {
        val EMPTY = OpenLoopDeadlineSweep(
            elapsedTicks = 0,
            bucketsVisited = 0,
            candidatesVisited = 0,
            expired = 0,
        )
    }
}
