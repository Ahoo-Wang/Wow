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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.Ddl
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * ClickHouse's configuration, `wow.clickhouse.*`, read when wow-clickhouse is on the classpath
 * ([ClickHouseAutoConfiguration]): the server, the Sink that writes the published streams, and the dataset queries.
 */
@InternalWowApi
@ConfigurationProperties(prefix = ClickHouseProperties.PREFIX)
class ClickHouseProperties {
    /** The server's HTTP endpoint, such as `http://localhost:8123`; ClickHouse is wired only when it is set. */
    var endpoint: String? = null

    /** The user name to sign in with. */
    var username: String = "default"

    /** The password to sign in with. */
    var password: String = ""

    /** The database that holds the tables. */
    var database: String = "default"

    /**
     * How the Sink manages its tables: `auto` creates what is missing, then validates; `validate` creates nothing and
     * refuses a table that is missing or has other columns; `none` creates and checks nothing. `wow.ddl` when not set.
     */
    var ddl: Ddl? = null

    /** The Sink that writes the Manifest's published streams to ClickHouse. */
    var sink: Sink = Sink()

    /** The datasets served from ClickHouse. */
    var query: Query = Query()

    /** The Sink that writes the Manifest's published streams to ClickHouse. */
    @InternalWowApi
    class Sink {
        /** Whether the Manifest's published streams are written to ClickHouse. */
        var enabled: Boolean = true

        /** The most records one insert carries. */
        var batchSize: Int = DEFAULT_BATCH_SIZE

        /**
         * How many recent inserts each table remembers to drop a retried one: the
         * `non_replicated_deduplication_window` of the tables `auto` creates.
         */
        var deduplicationWindow: Int = DEFAULT_DEDUPLICATION_WINDOW
    }

    /** The datasets served from ClickHouse. */
    @InternalWowApi
    class Query {
        /** Whether the datasets are served from ClickHouse. */
        var enabled: Boolean = true

        /** How long a dataset table's columns and engine are reused before `system.columns` is read again. */
        var metadataTtl: Duration = DEFAULT_METADATA_TTL

        /**
         * Server settings every dataset query runs under, such as `max_memory_usage`; the execution time comes from
         * each query's budget (`wow.query.budgets.http.timeout`), not from here.
         */
        var settings: MutableMap<String, String> = LinkedHashMap()
    }

    @InternalWowApi
    companion object {
        const val PREFIX: String = "wow.clickhouse"
        private const val DEFAULT_BATCH_SIZE = 1000
        private const val DEFAULT_DEDUPLICATION_WINDOW = 1000
        private val DEFAULT_METADATA_TTL: Duration = Duration.ofMinutes(1)
    }
}
