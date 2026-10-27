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
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.Ddl
import kotlin.time.Duration

/**
 * How the Sink keeps one aggregate type's commits.
 *
 * - [history]: whether it keeps every commit in `<ctx>_<agg>_state_events` (and every event in `<ctx>_<agg>_events`);
 *   when false only the current state is kept, written to `<ctx>_<agg>_state` directly.
 * - [historyTtl]: how long the history is kept, by commit time; forever when null. Only with [history].
 *
 * Fields may be added: Wow builds it from the host's configuration, so the constructor is internal
 * to Wow.
 */
class AggregateSinkOptions @InternalWowApi constructor(
    val history: Boolean = true,
    val historyTtl: Duration? = null,
) {
    init {
        require(historyTtl == null || history) { "A history TTL needs the history kept." }
        require(historyTtl == null || historyTtl.inWholeSeconds > 0) { "A history TTL is at least a second." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is AggregateSinkOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "AggregateSinkOptions(history=$history, historyTtl=$historyTtl)"

    private fun valueFields(): List<Any?> = listOf(history, historyTtl)
}

/**
 * The ClickHouse Sink's options.
 *
 * - [database]: where its tables are.
 * - [ddl]: whether it creates, validates or leaves its tables ([Ddl]).
 * - [batchSize]: the most records one `INSERT` carries.
 * - [deduplicationWindow]: the `non_replicated_deduplication_window` of the tables it creates: how many recent insert
 *   blocks each remembers, so that a retried insert (the same `ref@version` token) is dropped.
 * - [aggregates]: per aggregate type, how its commits are kept; [AggregateSinkOptions] defaults else.
 *
 * Fields may be added: Wow builds it from the host's configuration, so the constructor is internal
 * to Wow.
 */
class ClickHouseSinkOptions @InternalWowApi constructor(
    val database: String = DEFAULT_DATABASE,
    val ddl: Ddl = Ddl.AUTO,
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

    override fun equals(other: Any?): Boolean =
        this === other || other is ClickHouseSinkOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "ClickHouseSinkOptions(database=$database, ddl=$ddl, batchSize=$batchSize, " +
            "deduplicationWindow=$deduplicationWindow, aggregates=$aggregates)"

    private fun valueFields(): List<Any?> = listOf(database, ddl, batchSize, deduplicationWindow, aggregates)

    companion object {
        const val DEFAULT_DATABASE: String = "default"
        const val DEFAULT_BATCH_SIZE: Int = 1000
        const val DEFAULT_DEDUPLICATION_WINDOW: Int = 1000
    }
}
