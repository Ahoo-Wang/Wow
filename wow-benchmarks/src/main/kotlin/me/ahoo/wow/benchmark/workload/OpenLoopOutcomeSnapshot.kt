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

/**
 * Terminal accounting for one fixed-arrival cohort.
 *
 * [planned] is deliberately independent of how many requests the load generator
 * managed to launch. This makes generator saturation, admission shedding, and
 * server-side completion visible as separate loss modes.
 */
data class OpenLoopOutcomeSnapshot(
    val planned: Long,
    val generatorMissed: Long,
    val generatorExpired: Long,
    val shedAtMaxInFlight: Long,
    val admitted: Long,
    val sent: Long,
    val sentByDeadline: Long,
    val processedByDeadline: Long,
    val commandFailed: Long,
    val gatewayFailed: Long,
    val timedOut: Long,
    val forcedCancelled: Long,
) {
    val terminal: Long
        get() = processedByDeadline + commandFailed + gatewayFailed + timedOut + forcedCancelled

    fun invariantViolations(): List<String> =
        buildList {
            val accountedArrivals =
                generatorMissed + generatorExpired + shedAtMaxInFlight + admitted
            if (planned != accountedArrivals) {
                add(
                    "planned[$planned] != generatorMissed + generatorExpired + " +
                        "shedAtMaxInFlight + admitted[$accountedArrivals]"
                )
            }
            if (admitted != terminal) {
                add("admitted[$admitted] != terminal[$terminal]")
            }
            if (sent > admitted) {
                add("sent[$sent] > admitted[$admitted]")
            }
            if (sentByDeadline > sent) {
                add("sentByDeadline[$sentByDeadline] > sent[$sent]")
            }
        }
}

fun OpenLoopOutcomeSnapshot.generatorFidelityViolations(
    generatorLagP99Micros: Double?,
    maxMissedRatio: Double,
    maxLagP99Micros: Double,
    requireLagSample: Boolean = true,
): List<String> {
    require(maxMissedRatio.isFinite() && maxMissedRatio in 0.0..1.0) {
        "maxMissedRatio must be a finite number between 0 and 1: $maxMissedRatio"
    }
    require(maxLagP99Micros.isFinite() && maxLagP99Micros >= 0.0) {
        "maxLagP99Micros must be a finite non-negative number: $maxLagP99Micros"
    }
    return buildList {
        if (generatorExpired != 0L) {
            add("measurement generatorExpired[$generatorExpired] != 0")
        }
        val missedRatio =
            if (planned == 0L) {
                0.0
            } else {
                generatorMissed.toDouble() / planned
            }
        if (missedRatio > maxMissedRatio) {
            add(
                "measurement generatorMissedRatio[$missedRatio] > " +
                    "maxGeneratorMissedRatio[$maxMissedRatio]"
            )
        }
        if (requireLagSample) {
            if (generatorLagP99Micros == null) {
                add("measurement generatorLagP99Micros is unavailable")
            } else if (generatorLagP99Micros > maxLagP99Micros) {
                add(
                    "measurement generatorLagP99Micros[$generatorLagP99Micros] > " +
                        "maxGeneratorLagP99Micros[$maxLagP99Micros]"
                )
            }
        }
    }
}
