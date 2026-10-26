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

/** The pods of the platform: the services and the gateway, not the backends or the probe pods. */
private val PLATFORM = listOf("eshop-", "ops-", "view-store-", "analytics-catalog-", "cosec-gateway-")

private fun platform(pod: String): Boolean = PLATFORM.any(pod::startsWith)

/** Whether [at] falls inside one of [faults], widened by [before] and [after] for the cluster to settle. */
private fun inFault(
    at: Instant,
    faults: List<Fault>,
    before: Duration,
    after: Duration
): Boolean = faults.any { fault ->
    !at.isBefore(fault.start.minus(before)) && !at.isAfter((fault.end ?: Instant.now()).plus(after))
}

/**
 * The probes' view of the platform's pods while the scenarios run (G.3 ⑤): every few seconds, which ones Kubernetes
 * does not count as ready. A pod not ready outside every fault (and the minute after it) is a failed check.
 */
class HealthWatch(private val kube: Kube, private val report: ClusterReport) {
    private val unready = mutableListOf<Pair<Instant, String>>()

    @Volatile
    private var running = false
    private var worker: Thread? = null

    @Volatile
    var samples = 0
        private set

    fun start() {
        running = true
        worker = thread(name = "health-watch", isDaemon = true) {
            while (running) {
                runCatching {
                    val now = Instant.now()
                    kube.allPods().filter { platform(it.name) && !it.ready && !it.deleting }
                        .forEach { synchronized(unready) { unready += now to it.name } }
                    samples++
                }
                Thread.sleep(SAMPLE.toMillis())
            }
        }
    }

    fun stop() {
        running = false
        worker?.join(SAMPLE.multipliedBy(2).toMillis())
    }

    /** The samples of a pod not ready outside every fault. */
    fun unreadyOutsideFaults(): List<String> {
        val faults = synchronized(report) { report.faults.toList() }
        return synchronized(unready) { unready.toList() }
            .filterNot { (at, _) -> inFault(at, faults, Duration.ZERO, SETTLE) }
            .map { (at, pod) -> "$pod at $at" }
    }

    private companion object {
        val SAMPLE: Duration = Duration.ofSeconds(5)
        val SETTLE: Duration = Duration.ofSeconds(60)
    }
}

/**
 * The platform's logs after the run (G.3 ⑤): every ERROR line of every pod still running, its previous container's
 * too. One inside a fault's window (from shortly before it to two minutes after) is fault-induced and [allowed];
 * any other is unexpected. The pods a scenario deleted took their logs with them.
 */
class LogScan(private val kube: Kube, faults: List<Fault>) {
    private val faults = faults.toList()
    val allowed = mutableListOf<String>()
    var scanned = 0
        private set

    fun unexpectedErrors(): List<String> {
        val unexpected = mutableListOf<String>()
        kube.allPods().filter { platform(it.name) }.forEach { pod ->
            scanned++
            (kube.logs(pod.name, previous = true) + kube.logs(pod.name)).lineSequence()
                .filter { ERROR.containsMatchIn(it) }
                .forEach { line ->
                    val at = runCatching { Instant.parse(line.substringBefore(' ')) }.getOrNull()
                    val entry = "${pod.name}: ${line.take(LINE)}"
                    if (at != null && inFault(at, faults, BEFORE, AFTER)) allowed += entry else unexpected += entry
                }
        }
        return unexpected
    }

    private companion object {
        /** Logback's level column, as Spring Boot's console pattern prints it. */
        val ERROR = Regex("""\sERROR\s""")
        val BEFORE: Duration = Duration.ofSeconds(10)
        val AFTER: Duration = Duration.ofMinutes(2)
        const val LINE = 500
    }
}
