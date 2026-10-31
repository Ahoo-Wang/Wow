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

package me.ahoo.wow.example.eshop.e2e.soak

import java.io.File
import java.time.Duration
import kotlin.math.ceil

/**
 * How a soak runs, from the system properties `wow-test.soak.*` (the Gradle properties of the same names reach the test
 * JVM as such):
 *
 * | Property | Default | |
 * |---|---|---|
 * | `wow-test.soak.duration` | `PT1H` | how long the load runs, faults included (ISO-8601) |
 * | `wow-test.soak.mode` | `full` | `short`: a local run of a few minutes (`PT5M`, a fault every 30 s), unless set |
 * | `wow-test.soak.clients` | 4 | clients driving checkouts, each one order at a time |
 * | `wow-test.soak.rate` | 4.0 | orders started per second, across the clients |
 * | `wow-test.soak.faults` | every kind | the fault kinds, comma separated ([FaultKind]); `none` for a soak without |
 * | `wow-test.soak.fault-interval` | duration / 24, 30 s to 3 min | from one fault's start to the next one's |
 * | `wow-test.soak.nodes` | `basket=1,ordering=2,inventory=2,payment=1` | nodes per service |
 * | `wow-test.soak.wait-timeout` | `PT20S` | a checkout's `Wow-Wait-Timeout` |
 * | `wow-test.soak.quiescence` | `PT5M` | how long the flows may take to settle once the load and the faults stop |
 * | `wow-test.soak.sample-interval` | `PT10S` | between two samples of the checkpoints and the parked ledger |
 * | `wow-test.soak.replay-sample` | 200 | aggregates per type whose snapshot is compared with a replay of their log |
 * | `wow-test.soak.heap` | `384m` | each service JVM's maximum heap |
 * | `wow-test.soak.seed` | 0 | the seed of which node a kill takes |
 */
data class SoakConfig(
    val duration: Duration,
    val clients: Int,
    val rate: Double,
    val faults: List<FaultKind>,
    val faultInterval: Duration,
    val nodes: Map<String, Int>,
    val waitTimeout: Duration,
    val quiescence: Duration,
    val sampleInterval: Duration,
    val replaySample: Int,
    val heap: String,
    val partitions: Int,
    val seed: Long,
    val dir: File,
) {
    /**
     * The services' `wow.query.state.max-replay`, the most commits a state read at a version replays (Wow's default
     * is 10,000, which a night's hottest stock passes). The replay check pages every stock's trace from version 1, and
     * a page from version n replays the log up to n - 1 from version 1 (the snapshot is past it), so the bound must
     * hold the hottest aggregate's whole log: a stock's, of which every order the load starts takes at most two
     * commits (its reservation, then committed or released), after the seed's one. The clients start at most [rate]
     * orders a second over the [duration], and one more each. Twice that, for a margin: 57,618 for an hour at the
     * defaults.
     */
    val maxReplay: Int
        get() {
            val orders = ceil(rate * duration.toSeconds()).toLong() + clients
            return ((orders * COMMITS_PER_ORDER + 1) * REPLAY_MARGIN).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

    fun describe(): Map<String, Any> = linkedMapOf(
        "duration" to duration.toString(),
        "clients" to clients,
        "rate" to rate,
        "faults" to faults.map { it.name },
        "faultInterval" to faultInterval.toString(),
        "nodes" to nodes,
        "waitTimeout" to waitTimeout.toString(),
        "quiescence" to quiescence.toString(),
        "sampleInterval" to sampleInterval.toString(),
        "replaySample" to replaySample,
        "maxReplay" to maxReplay,
        "heap" to heap,
        "partitions" to partitions,
        "seed" to seed,
    )

    companion object {
        val CONTEXTS = listOf("basket", "ordering", "inventory", "payment")

        fun fromSystemProperties(): SoakConfig {
            val short = property("mode") == "short"
            val duration = property("duration")?.let(Duration::parse) ?: if (short) SHORT else FULL
            val interval = property("fault-interval")?.let(Duration::parse)
                ?: if (short) SHORT_INTERVAL else duration.dividedBy(FAULTS_PER_RUN).coerceIn(MIN_INTERVAL, MAX_INTERVAL)
            return SoakConfig(
                duration = duration,
                clients = property("clients")?.toInt() ?: DEFAULT_CLIENTS,
                rate = property("rate")?.toDouble() ?: DEFAULT_RATE,
                faults = faults(),
                faultInterval = interval,
                nodes = DEFAULT_NODES + nodes(),
                waitTimeout = property("wait-timeout")?.let(Duration::parse) ?: Duration.ofSeconds(20),
                quiescence = property("quiescence")?.let(Duration::parse) ?: Duration.ofMinutes(5),
                sampleInterval = property("sample-interval")?.let(Duration::parse) ?: Duration.ofSeconds(10),
                replaySample = property("replay-sample")?.toInt() ?: DEFAULT_REPLAY_SAMPLE,
                heap = property("heap") ?: "384m",
                partitions = PARTITIONS,
                seed = property("seed")?.toLong() ?: 0L,
                dir = File(System.getProperty("eshop.soak.dir") ?: "build/eshop-soak"),
            )
        }

        /**
         * `wow-test.soak.faults`: every kind by default, none for `none`, else the kinds listed (`kill-ordering`, ...).
         */
        private fun faults(): List<FaultKind> = when (val kinds = property("faults")) {
            null -> FaultKind.entries.toList()
            "none" -> emptyList()
            else -> kinds.split(',').map { FaultKind.valueOf(it.trim().uppercase().replace('-', '_')) }
        }

        /** `wow-test.soak.nodes`: `<service>=<count>`, comma separated, over the defaults. */
        private fun nodes(): Map<String, Int> = property("nodes")?.split(',')?.associate { entry ->
            val (context, count) = entry.split('=').map(String::trim)
            require(context in CONTEXTS) { "No service $context; the services are $CONTEXTS." }
            context to count.toInt()
        } ?: emptyMap()

        private fun property(name: String): String? = System.getProperty("wow-test.soak.$name")?.takeIf { it.isNotBlank() }

        private val FULL: Duration = Duration.ofHours(1)
        private val SHORT: Duration = Duration.ofMinutes(5)
        private val SHORT_INTERVAL: Duration = Duration.ofSeconds(30)
        private val MIN_INTERVAL: Duration = Duration.ofSeconds(30)
        private val MAX_INTERVAL: Duration = Duration.ofMinutes(3)
        private const val FAULTS_PER_RUN = 24L
        private const val DEFAULT_CLIENTS = 4
        private const val DEFAULT_RATE = 4.0
        private const val DEFAULT_REPLAY_SAMPLE = 200
        private const val COMMITS_PER_ORDER = 2L
        private const val REPLAY_MARGIN = 2L
        private const val PARTITIONS = 4
        private val DEFAULT_NODES = mapOf("basket" to 1, "ordering" to 2, "inventory" to 2, "payment" to 1)
    }
}

/** The faults the injector cycles through, in this order. */
enum class FaultKind {
    /** SIGKILL of an ordering node, then a node of a new id in its place: the leases of its partitions move. */
    KILL_ORDERING,

    /** PostgreSQL frozen (`docker pause`) for 15 s: every statement hangs, then goes on. */
    PAUSE_POSTGRES,

    /** SIGKILL of an inventory node, then a node of a new id in its place. */
    KILL_INVENTORY,

    /** Kafka restarted (`docker restart`): every producer and consumer loses its broker for a while. */
    RESTART_KAFKA,

    /** SIGKILL of a payment node, then a node of a new id in its place. */
    KILL_PAYMENT,

    /** PostgreSQL restarted (`docker restart`, a fast shutdown): every connection is cut. */
    RESTART_POSTGRES,

    /** SIGKILL of a basket node, one the clients send to, then a node of a new id in its place. */
    KILL_BASKET,
}
