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
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The naming rules of Wow's metrics and span attributes, held by the catalog ([WowMetrics], [WowAttributes]); every
 * meter the tests of this module register goes through [CATALOG_ONLY], so a meter outside the catalog, a tag outside
 * its metric's, or a value outside its tag's closed set fails the test that registers it. The snapshot [SNAPSHOT],
 * which the documentation's test holds the observability reference to, is the catalog's.
 */
class MetricNamingTest {
    private val names = WowMetrics.ALL.map { it.name }

    @Test
    fun `every name should be OpenTelemetry's form, without a unit or a count suffix`() {
        names.assert().doesNotHaveDuplicates().isNotEmpty()
        names.forEach { name ->
            name.assert().matches(NAME.toPattern())
            UNIT_OR_COUNT.forEach { suffix -> name.assert().doesNotEndWith(suffix) }
        }
    }

    @Test
    fun `no name should be an error code's, nor another metric's namespace`() {
        val codes = FrameworkErrors.ALL.map { it.code }.toSet()
        names.filter { it in codes }.assert().isEmpty()
        names.filter { name -> names.any { it.startsWith("$name.") } }.assert().isEmpty()
    }

    @Test
    fun `no attribute should be another attribute's namespace`() {
        val attributes = (WowAttributes.NAMES + WowMetrics.ALL.flatMap { it.tags }).toSet()
        attributes.forEach { it.assert().matches(ATTRIBUTE.toPattern()) }
        attributes.filter { name -> attributes.any { it.startsWith("$name.") } }.assert().isEmpty()
    }

    @Test
    fun `a metric's tags should be its own bounded attributes, never an identifier, its closed values lower case`() {
        WowMetrics.ALL.forEach { metric ->
            metric.tags.assert().describedAs(metric.name).doesNotHaveDuplicates()
            metric.tags.intersect(WowMetrics.IDENTIFIERS).assert().describedAs(metric.name).isEmpty()
            metric.values.values.flatten().forEach { it.assert().describedAs(metric.name).matches(VALUE.toPattern()) }
        }
        WowMetrics.IDENTIFIERS.assert().contains(WowAttributes.AGGREGATE_ID, WowAttributes.REQUEST_ID)
    }

    @Test
    fun `a meter outside the catalog, with a tag outside its own, or a value outside its set, should be refused`() {
        val meters = SimpleMeterRegistry().apply { config().meterFilter(CATALOG_ONLY) }
        WowMetrics.TAIL_READS.counter(meters, "sales", "gap").increment()
        assertThrows<IllegalStateException> { meters.counter("wow.something.else") }
        assertThrows<IllegalStateException> { meters.counter(WowMetrics.TAIL_READS.name, WowMetrics.CONTEXT, "sales") }
        assertThrows<IllegalStateException> { WowMetrics.TAIL_READS.counter(meters, "sales", "Gap") }
        assertThrows<IllegalArgumentException> { WowMetrics.TAIL_READS.counter(meters, "sales") }
        assertThrows<IllegalStateException> { WowMetrics.TAIL_READS.timer(meters, "sales", "gap") }
    }

    @Test
    fun `the snapshot the documentation is held to should be this catalog`() {
        val expected = WowMetrics.ALL.joinToString(",\n", "[\n", "\n]\n") { metric ->
            val values = metric.values.entries.joinToString(", ", "{", "}") { (tag, values) ->
                "${json(tag)}: ${values.joinToString(", ", "[", "]") { json(it) }}"
            }
            """  { "name": ${json(metric.name)}, "type": ${json(metric.type.value)}, """ +
                """"unit": ${metric.unit?.let(::json) ?: "null"}, "tags": ${metric.tags.joinToString(", ", "[", "]") { json(it) }}, """ +
                """"values": $values, "description": ${json(metric.description)} }"""
        }
        val snapshot = Path.of(SNAPSHOT)
        if (System.getenv(UPDATE) != null) {
            snapshot.writeText(expected)
        }
        snapshot.readText().assert().describedAs("Run with $UPDATE=1 to rewrite $SNAPSHOT.").isEqualTo(expected)
    }

    private fun json(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        private val NAME = Regex("^wow(\\.[a-z][a-z0-9_]*)+$")
        private val ATTRIBUTE = Regex("^[a-z]+(\\.[a-z][a-z0-9_]*)+$")
        private val VALUE = Regex("^[a-z][a-z0-9-]*$")
        private val UNIT_OR_COUNT = listOf(".count", ".total", "_total", "_seconds", "_ms", "_bytes")

        /** Read by documentation/test/observability-names.test.mjs. */
        private const val SNAPSHOT = "src/test/resources/metrics.json"
        private const val UPDATE = "WOW_UPDATE_SNAPSHOTS"

        private val CATALOG: Map<String, MetricSpec> = WowMetrics.ALL.associateBy { it.name }

        /**
         * Refuses a meter whose name is not in [WowMetrics.ALL], whose tags are not its metric's, or whose value of a
         * closed tag is not in its set.
         */
        val CATALOG_ONLY: MeterFilter = object : MeterFilter {
            override fun map(id: Meter.Id): Meter.Id {
                val metric = checkNotNull(CATALOG[id.name]) { "${id.name} is not in WowMetrics.ALL." }
                val tags = id.tags.associate { it.key to it.value }
                check(
                    tags.keys == metric.tags.toSet()
                ) { "${id.name} carries the tags ${tags.keys}, not ${metric.tags}." }
                metric.values.forEach { (tag, values) ->
                    check(tags.getValue(tag) in values) { "${id.name}: ${tags[tag]} is not a value of $tag: $values." }
                }
                return id
            }
        }
    }
}
