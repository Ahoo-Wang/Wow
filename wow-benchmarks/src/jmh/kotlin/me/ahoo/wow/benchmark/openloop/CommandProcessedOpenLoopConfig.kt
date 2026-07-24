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

package me.ahoo.wow.benchmark.openloop

import me.ahoo.wow.benchmark.workload.OpenLoopObservationMode
import me.ahoo.wow.messaging.dispatcher.MessageParallelism
import reactor.core.scheduler.Schedulers
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class CommandProcessedOpenLoopConfig(
    val runId: String,
    val resultPath: Path,
    val humanPath: Path,
    val ratePerSecond: Long,
    val warmupNanos: Long,
    val measurementNanos: Long,
    val producerCount: Int,
    val maxInFlight: Int,
    val requestTimeoutNanos: Long,
    val watchdogIntervalNanos: Long,
    val startLeadNanos: Long,
    val maxGeneratorMissedRatio: Double,
    val maxGeneratorLagP99Nanos: Long,
    val observationMode: OpenLoopObservationMode,
    val schedulerPoolSizeToken: String,
    val schedulerPoolSize: Int,
    val stripeCountToken: String,
    val stripeCount: Int,
    val aggregateCardinality: String,
) {
    val totalDurationNanos: Long =
        Math.addExact(warmupNanos, measurementNanos)

    val requestTimeoutMillis: Long =
        TimeUnit.NANOSECONDS.toMillis(requestTimeoutNanos)

    val maxGeneratorLagP99Millis: Long =
        TimeUnit.NANOSECONDS.toMillis(maxGeneratorLagP99Nanos)

    companion object {
        private const val DEFAULT_RATE_PER_SECOND = 200_000L
        private const val DEFAULT_WARMUP_SECONDS = 10L
        private const val DEFAULT_MEASUREMENT_SECONDS = 20L
        private const val DEFAULT_PRODUCER_COUNT = 16
        private const val DEFAULT_MAX_IN_FLIGHT = 65_536
        private const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 5_000L
        private const val DEFAULT_WATCHDOG_INTERVAL_MILLIS = 5L
        private const val DEFAULT_START_LEAD_MILLIS = 250L
        private const val DEFAULT_MAX_GENERATOR_MISSED_RATIO = 0.001
        private const val DEFAULT_MAX_GENERATOR_LAG_P99_MILLIS = 5L

        fun parse(arguments: Array<String>): CommandProcessedOpenLoopConfig {
            val values = arguments.associate { argument ->
                require(argument.startsWith("--") && argument.contains('=')) {
                    "Arguments must use --key=value syntax: $argument"
                }
                val (key, value) = argument.removePrefix("--").split('=', limit = 2)
                require(key.isNotBlank() && value.isNotBlank()) {
                    "Arguments must use non-blank --key=value syntax: $argument"
                }
                key to value
            }
            val unknown = values.keys - SUPPORTED_ARGUMENTS
            require(unknown.isEmpty()) {
                "Unsupported argument(s): ${unknown.sorted()}."
            }

            val runId = values["runId"] ?: UUID.randomUUID().toString()
            val resultPath = Path.of(
                values["resultPath"]
                    ?: error("--resultPath is required.")
            ).toAbsolutePath().normalize()
            val humanPath = Path.of(
                values["humanPath"]
                    ?: error("--humanPath is required.")
            ).toAbsolutePath().normalize()
            require(resultPath != humanPath) {
                "resultPath and humanPath must be different files."
            }

            val schedulerPoolSizeArgument = values["schedulerPoolSize"] ?: "cpu"
            val schedulerPoolSizeToken =
                values["schedulerPoolSizeToken"] ?: schedulerPoolSizeArgument
            val schedulerPoolSize =
                if ("schedulerPoolSizeToken" in values) {
                    resolveExplicitPositiveToken(
                        name = "schedulerPoolSize",
                        tokenName = "schedulerPoolSizeToken",
                        token = schedulerPoolSizeToken,
                        namedDefault = "cpu",
                        rawValue = schedulerPoolSizeArgument,
                    )
                } else {
                    resolvePositiveToken(
                        name = "schedulerPoolSize",
                        token = schedulerPoolSizeToken,
                        namedDefault = "cpu",
                        defaultValue = Schedulers.DEFAULT_POOL_SIZE,
                    )
                }
            val stripeCountArgument = values["stripeCount"] ?: "default"
            val stripeCountToken = values["stripeCountToken"] ?: stripeCountArgument
            val stripeCount =
                if ("stripeCountToken" in values) {
                    resolveExplicitPositiveToken(
                        name = "stripeCount",
                        tokenName = "stripeCountToken",
                        token = stripeCountToken,
                        namedDefault = "default",
                        rawValue = stripeCountArgument,
                    )
                } else {
                    resolvePositiveToken(
                        name = "stripeCount",
                        token = stripeCountToken,
                        namedDefault = "default",
                        defaultValue = MessageParallelism.DEFAULT_PARALLELISM,
                    )
                }

            return CommandProcessedOpenLoopConfig(
                runId = runId,
                resultPath = resultPath,
                humanPath = humanPath,
                ratePerSecond = positiveLong(
                    "ratePerSecond",
                    values["ratePerSecond"] ?: DEFAULT_RATE_PER_SECOND.toString(),
                ),
                warmupNanos = secondsToNanos(
                    "warmupSeconds",
                    values["warmupSeconds"] ?: DEFAULT_WARMUP_SECONDS.toString(),
                ),
                measurementNanos = secondsToNanos(
                    "measurementSeconds",
                    values["measurementSeconds"] ?: DEFAULT_MEASUREMENT_SECONDS.toString(),
                ),
                producerCount = positiveInt(
                    "producerCount",
                    values["producerCount"] ?: DEFAULT_PRODUCER_COUNT.toString(),
                ),
                maxInFlight = positiveInt(
                    "maxInFlight",
                    values["maxInFlight"] ?: DEFAULT_MAX_IN_FLIGHT.toString(),
                ),
                requestTimeoutNanos = millisToNanos(
                    "requestTimeoutMillis",
                    values["requestTimeoutMillis"] ?: DEFAULT_REQUEST_TIMEOUT_MILLIS.toString(),
                ),
                watchdogIntervalNanos = millisToNanos(
                    "watchdogIntervalMillis",
                    values["watchdogIntervalMillis"]
                        ?: DEFAULT_WATCHDOG_INTERVAL_MILLIS.toString(),
                ),
                startLeadNanos = millisToNanos(
                    "startLeadMillis",
                    values["startLeadMillis"] ?: DEFAULT_START_LEAD_MILLIS.toString(),
                ),
                maxGeneratorMissedRatio = ratio(
                    "maxGeneratorMissedRatio",
                    values["maxGeneratorMissedRatio"]
                        ?: DEFAULT_MAX_GENERATOR_MISSED_RATIO.toString(),
                ),
                maxGeneratorLagP99Nanos = millisToNanos(
                    "maxGeneratorLagP99Millis",
                    values["maxGeneratorLagP99Millis"]
                        ?: DEFAULT_MAX_GENERATOR_LAG_P99_MILLIS.toString(),
                ),
                observationMode = OpenLoopObservationMode.parse(
                    values["observationMode"] ?: OpenLoopObservationMode.FULL.name,
                ),
                schedulerPoolSizeToken = schedulerPoolSizeToken,
                schedulerPoolSize = schedulerPoolSize,
                stripeCountToken = stripeCountToken,
                stripeCount = stripeCount,
                aggregateCardinality = values["aggregateCardinality"] ?: "high",
            )
        }

        private fun positiveInt(
            name: String,
            rawValue: String,
        ): Int {
            val value = rawValue.toIntOrNull()
            require(value != null && value > 0) {
                "$name must be a positive integer: $rawValue"
            }
            return value
        }

        private fun positiveLong(
            name: String,
            rawValue: String,
        ): Long {
            val value = rawValue.toLongOrNull()
            require(value != null && value > 0) {
                "$name must be a positive integer: $rawValue"
            }
            return value
        }

        private fun ratio(
            name: String,
            rawValue: String,
        ): Double {
            val value = rawValue.toDoubleOrNull()
            require(value != null && value.isFinite() && value in 0.0..1.0) {
                "$name must be a finite number between 0 and 1: $rawValue"
            }
            return value
        }

        private fun secondsToNanos(
            name: String,
            rawValue: String,
        ): Long =
            runCatching {
                Math.multiplyExact(
                    positiveLong(name, rawValue),
                    NANOS_PER_SECOND,
                )
            }.getOrElse {
                throw IllegalArgumentException("$name is too large: $rawValue", it)
            }

        private fun millisToNanos(
            name: String,
            rawValue: String,
        ): Long =
            runCatching {
                Math.multiplyExact(
                    positiveLong(name, rawValue),
                    NANOS_PER_MILLISECOND,
                )
            }.getOrElse {
                throw IllegalArgumentException("$name is too large: $rawValue", it)
            }

        private fun resolvePositiveToken(
            name: String,
            token: String,
            namedDefault: String,
            defaultValue: Int,
        ): Int =
            if (token == namedDefault) {
                defaultValue
            } else {
                positiveInt(name, token)
            }

        private fun resolveExplicitPositiveToken(
            name: String,
            tokenName: String,
            token: String,
            namedDefault: String,
            rawValue: String,
        ): Int {
            val resolved = positiveInt(name, rawValue)
            if (token != namedDefault) {
                val tokenValue = positiveInt(tokenName, token)
                require(tokenValue == resolved) {
                    "$tokenName[$token] does not match resolved $name[$resolved]."
                }
            }
            return resolved
        }

        private val SUPPORTED_ARGUMENTS = setOf(
            "runId",
            "resultPath",
            "humanPath",
            "ratePerSecond",
            "warmupSeconds",
            "measurementSeconds",
            "producerCount",
            "maxInFlight",
            "requestTimeoutMillis",
            "watchdogIntervalMillis",
            "startLeadMillis",
            "maxGeneratorMissedRatio",
            "maxGeneratorLagP99Millis",
            "observationMode",
            "schedulerPoolSizeToken",
            "schedulerPoolSize",
            "stripeCountToken",
            "stripeCount",
            "aggregateCardinality",
        )

        private const val NANOS_PER_SECOND: Long = 1_000_000_000
        private const val NANOS_PER_MILLISECOND: Long = 1_000_000
    }
}
