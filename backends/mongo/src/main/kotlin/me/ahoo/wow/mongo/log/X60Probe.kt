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

package me.ahoo.wow.mongo.log

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.LongAdder

/**
 * TEMPORARY (X-60, never merged): counts of the MongoDB log's internal steps, logged once a second as
 * `x60-mongo {json}`, cumulative from the first count.
 */
internal object X60Probe {
    private val logger = KotlinLogging.logger {}
    private val counters = ConcurrentHashMap<String, LongAdder>()

    private val ticker by lazy {
        Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "x60-mongo").apply { isDaemon = true } }
            .also { it.scheduleAtFixedRate({ logger.warn { "x60-mongo ${snapshot()}" } }, 1, 1, TimeUnit.SECONDS) }
    }

    fun count(key: String, n: Long = 1) {
        ticker
        counters.computeIfAbsent(key) { LongAdder() }.add(n)
    }

    fun snapshot(): String = counters.toSortedMap().entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value.sum()}" }
}
