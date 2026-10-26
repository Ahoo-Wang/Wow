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

import java.io.File
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

    /** The runner's 1-minute load average at each sample: the kind node shares the runner's kernel and cores. */
    private val loads = mutableListOf<Double>()

    /** The load averages seen, for the report: their maximum and median over the run. */
    fun load(): String = synchronized(loads) {
        if (loads.isEmpty()) return "no load samples"
        val sorted = loads.sorted()
        "load average max ${sorted.last()}, median ${sorted[sorted.size / 2]} on ${Runtime.getRuntime().availableProcessors()} cores"
    }

    fun start() {
        running = true
        worker = thread(name = "health-watch", isDaemon = true) {
            while (running) {
                runCatching {
                    val now = Instant.now()
                    kube.allPods().filter { platform(it.name) && !it.ready && !it.deleting }
                        .forEach { synchronized(unready) { unready += now to it.name } }
                    samples++
                    File("/proc/loadavg").takeIf { it.exists() }?.readText()?.substringBefore(' ')?.toDoubleOrNull()
                        ?.let { synchronized(loads) { loads += it } }
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

/**
 * Evidence for an outage the logs alone would lose (finding F10, PostgreSQL out of connections after a node's
 * crash), kept in [directory]: every 30 s, PostgreSQL's connections by client address and state (`pg_stat_activity`,
 * through psql inside its pod, which a full server still admits on its local socket for the superuser); every 60 s,
 * the last minute of each ordering node's log, before the kubelet rotates a log a flood of stack traces fills.
 */
class Evidence(private val kube: Kube, private val directory: File) {
    @Volatile
    private var running = false
    private var worker: Thread? = null

    fun start() {
        directory.mkdirs()
        runCatching { settings() }
        running = true
        worker = thread(name = "evidence", isDaemon = true) {
            var round = 0L
            while (running) {
                runCatching { connections() }
                if (round % LOGS_EVERY == 0L) runCatching { orderingLogs() }
                round++
                Thread.sleep(SAMPLE.toMillis())
            }
        }
    }

    fun stop() {
        running = false
        worker?.join(SAMPLE.multipliedBy(2).toMillis())
    }

    private fun connections() {
        // Grouped by client and state, with when the oldest backend started, the last state change, what it waits on
        // and the start of its last query: a dead pod's backends linger, unchanged, until TCP keepalive drops them.
        val query = "SELECT coalesce(host(client_addr), 'local') AS client, coalesce(state, '-') AS state, count(*), " +
            "min(backend_start)::timestamp(0), max(state_change)::timestamp(0), " +
            "string_agg(DISTINCT coalesce(wait_event, '-'), ','), left(max(query), 60) FROM pg_stat_activity " +
            "WHERE backend_type = 'client backend' GROUP BY 1, 2 ORDER BY 3 DESC"
        val result = kube.exec("postgres-0", "psql", "-U", "postgres", "-tA", "-F", " | ", "-c", query)
        File(directory, "pg-connections.log").appendText("${Instant.now()}\n${result.out.ifBlank { result.err }}\n")
    }

    /** The server's limits and keepalive settings, once: what decides how long a dead client's backends stay. */
    private fun settings() {
        val query = "SELECT name, setting, unit FROM pg_settings WHERE name IN ('max_connections', " +
            "'superuser_reserved_connections', 'tcp_keepalives_idle', 'tcp_keepalives_interval', 'tcp_keepalives_count', " +
            "'idle_session_timeout', 'idle_in_transaction_session_timeout', 'client_connection_check_interval')"
        val result = kube.exec("postgres-0", "psql", "-U", "postgres", "-tA", "-F", " ", "-c", query)
        File(directory, "pg-settings.txt").writeText(result.out.ifBlank { result.err })
    }

    private fun orderingLogs() {
        kube.pods("eshop-ordering").forEach { pod ->
            val log = kube.run("logs", pod.name, "--timestamps=true", "--since=70s", "--limit-bytes=$LOG_BYTES")
            File(directory, "${pod.name}.log").appendText(log.out)
            // A pod that restarted: its previous container's log, how far it got before it failed.
            if (pod.restarts > 0) {
                val previous = kube.run("logs", pod.name, "--previous", "--timestamps=true", "--limit-bytes=$LOG_BYTES")
                File(directory, "${pod.name}.previous-${pod.restarts}.log").writeText(previous.out)
            }
        }
        File(directory, "pods.log").appendText(
            "${Instant.now()}\n${kube.run("get", "pods", "-o", "wide").out}\n",
        )
    }

    private companion object {
        val SAMPLE: Duration = Duration.ofSeconds(30)
        const val LOGS_EVERY = 2L
        const val LOG_BYTES = 2_000_000
    }
}
