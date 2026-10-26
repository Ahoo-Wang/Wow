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

package me.ahoo.wow.spring.boot

import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.transport.PartitionedSource
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

class TransportAutoConfigurationTest {

    @Test
    fun `the offset gaps counter should keep reading its source after a garbage collection`() {
        val registry = SimpleMeterRegistry()
        val reads = AtomicInteger()
        val counter = register(registry, reads)
        collectGarbage()
        val before = reads.get()
        counter.count()
        // Finding R2.3-4: a weakly held provider is gone after the collection, and the counter freezes.
        reads.get().assert().isGreaterThan(before)
        registry.get(TransportAutoConfiguration.OFFSET_GAPS).functionCounter().count().assert().isEqualTo(0.0)
    }

    /** Registers the counter over a provider only the meter can hold, counting the provider's reads in [reads]. */
    private fun register(registry: MeterRegistry, reads: AtomicInteger): FunctionCounter {
        val factory = DefaultListableBeanFactory()
        val delegate = factory.getBeanProvider(PartitionedSource::class.java)
        val sources = object : ObjectProvider<PartitionedSource> by delegate {
            override fun getIfUnique(): PartitionedSource? {
                reads.incrementAndGet()
                return delegate.getIfUnique()
            }
        }
        val meters = factory.getBeanProvider(MeterRegistry::class.java)
        factory.registerSingleton("registry", registry)
        return TransportAutoConfiguration.TransportMetrics().wowOffsetGaps(sources, meters)
    }

    private fun collectGarbage() {
        val sentinel = WeakReference(Any())
        repeat(GC_ATTEMPTS) {
            if (sentinel.get() == null) return
            System.gc()
            Thread.sleep(GC_PAUSE_MILLIS)
        }
    }

    private companion object {
        const val GC_ATTEMPTS = 50
        const val GC_PAUSE_MILLIS = 10L
    }
}
