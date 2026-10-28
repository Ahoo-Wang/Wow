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

package me.ahoo.wow.example.eshop.e2e.cluster

import java.time.Duration
import java.time.Instant
import kotlin.concurrent.thread

/**
 * Measures one fault's unavailability window: [probe] runs back to back on a thread of its own from [start] until
 * [stop], each run recorded as served or failed with its time. The window is from the first failure to the end of the
 * last one (the next served probe's start), so a fault the shop absorbs has a window of zero; a probe still failing
 * at [stop] counts until then.
 */
class Outages(private val name: String, private val probe: () -> Boolean) {
    private class Sample(val start: Long, val end: Long, val served: Boolean)

    private val samples = mutableListOf<Sample>()

    @Volatile
    private var running = false
    private var worker: Thread? = null

    fun start(): Outages = apply {
        running = true
        worker = thread(name = "probe-$name", isDaemon = true) {
            while (running) {
                val begun = System.currentTimeMillis()
                val served = runCatching(probe).getOrDefault(false)
                synchronized(samples) { samples += Sample(begun, System.currentTimeMillis(), served) }
                if (served) Thread.sleep(PAUSE.toMillis())
            }
        }
    }

    /** Stops probing and gives the [fault]'s window with its [bound]. */
    fun stop(fault: String, bound: Duration): Window {
        running = false
        worker?.join(JOIN.toMillis())
        val recorded = synchronized(samples) { samples.toList() }
        val firstFailure = recorded.indexOfFirst { !it.served }
        val millis = if (firstFailure < 0) {
            0L
        } else {
            val lastFailure = recorded.indexOfLast { !it.served }
            val recovered = recorded.getOrNull(lastFailure + 1)?.start ?: recorded[lastFailure].end
            recovered - recorded[firstFailure].start
        }
        return Window(fault, name, millis, bound, recorded.count { !it.served }, recorded.size)
    }

    companion object {
        val PAUSE: Duration = Duration.ofMillis(250)
        val JOIN: Duration = Duration.ofSeconds(60)

        /** Runs [action] while [name]'s probe measures it, after [lead] of steady probes, until [settled] holds. */
        fun measure(
            report: ClusterReport,
            fault: String,
            bound: Duration,
            probeName: String,
            probe: () -> Boolean,
            settled: () -> Boolean,
            settleTimeout: Duration = Duration.ofMinutes(10),
            lead: Duration = Duration.ofSeconds(5),
            action: () -> Unit,
        ): Window {
            val outages = Outages(probeName, probe).start()
            Thread.sleep(lead.toMillis())
            val record = Fault(fault, Instant.now())
            synchronized(report) { report.faults += record }
            action()
            val until = System.nanoTime() + settleTimeout.toNanos()
            while (!runCatching(settled).getOrDefault(false) && System.nanoTime() < until) {
                Thread.sleep(SETTLE_POLL.toMillis())
            }
            // A few served probes after the cluster settled close the window.
            Thread.sleep(lead.toMillis())
            record.end = Instant.now()
            return outages.stop(fault, bound).also(report::window)
        }

        private val SETTLE_POLL: Duration = Duration.ofSeconds(2)
    }
}
