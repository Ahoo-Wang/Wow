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

package me.ahoo.wow.routing.stage2

import java.io.File
import java.time.Duration

/** Which node a command is sent to, and so where it runs: the host's `wow.routing`. */
enum class Router(val label: String, val property: String) {
    PARTITIONED("Partitioned", "partitioned"),
    LOCAL("Local", "local"),
}

/**
 * The settings of one stage-2 run, from the system properties `wow-test.h2.stage2.*` (the Gradle properties of the same
 * names):
 *
 * - `mode`: `full` (the default) or `smoke`, a run of seconds on a small data set that only checks the harness;
 * - `duration`: the measured window of each run (ISO-8601, `PT5M` by default; `PT5S` in smoke mode);
 * - `warm-up`: the load before it (`PT30S`; `PT3S`);
 * - `recovery`: how long the load goes on after an owner is killed (`PT60S`; `PT20S`), longer than the lease TTL;
 * - `rate`: commands per second, offered open loop (400; 50);
 * - `aggregates`: the seeded accounts (100000, as stage 1; 2000);
 * - `distributions`: `uniform,zipf` (`zipf`);
 * - `routers`: `partitioned,local`; the owner is killed in the Partitioned runs only;
 * - `partitions` (64, as stage 1), `node-heap` (`1g`), `node-pool` (16, as stage 1).
 */
data class Stage2Settings(
    val smoke: Boolean,
    val duration: Duration,
    val warmUp: Duration,
    val recovery: Duration,
    val rate: Double,
    val aggregates: Int,
    val distributions: List<String>,
    val routers: List<Router>,
    val partitions: Int,
    val nodeHeap: String,
    val nodePool: Int,
    val classpath: String,
    val directory: File,
) {
    init {
        require(duration.isPositive && !warmUp.isNegative && recovery.isPositive) { "The durations are positive." }
        require(rate > 0) { "The rate is positive." }
        require(aggregates > 0) { "At least one aggregate." }
        require(distributions.all { it in DISTRIBUTIONS }) { "Distributions are among $DISTRIBUTIONS: $distributions" }
        require(distributions.isNotEmpty() && routers.isNotEmpty()) { "At least one distribution and one router." }
    }

    companion object {
        const val PREFIX = "wow-test.h2.stage2."
        val DISTRIBUTIONS = listOf("uniform", "zipf")

        @Suppress("CyclomaticComplexMethod") // One default per setting and mode.
        fun fromSystemProperties(): Stage2Settings {
            fun property(name: String): String? = System.getProperty(PREFIX + name)?.takeIf { it.isNotBlank() }
            val mode = property("mode") ?: "full"
            require(mode == "full" || mode == "smoke") { "${PREFIX}mode is full or smoke: $mode" }
            val smoke = mode == "smoke"
            fun duration(name: String, full: String, quick: String) = Duration.parse(
                property(name) ?: if (smoke) quick else full
            )
            return Stage2Settings(
                smoke = smoke,
                duration = duration("duration", "PT5M", "PT5S"),
                warmUp = duration("warm-up", "PT30S", "PT3S"),
                recovery = duration("recovery", "PT60S", "PT20S"),
                rate = property("rate")?.toDouble() ?: if (smoke) SMOKE_RATE else RATE,
                aggregates = property("aggregates")?.toInt() ?: if (smoke) SMOKE_AGGREGATES else AGGREGATES,
                distributions = property("distributions")?.split(',')?.map { it.trim() }
                    ?: if (smoke) listOf("zipf") else DISTRIBUTIONS,
                routers = property("routers")?.split(',')?.map { name ->
                    Router.entries.first { it.property == name.trim() }
                } ?: Router.entries,
                partitions = property("partitions")?.toInt() ?: PARTITIONS,
                nodeHeap = property("node-heap") ?: "1g",
                nodePool = property("node-pool")?.toInt() ?: NODE_POOL,
                classpath = requireNotNull(property("classpath")) { "System property ${PREFIX}classpath is not set." },
                directory = File(property("dir") ?: "build/h2-stage2"),
            )
        }

        private const val RATE = 400.0
        private const val SMOKE_RATE = 50.0
        private const val AGGREGATES = 100_000
        private const val SMOKE_AGGREGATES = 2_000
        private const val PARTITIONS = 64
        private const val NODE_POOL = 16
    }
}
