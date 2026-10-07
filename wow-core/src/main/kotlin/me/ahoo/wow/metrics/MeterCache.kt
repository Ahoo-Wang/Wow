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
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The meters of one [MeterRegistry], kept per [MetricDescriptor], so a recording on the hot path neither builds
 * [Tags] nor looks the meter up in the registry. Names and tags are exactly those the registry lookups used.
 *
 * Meters are created on first use through the registry, so the filters configured by then apply to them, renames
 * and common tags included. A [io.micrometer.core.instrument.config.MeterFilter] added after a meter's first use does
 * not reach that cached meter; Spring Boot applies its filters before it injects the registry, so this only concerns
 * filters added by hand later. A creation that fails is not kept and is tried again on the next recording.
 *
 * Removing one of the meters this cache handed out drops the whole cache, so a recording after that removal
 * registers the meter again, as a registry lookup would. Removing any other meter leaves the cache alone. The removal
 * listener is registered on the registry and lives as long as the registry does (Micrometer cannot unregister it).
 */
internal class MeterCache(
    val registry: MeterRegistry,
) {
    private val descriptors = ConcurrentHashMap<MetricDescriptor, DescriptorMeters>()

    /** The ids (after the registry's filters) of the meters this cache handed out. */
    private val ownedIds = ConcurrentHashMap.newKeySet<Meter.Id>()

    /** Meters removed from the registry so far, any meter: see [own]. */
    private val removals = AtomicLong()

    init {
        registry.config().onMeterRemoved { removed ->
            removals.incrementAndGet()
            if (ownedIds.remove(removed.id)) {
                descriptors.clear()
            }
        }
    }

    fun of(descriptor: MetricDescriptor): DescriptorMeters =
        descriptors[descriptor] ?: descriptors.computeIfAbsent(descriptor) { DescriptorMeters(this, it) }

    /**
     * Resolves a meter through the registry ([resolve]) and remembers it as handed out by this cache, so its removal
     * drops the cache. A removal that runs after [resolve] got the meter but before it is remembered finds no owned
     * id and drops nothing; so when any meter was removed meanwhile, the meter is looked up once more, and if it is no
     * longer registered the cache is dropped here and the next recording registers it again. Runs only when a meter
     * is first resolved, not per recording.
     */
    fun <M : Meter> own(resolve: () -> M): M {
        val removalsBefore = removals.get()
        val meter = resolve()
        ownedIds.add(meter.id)
        // Pairs with the listener, which counts the removal before it checks ownedIds: either it sees this id, or
        // this sees its count.
        if (removals.get() != removalsBefore && !isRegistered(meter)) {
            ownedIds.remove(meter.id)
            descriptors.clear()
        }
        return meter
    }

    private fun isRegistered(meter: Meter): Boolean =
        registry.find(meter.id.name).tags(meter.id.tags).meters().any { it === meter }

    internal val size: Int
        get() = descriptors.size
}

/** The meters of one [MetricDescriptor]. */
internal class DescriptorMeters(
    private val cache: MeterCache,
    private val descriptor: MetricDescriptor,
) {
    private val registry: MeterRegistry = cache.registry
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
            TerminalMeters(cache, descriptor.terminalTags(outcome, it))
        }
    }

    fun processingOutcome(outcome: String): Counter =
        processingOutcomes[outcome] ?: cache.own {
            registry.counter(
                WowMetricNames.PROCESSING_OUTCOMES,
                baseTags.and(MetricDescriptor.OUTCOME_TAG, outcome),
            )
        }.also { processingOutcomes[outcome] = it }

    fun streamActive(): LongTaskTimer =
        streamActive ?: cache.own { registry.more().longTaskTimer(WowMetricNames.STREAM_ACTIVE, baseTags) }
            .also { streamActive = it }

    fun streamMessages(): Counter =
        streamMessages ?: cache.own { registry.counter(WowMetricNames.STREAM_MESSAGES, baseTags) }
            .also { streamMessages = it }
}

/** The meters that share one set of terminal tags (descriptor, outcome and exception). */
internal class TerminalMeters(
    private val cache: MeterCache,
    private val tags: Tags,
) {
    private val registry: MeterRegistry = cache.registry

    @Volatile
    private var operation: Timer? = null

    @Volatile
    private var operationItems: DistributionSummary? = null

    @Volatile
    private var streamTerminations: Counter? = null

    fun operation(): Timer =
        operation ?: cache.own { registry.timer(WowMetricNames.OPERATION, tags) }.also { operation = it }

    fun operationItems(): DistributionSummary =
        operationItems ?: cache.own { registry.summary(WowMetricNames.OPERATION_ITEMS, tags) }
            .also { operationItems = it }

    fun streamTerminations(): Counter =
        streamTerminations ?: cache.own { registry.counter(WowMetricNames.STREAM_TERMINATIONS, tags) }
            .also { streamTerminations = it }
}
