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
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.ConcurrentHashMap

/**
 * The timers and counters of [registry] by name and tags, registered once: a lookup of the registry
 * builds its `Tags` and `Meter.Id` and hashes them on every call, once per command and per entry and subscription on
 * the hot paths. Wow's tags are bounded (contexts, aggregate types, subscriptions, outcomes; never an id), so the
 * cache is too.
 */
internal class MeterCache(private val registry: MeterRegistry) {
    private val timers = ConcurrentHashMap<MeterKey, Timer>()
    private val counters = ConcurrentHashMap<MeterKey, Counter>()

    /** The timer [name] with [tags], as name and value pairs. */
    fun timer(name: String, vararg tags: String): Timer =
        timers.computeIfAbsent(MeterKey(name, tags)) { registry.timer(name, *tags) }

    /** The counter [name] with [tags], as name and value pairs. */
    fun counter(name: String, vararg tags: String): Counter =
        counters.computeIfAbsent(MeterKey(name, tags)) { registry.counter(name, *tags) }

    private class MeterKey(val name: String, val tags: Array<out String>) {
        private val hash = 31 * name.hashCode() + tags.contentHashCode()

        override fun equals(other: Any?): Boolean =
            other is MeterKey && hash == other.hash && name == other.name && tags.contentEquals(other.tags)

        override fun hashCode(): Int = hash
    }
}
