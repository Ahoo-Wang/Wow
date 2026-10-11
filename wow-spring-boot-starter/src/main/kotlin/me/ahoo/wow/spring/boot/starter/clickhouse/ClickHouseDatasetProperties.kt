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

package me.ahoo.wow.spring.boot.starter.clickhouse

import me.ahoo.wow.api.Wow
import me.ahoo.wow.clickhouse.query.ClickHouseDatasetOptions
import me.ahoo.wow.clickhouse.query.ClickHouseQueryLimits
import me.ahoo.wow.query.QueryEntry
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import java.net.URI
import java.time.Duration

/**
 * The ClickHouse server the datasets of `wow.query.datasets` are read from (`clickhouse-support`).
 *
 * @property enabled serves datasets from ClickHouse.
 * @property endpoints the HTTP endpoints, such as `http://clickhouse:8123`.
 * @property username the account; give Wow a read-only one.
 * @property password the account's password.
 * @property database the database of a dataset whose `storage.table` names none.
 * @property connectionTimeout how long to wait for a connection.
 * @property socketTimeout how long to wait for a response to make progress.
 * @property maxConnections the connection pool limit.
 * @property metadataTtl how long a table's columns and engine are reused before `system.columns` is read again.
 * @property http the limits of an HTTP query, sent as server settings; an over-limit query fails.
 * @property inProcess the limits of an in-process query; none by default, as in-process callers are trusted code.
 * @property sources other ClickHouse servers, by name, that a dataset reads from when its `storage.source` names one
 * (dataset platform design §5.1); the settings above are the default source's. The limits apply to every source.
 */
@ConfigurationProperties(prefix = ClickHouseDatasetProperties.PREFIX)
data class ClickHouseDatasetProperties(
    var enabled: Boolean = false,
    var endpoints: List<URI> = emptyList(),
    var username: String = "default",
    var password: String = "",
    var database: String = "default",
    var connectionTimeout: Duration = Duration.ofSeconds(5),
    var socketTimeout: Duration = Duration.ofSeconds(60),
    var maxConnections: Int = 16,
    var metadataTtl: Duration = Duration.ofMinutes(1),
    var http: HttpLimits = HttpLimits(),
    var inProcess: Limits = Limits(),
    var sources: Map<String, Source> = emptyMap(),
) {
    /** One named ClickHouse server: its connection, as the default source's settings say for it. */
    data class Source(
        var endpoints: List<URI> = emptyList(),
        var username: String = "default",
        var password: String = "",
        var database: String = "default",
        var connectionTimeout: Duration = Duration.ofSeconds(5),
        var socketTimeout: Duration = Duration.ofSeconds(60),
        var maxConnections: Int = 16,
    )

    /** The default source's connection, as a [Source]. */
    fun defaultSource(): Source =
        Source(endpoints, username, password, database, connectionTimeout, socketTimeout, maxConnections)

    /** What one HTTP query may cost: 30 seconds and 4 GiB of memory unless set; untrusted callers get a bound. */
    data class HttpLimits(
        @DefaultValue("30s")
        var maxExecutionTime: Duration? = Duration.ofSeconds(30),
        var maxRowsToRead: Long? = null,
        var maxResultRows: Long? = null,
        @DefaultValue("4294967296")
        var maxMemoryUsage: Long? = 4L * 1024 * 1024 * 1024,
    ) {
        fun toLimits(): ClickHouseQueryLimits =
            ClickHouseQueryLimits(maxExecutionTime, maxRowsToRead, maxResultRows, maxMemoryUsage)
    }

    /** What one query may cost; unset leaves the server's own setting. */
    data class Limits(
        var maxExecutionTime: Duration? = null,
        var maxRowsToRead: Long? = null,
        var maxResultRows: Long? = null,
        var maxMemoryUsage: Long? = null,
    ) {
        fun toLimits(): ClickHouseQueryLimits =
            ClickHouseQueryLimits(maxExecutionTime, maxRowsToRead, maxResultRows, maxMemoryUsage)
    }

    fun toOptions(): ClickHouseDatasetOptions = ClickHouseDatasetOptions(
        defaultDatabase = database,
        limits = mapOf(QueryEntry.HTTP to http.toLimits(), QueryEntry.IN_PROCESS to inProcess.toLimits()),
        metadataTtl = metadataTtl,
    )

    companion object {
        const val PREFIX = "${Wow.WOW_PREFIX}query.clickhouse"
    }
}
