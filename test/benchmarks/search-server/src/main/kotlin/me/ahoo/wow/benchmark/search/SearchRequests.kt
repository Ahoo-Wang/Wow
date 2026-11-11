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

package me.ahoo.wow.benchmark.search

import org.apache.hc.core5.http.EntityDetails
import org.apache.hc.core5.http.HttpRequest
import org.apache.hc.core5.http.HttpRequestInterceptor
import org.apache.hc.core5.http.protocol.HttpContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.LongAdder

/**
 * The requests an Elasticsearch client sends, counted by kind as an interceptor of its HTTP client, which sees each
 * request once, as sent: the last segment of the path when it is one of [KINDS] (`_bulk`, `_refresh`,
 * `_update_by_query`), otherwise `other`. So a measurement reads what the sink sent with nothing in the store that
 * counts for a benchmark. The counts are cumulative from the start and logged once a second and on stop
 * (`search-requests {json}` at WARN, the level the server logs at), as BenchSignals logs its own.
 */
class SearchRequests : HttpRequestInterceptor, AutoCloseable {
    private val counts = ConcurrentHashMap<String, LongAdder>()
    private val ticker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "search-requests").apply { isDaemon = true }
    }

    fun start() {
        ticker.scheduleAtFixedRate({ log.warn("search-requests {}", snapshot()) }, 1, 1, TimeUnit.SECONDS)
    }

    override fun close() {
        ticker.shutdownNow()
        log.warn("search-requests {}", snapshot())
    }

    override fun process(request: HttpRequest, entity: EntityDetails?, context: HttpContext?) {
        counts.computeIfAbsent(kindOf(request.path)) { LongAdder() }.increment()
    }

    /** The counts as one JSON object, each of [KINDS] and `other` present: `{"_bulk": <count>, …}`. */
    fun snapshot(): String =
        (KINDS + OTHER).joinToString(",", "{", "}") { "\"$it\":${counts[it]?.sum() ?: 0L}" }

    companion object {
        private val log = LoggerFactory.getLogger(SearchRequests::class.java)

        /** The kinds of request counted apart. */
        val KINDS: List<String> = listOf("_bulk", "_refresh", "_update_by_query")
        private const val OTHER = "other"

        /** The kind of a request to [path], with its query, if any. */
        fun kindOf(path: String?): String =
            path.orEmpty().substringBefore('?').trimEnd('/').substringAfterLast('/').takeIf { it in KINDS } ?: OTHER
    }
}
