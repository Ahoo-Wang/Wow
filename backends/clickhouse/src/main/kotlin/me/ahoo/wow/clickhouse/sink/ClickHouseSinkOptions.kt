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

package me.ahoo.wow.clickhouse.sink

import me.ahoo.wow.api.aggregate.AggregateType
import kotlin.time.Duration

/** What the Sink does with its tables before it writes (`wow.clickhouse.sink.ddl`, decision D142). */
enum class DdlMode {
    /** Creates what is missing (tables and materialized views if absent, the flat view replaced), then validates. */
    AUTO,

    /** Creates nothing; checks that every table and view exists with the columns the Sink expects. */
    VALIDATE,

    /** Neither creates nor checks: the tables are the operator's, such as replicated ones on a cluster. */
    NONE,
}

/**
 * How the Sink keeps one aggregate type's commits (decision D164).
 *
 * - [history]: whether it keeps every commit in `<ctx>_<agg>_state_events` (and every event in `<ctx>_<agg>_events`);
 *   when false only the current state is kept, written to `<ctx>_<agg>_state` directly.
 * - [historyTtl]: how long the history is kept, by commit time; forever when null. Only with [history].
 */
data class AggregateSinkOptions(val history: Boolean = true, val historyTtl: Duration? = null) {
    init {
        require(historyTtl == null || history) { "A history TTL needs the history kept." }
        require(historyTtl == null || historyTtl.inWholeSeconds > 0) { "A history TTL is at least a second." }
    }
}

/**
 * The ClickHouse Sink's options (decisions D142, D164).
 *
 * - [database]: where its tables are.
 * - [ddl]: whether it creates, validates or leaves its tables ([DdlMode]).
 * - [batchSize]: the most records one `INSERT` carries.
 * - [deduplicationWindow]: the `non_replicated_deduplication_window` of the tables it creates: how many recent insert
 *   blocks each remembers, so that a retried insert (the same `ref@version` token) is dropped.
 * - [aggregates]: per aggregate type, how its commits are kept; [AggregateSinkOptions] defaults else.
 */
data class ClickHouseSinkOptions(
    val database: String = DEFAULT_DATABASE,
    val ddl: DdlMode = DdlMode.AUTO,
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    val deduplicationWindow: Int = DEFAULT_DEDUPLICATION_WINDOW,
    val aggregates: Map<AggregateType, AggregateSinkOptions> = emptyMap(),
) {
    init {
        require(database.isNotBlank()) { "The Sink names its database." }
        require(batchSize >= 1) { "A batch holds at least one record." }
        require(deduplicationWindow >= 1) { "The deduplication window holds at least one block." }
    }

    /** How the commits of [type] are kept. */
    fun aggregate(type: AggregateType): AggregateSinkOptions = aggregates[type] ?: AggregateSinkOptions()

    companion object {
        const val DEFAULT_DATABASE: String = "default"
        const val DEFAULT_BATCH_SIZE: Int = 1000
        const val DEFAULT_DEDUPLICATION_WINDOW: Int = 1000
    }
}
