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

package me.ahoo.wow.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.LongTaskTimer
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import java.util.concurrent.ConcurrentHashMap

/**
 * The meters of one [MeterRegistry], kept per [MetricDescriptor], so a recording on the hot path neither builds
 * [Tags] nor looks the meter up in the registry. Names and tags are exactly those the registry lookups used.
 *
 * Meters are created on first use through the registry, so its filters still apply. A creation that fails is not
 * kept and is tried again on the next recording. Removing any meter from the registry drops the whole cache, so a
 * recording after a removal registers the meter again, as a registry lookup would.
 */
internal class MeterCache(
    val registry: MeterRegistry,
) {
    private val descriptors = ConcurrentHashMap<MetricDescriptor, DescriptorMeters>()

    init {
        registry.config().onMeterRemoved { descriptors.clear() }
    }

    fun of(descriptor: MetricDescriptor): DescriptorMeters =
        descriptors[descriptor] ?: descriptors.computeIfAbsent(descriptor) { DescriptorMeters(registry, it) }

    internal val size: Int
        get() = descriptors.size
}

/** The meters of one [MetricDescriptor]. */
internal class DescriptorMeters(
    private val registry: MeterRegistry,
    private val descriptor: MetricDescriptor,
) {
    private val baseTags: Tags = descriptor.baseTags()
    private val terminals = Array(MetricOutcome.entries.size) { ConcurrentHashMap<String, TerminalMeters>() }
    private val processingOutcomes = ConcurrentHashMap<String, Counter>()

    @Volatile
    private var streamActive: LongTaskTimer? = null

    @Volatile
    private var streamMessages: Counter? = null

    /** The meters tagged with [outcome] and [exception] besides the descriptor's tags. */
    fun terminal(outcome: MetricOutcome, exception: String): TerminalMeters {
        val byException = terminals[outcome.ordinal]
        return byException[exception] ?: byException.computeIfAbsent(exception) {
            TerminalMeters(registry, descriptor.terminalTags(outcome, it))
        }
    }

    fun processingOutcome(outcome: String): Counter =
        processingOutcomes[outcome] ?: registry.counter(
            WowMetricNames.PROCESSING_OUTCOMES,
            baseTags.and(MetricDescriptor.OUTCOME_TAG, outcome),
        ).also { processingOutcomes[outcome] = it }

    fun streamActive(): LongTaskTimer =
        streamActive ?: registry.more()
            .longTaskTimer(WowMetricNames.STREAM_ACTIVE, baseTags)
            .also { streamActive = it }

    fun streamMessages(): Counter =
        streamMessages ?: registry.counter(WowMetricNames.STREAM_MESSAGES, baseTags)
            .also { streamMessages = it }
}

/** The meters that share one set of terminal tags (descriptor, outcome and exception). */
internal class TerminalMeters(
    private val registry: MeterRegistry,
    private val tags: Tags,
) {
    @Volatile
    private var operation: Timer? = null

    @Volatile
    private var operationItems: DistributionSummary? = null

    @Volatile
    private var streamTerminations: Counter? = null

    fun operation(): Timer =
        operation ?: registry.timer(WowMetricNames.OPERATION, tags).also { operation = it }

    fun operationItems(): DistributionSummary =
        operationItems ?: registry.summary(WowMetricNames.OPERATION_ITEMS, tags).also { operationItems = it }

    fun streamTerminations(): Counter =
        streamTerminations ?: registry.counter(WowMetricNames.STREAM_TERMINATIONS, tags)
            .also { streamTerminations = it }
}
