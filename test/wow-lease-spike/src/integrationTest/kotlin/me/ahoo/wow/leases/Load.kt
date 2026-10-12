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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.runtime.wait.ResultCodes
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds

/** Aggregate ids drawn from a Zipf distribution with exponent [s] over [count] aggregates, ranks shuffled onto ids. */
class Zipf(private val count: Int, s: Double, seed: Long = 42) {
    private val cumulative = DoubleArray(count)
    private val ids = (0 until count).shuffled(kotlin.random.Random(seed)).map { "acc-$it" }

    init {
        var sum = 0.0
        for (rank in 0 until count) {
            sum += 1.0 / (rank + 1.0).pow(s)
            cumulative[rank] = sum
        }
        for (rank in 0 until count) {
            cumulative[rank] /= sum
        }
    }

    fun next(): String {
        val draw = ThreadLocalRandom.current().nextDouble()
        val found = cumulative.binarySearch(draw)
        val rank = if (found >= 0) found else (-found - 1).coerceAtMost(count - 1)
        return ids[rank]
    }
}

/**
 * An open-loop write load: [rate] deposits a second, each sent to a uniformly random receiver (the load balancer) and
 * waited for until committed. Each command is counted in the second it was sent.
 */
class LoadDriver(private val cluster: SpikeCluster, private val rate: Double, private val zipf: Zipf) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlight = SupervisorJob()
    private var pacing: Job? = null

    fun start() {
        pacing = scope.launch {
            val started = System.nanoTime()
            var sent = 0L
            while (isActive) {
                val due = ((System.nanoTime() - started) / NANOS_PER_SECOND * rate).toLong()
                while (sent < due) {
                    sent++
                    scope.launch(inFlight) { send() }
                }
                delay(TICK_MILLIS)
            }
        }
    }

    private suspend fun send() {
        val receivers = cluster.receivers
        if (receivers.isEmpty()) {
            return
        }
        val node = receivers[ThreadLocalRandom.current().nextInt(receivers.size)]
        val sentAt = System.currentTimeMillis()
        val second = cluster.timeline.at(sentAt)
        second.sent.increment()
        val started = System.nanoTime()
        try {
            val result = withTimeout(COMMAND_TIMEOUT) {
                node.coordinator.execute(node.model, node.command(zipf.next(), Ids.next()))
            }
            when {
                result.status == CommandResult.Status.SUCCEEDED -> {
                    second.succeeded.increment()
                    if (result.duplicate) {
                        second.duplicates.increment()
                    }
                }

                result.error?.code == ResultCodes.CONTENDED -> second.contended.increment()
                result.error?.code == ResultCodes.UNKNOWN -> second.unknown.increment()
                else -> second.otherFailed.increment()
            }
        } catch (cancelled: CancellationException) {
            second.errors.increment()
            if (!scope.isActive) {
                throw cancelled
            }
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            second.errors.increment()
        } finally {
            second.latencies += (System.nanoTime() - started) / NANOS_PER_MICRO
        }
    }

    /** Stops sending and waits a while for the commands in flight. */
    suspend fun stop() {
        pacing?.cancelAndJoin()
        withTimeoutOrNull(DRAIN) {
            while (inFlight.children.any()) {
                delay(TICK_MILLIS)
            }
        }
        inFlight.cancelAndJoin()
    }

    private companion object {
        const val NANOS_PER_SECOND = 1e9
        const val NANOS_PER_MICRO = 1_000L
        const val TICK_MILLIS = 5L
        val COMMAND_TIMEOUT = 30.seconds
        val DRAIN = 30.seconds
    }
}
