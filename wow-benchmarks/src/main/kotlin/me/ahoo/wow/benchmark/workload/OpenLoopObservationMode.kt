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

import java.util.Locale

/**
 * Benchmark-side observer ablations for quantifying measurement perturbation.
 *
 * Only [FULL] preserves the bounded-open-loop protocol's complete timeout,
 * server-drain, generator-fidelity, and latency evidence. Every other mode is
 * a diagnostic ablation and must not be published as formal capacity.
 *
 * In particular, [NO_DEADLINE_WHEEL] changes timeout release and admission
 * semantics when requests cross their deadlines, so its result is not
 * necessarily an upper bound.
 */
enum class OpenLoopObservationMode(
    val deadlineWheelEnabled: Boolean,
    val serverTrackerEnabled: Boolean,
    val generatorLatencyEnabled: Boolean,
    val serviceLatencyEnabled: Boolean,
) {
    FULL(
        deadlineWheelEnabled = true,
        serverTrackerEnabled = true,
        generatorLatencyEnabled = true,
        serviceLatencyEnabled = true,
    ),
    NO_DEADLINE_WHEEL(
        deadlineWheelEnabled = false,
        serverTrackerEnabled = true,
        generatorLatencyEnabled = true,
        serviceLatencyEnabled = true,
    ),
    NO_SERVER_TRACKER(
        deadlineWheelEnabled = true,
        serverTrackerEnabled = false,
        generatorLatencyEnabled = true,
        serviceLatencyEnabled = true,
    ),
    GENERATOR_ONLY_LATENCY(
        deadlineWheelEnabled = true,
        serverTrackerEnabled = true,
        generatorLatencyEnabled = true,
        serviceLatencyEnabled = false,
    ),
    NO_LATENCY(
        deadlineWheelEnabled = true,
        serverTrackerEnabled = true,
        generatorLatencyEnabled = false,
        serviceLatencyEnabled = false,
    ),
    ;

    val fullObservationCoverage: Boolean
        get() = this == FULL

    companion object {
        fun parse(rawValue: String): OpenLoopObservationMode {
            val normalized = rawValue
                .trim()
                .replace('-', '_')
                .uppercase(Locale.US)
            return entries.firstOrNull { it.name == normalized }
                ?: throw IllegalArgumentException(
                    "Unsupported observationMode[$rawValue]. Supported values: " +
                        entries.joinToString { it.name }
                )
        }
    }
}
