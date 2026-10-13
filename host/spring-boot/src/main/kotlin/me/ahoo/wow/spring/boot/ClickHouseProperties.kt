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

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * ClickHouse's configuration, `wow.clickhouse.*` (decisions D110, D142, D164), read when wow-clickhouse is on the
 * classpath ([ClickHouseAutoConfiguration]):
 *
 * - `wow.clickhouse.endpoint`: the server's HTTP endpoint, such as `http://localhost:8123`; ClickHouse is wired only
 *   when it is set. `username` (`default`) and `password` (empty) sign in; `database` (`default`) holds the tables.
 * - `wow.clickhouse.sink.enabled`: whether the Manifest's published streams are written to ClickHouse; on by default.
 * - `wow.clickhouse.sink.ddl`: `auto` (create what is missing, then validate; the default), `validate` or `none`.
 * - `wow.clickhouse.sink.batch-size`: the most records one insert carries (1000).
 * - `wow.clickhouse.sink.deduplication-window`: how many recent inserts each table remembers to drop a retried one
 *   (1000), the `non_replicated_deduplication_window` of the tables `auto` creates.
 * - `wow.clickhouse.sink.aggregates.<context>.<aggregate>.history.enabled`: whether every commit is kept (on by
 *   default); off keeps only the current state. `...history.ttl`: how long the history is kept, forever when unset.
 */
@ConfigurationProperties(prefix = ClickHouseProperties.PREFIX)
class ClickHouseProperties {
    var endpoint: String? = null
    var username: String = "default"
    var password: String = ""
    var database: String = "default"
    var sink: Sink = Sink()

    class Sink {
        var enabled: Boolean = true
        var ddl: String = "auto"
        var batchSize: Int = DEFAULT_BATCH_SIZE
        var deduplicationWindow: Int = DEFAULT_DEDUPLICATION_WINDOW

        /** By context, then by aggregate name. */
        var aggregates: MutableMap<String, MutableMap<String, Aggregate>> = LinkedHashMap()
    }

    class Aggregate {
        var history: History = History()
    }

    class History {
        var enabled: Boolean = true
        var ttl: Duration? = null
    }

    companion object {
        const val PREFIX: String = "wow.clickhouse"
        private const val DEFAULT_BATCH_SIZE = 1000
        private const val DEFAULT_DEDUPLICATION_WINDOW = 1000
    }
}
