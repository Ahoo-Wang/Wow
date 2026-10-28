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

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import me.ahoo.wow.api.annotation.InternalWowApi

/** The kind of meter a [MetricSpec] registers. */
@InternalWowApi
enum class MetricType(val value: String) {
    TIMER("timer"),
    COUNTER("counter"),
    GAUGE("gauge"),
}

/**
 * One of Wow's metrics, which registers its meters: [name], [type] and [description], and its [tags] in the order
 * their values are given. [values] holds the closed value set of each tag whose values the framework names (an
 * outcome, a kind, a reason); any other tag takes the application's names (a context, an aggregate type, a
 * subscription, a store, a problem code).
 *
 * [unit] is the unit of a measured quantity (`seconds`), and null for a count: an exporter appends a unit to the
 * name (Prometheus' `_seconds`), so a count carries none. A timer's unit is the registry's own.
 */
@InternalWowApi
class MetricSpec internal constructor(
    val name: String,
    val type: MetricType,
    val description: String,
    val tags: List<String>,
    val values: Map<String, List<String>>,
    val unit: String? = null,
) {
    init {
        require(values.keys.all { it in tags }) { "$name lists the values of tags it does not carry." }
    }

    /** [values], one for each of [tags] in their order, as Micrometer's tags. */
    fun tagsOf(vararg values: String): Tags {
        require(values.size == tags.size) { "$name takes the tags $tags, given ${values.toList()}." }
        return Tags.of(tags.mapIndexed { at, tag -> Tag.of(tag, values[at]) })
    }

    /** This timer in [registry], with [values] of its tags. */
    fun timer(registry: MeterRegistry, vararg values: String): Timer {
        check(type == MetricType.TIMER) { "$name is a $type." }
        return Timer.builder(name).description(description).tags(tagsOf(*values)).register(registry)
    }

    /** This counter in [registry], with [values] of its tags. */
    fun counter(registry: MeterRegistry, vararg values: String): Counter {
        check(type == MetricType.COUNTER) { "$name is a $type." }
        return Counter.builder(name).description(description).baseUnit(unit).tags(tagsOf(*values)).register(registry)
    }

    /** This counter in [registry], read from [source] by [count], with [tags]. */
    fun <T : Any> functionCounter(
        registry: MeterRegistry,
        source: T,
        tags: Tags,
        count: (T) -> Double
    ): FunctionCounter {
        check(type == MetricType.COUNTER) { "$name is a $type." }
        return FunctionCounter.builder(name, source) { count(it) }
            .description(description)
            .baseUnit(unit)
            .tags(tags)
            .register(registry)
    }

    /** This gauge in [registry], read from [source] by [value], with [tags]. */
    fun <T : Any> gauge(registry: MeterRegistry, source: T, tags: Tags, value: (T) -> Double): Gauge {
        check(type == MetricType.GAUGE) { "$name is a $type." }
        return Gauge.builder(name, source) { value(it) }
            .description(description)
            .baseUnit(unit)
            .tags(tags)
            .register(registry)
    }

    override fun toString(): String = name

    internal companion object {
        fun timer(
            name: String,
            description: String,
            tags: List<String>,
            values: Map<String, List<String>> = emptyMap()
        ) =
            MetricSpec(name, MetricType.TIMER, description, tags, values)

        fun counter(name: String, description: String, tags: List<String>, values: Map<String, List<String>> = emptyMap()) =
            MetricSpec(name, MetricType.COUNTER, description, tags, values)

        fun gauge(
            name: String,
            description: String,
            tags: List<String>,
            values: Map<String, List<String>> = emptyMap(),
            unit: String? = null,
        ) = MetricSpec(name, MetricType.GAUGE, description, tags, values, unit)
    }
}
