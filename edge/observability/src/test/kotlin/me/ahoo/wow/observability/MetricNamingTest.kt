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
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.FrameworkErrors
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The naming rules of Wow's metrics (decision D289), held by the catalog ([WowMetrics]); every meter the tests of this
 * module register goes through [CATALOG_ONLY], so a meter outside the catalog, or a tag outside its bounded set, fails
 * the test that registers it.
 */
class MetricNamingTest {
    @Test
    fun `every name should be OpenTelemetry's form, without a unit or a count suffix`() {
        WowMetrics.NAMES.assert().doesNotHaveDuplicates().isNotEmpty()
        WowMetrics.NAMES.forEach { name ->
            name.assert().matches(NAME.toPattern())
            UNIT_OR_COUNT.forEach { suffix -> name.assert().doesNotEndWith(suffix) }
        }
    }

    @Test
    fun `no name should be an error code's, nor another metric's namespace`() {
        val codes = FrameworkErrors.ALL.map { it.code }.toSet()
        WowMetrics.NAMES.filter { it in codes }.assert().isEmpty()
        WowMetrics.NAMES.filter { name -> WowMetrics.NAMES.any { it.startsWith("$name.") } }.assert().isEmpty()
    }

    @Test
    fun `a metric's tags should be a bounded set of span attributes, never an identifier`() {
        WowMetrics.TAGS.forEach { it.assert().matches(ATTRIBUTE.toPattern()) }
        WowMetrics.TAGS.intersect(WowMetrics.IDENTIFIERS).assert().isEmpty()
        WowMetrics.IDENTIFIERS.assert().contains(WowAttributes.AGGREGATE_ID, WowAttributes.REQUEST_ID)
    }

    @Test
    fun `a meter outside the catalog, or with a tag outside its set, should be refused`() {
        val meters = SimpleMeterRegistry().apply { config().meterFilter(CATALOG_ONLY) }
        meters.counter(WowMetrics.TAIL_READS, WowMetrics.CONTEXT, "sales").increment()
        assertThrows<IllegalStateException> { meters.counter("wow.something.else") }
        assertThrows<IllegalStateException> {
            meters.counter(WowMetrics.TAIL_READS, WowAttributes.AGGREGATE_ID, "a-1")
        }
    }

    companion object {
        private val NAME = Regex("^wow(\\.[a-z][a-z0-9_]*)+$")
        private val ATTRIBUTE = Regex("^[a-z]+(\\.[a-z][a-z0-9_]*)+$")
        private val UNIT_OR_COUNT = listOf(".count", ".total", "_total", "_seconds", "_ms", "_bytes")

        /** Refuses a meter whose name is not in [WowMetrics.NAMES], or with a tag outside [WowMetrics.TAGS]. */
        val CATALOG_ONLY: MeterFilter = object : MeterFilter {
            override fun map(id: Meter.Id): Meter.Id {
                check(id.name in WowMetrics.NAMES) { "${id.name} is not in WowMetrics.NAMES." }
                val outside = id.tags.map { it.key }.filterNot { it in WowMetrics.TAGS }
                check(outside.isEmpty()) { "${id.name} carries tags outside WowMetrics.TAGS: $outside." }
                return id
            }
        }
    }
}
