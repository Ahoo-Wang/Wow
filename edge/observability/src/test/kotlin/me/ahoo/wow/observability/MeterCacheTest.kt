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

package me.ahoo.wow.observability

import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MeterCacheTest {
    /** Counts the meters registered (or looked up) through the registry. */
    private class CountingRegistry(val lookups: AtomicInteger = AtomicInteger()) : SimpleMeterRegistry() {
        override fun newTimer(
            id: Meter.Id,
            distributionStatisticConfig: io.micrometer.core.instrument.distribution.DistributionStatisticConfig,
            pauseDetector: io.micrometer.core.instrument.distribution.pause.PauseDetector,
        ): Timer {
            lookups.incrementAndGet()
            return super.newTimer(id, distributionStatisticConfig, pauseDetector)
        }
    }

    @Test
    fun `a timer is registered once per name and tags, and the same instance is returned after`() {
        val registry = CountingRegistry()
        val cache = MeterCache(registry)
        val first = cache.timer(WowMetrics.COMMAND_DURATION, "context", "shop", "outcome", "committed")
        val again = cache.timer(WowMetrics.COMMAND_DURATION, "context", "shop", "outcome", "committed")
        val other = cache.timer(WowMetrics.COMMAND_DURATION, "context", "shop", "outcome", "rejected")
        again.assert().isSameAs(first)
        other.assert().isNotSameAs(first)
        registry.lookups.get().assert().isEqualTo(2)
        first.record(1, TimeUnit.MILLISECONDS)
        registry.get(WowMetrics.COMMAND_DURATION).tag("outcome", "committed").timer().count().assert().isEqualTo(1L)
    }

    @Test
    fun `a counter is registered once per name and tags`() {
        val registry = SimpleMeterRegistry()
        val cache = MeterCache(registry)
        cache.counter(WowMetrics.UNDECODABLE_MESSAGES, "topic", "t").increment()
        cache.counter(WowMetrics.UNDECODABLE_MESSAGES, "topic", "t").increment()
        cache.counter(WowMetrics.UNDECODABLE_MESSAGES, "topic", "t")
            .assert().isSameAs(cache.counter(WowMetrics.UNDECODABLE_MESSAGES, "topic", "t"))
        registry.get(WowMetrics.UNDECODABLE_MESSAGES).counter().count().assert().isEqualTo(2.0)
    }
}
