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

package me.ahoo.wow.analytics.catalog.server

import me.ahoo.wow.analytics.catalog.domain.dataset.DatasetCompiler
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * The analytics catalog service's settings, `wow.analytics.catalog.*`: the warehouse whose tables and views are the
 * sources, the accounts trusted to describe them, how the catalog watches them, and its datasets.
 */
@ConfigurationProperties(prefix = AnalyticsCatalogProperties.PREFIX)
class AnalyticsCatalogProperties(
    /** The name of the warehouse connection, the first part of each source's id. */
    var connection: String = DEFAULT_CONNECTION,
    /** The databases whose tables and views are sources; `wow.clickhouse.database` when empty. */
    var databases: MutableList<String> = mutableListOf(),
    /**
     * By warehouse account, the contexts whose tables the `wow_catalog` rows of that account are trusted to describe:
     * the account each context's Sink inserts as. Nobody is trusted by default.
     */
    var trustedWriters: MutableMap<String, MutableList<String>> = linkedMapOf(),
    /**
     * How often the catalog compares `system.tables` and `wow_catalog` with what it saw last and brings each changed
     * table's source up to date.
     */
    var refreshInterval: Duration = DEFAULT_REFRESH_INTERVAL,
    /** How the catalog watches the warehouse for changed tables. */
    var watch: Watch = Watch(),
    /** The datasets: where their views are created, how long a retired one is kept, and whether this node serves them. */
    var datasets: Datasets = Datasets(),
    /** Whether a dataset reads only the sources an approver approved (`ApproveSource`). */
    var requireSourceApproval: Boolean = false,
    /**
     * Whether every publication takes an approver (`ApproveDatasetPublication`); a publication that computes from
     * sensitive values or lowers a protection takes one either way.
     */
    var requirePublicationApproval: Boolean = false,
) {
    /** How the catalog watches the warehouse, `wow.analytics.catalog.watch.*`. */
    class Watch(
        /** Whether the catalog watches the warehouse for changed tables at all. */
        var enabled: Boolean = true,
    )

    /** The datasets' settings, `wow.analytics.catalog.datasets.*`. */
    class Datasets(
        /** The warehouse database the datasets' views are created in. */
        var database: String = DatasetCompiler.DEFAULT_DATABASE,
        /** How long a retired dataset's views are kept before they are dropped. */
        var retention: Duration = Duration.ofDays(DEFAULT_RETENTION_DAYS),
        /** Whether this node serves the published datasets' queries. */
        var serving: Serving = Serving(),
        /** How often this node reads which published datasets changed. */
        var refreshInterval: Duration = Duration.ofSeconds(DEFAULT_REFRESH_SECONDS),
        /** How often this node reads every published dataset. */
        var reconcileInterval: Duration = Duration.ofMinutes(DEFAULT_RECONCILE_MINUTES),
    )

    /** Whether a node serves the published datasets' queries, `wow.analytics.catalog.datasets.serving.*`. */
    class Serving(
        /** Whether this node serves the published datasets' queries. */
        var enabled: Boolean = true,
    )

    companion object {
        const val PREFIX: String = "wow.analytics.catalog"
        const val DEFAULT_CONNECTION: String = "default"

        @JvmField
        val DEFAULT_REFRESH_INTERVAL: Duration = Duration.ofSeconds(30)
        const val DEFAULT_RETENTION_DAYS: Long = 7
        const val DEFAULT_REFRESH_SECONDS: Long = 5
        const val DEFAULT_RECONCILE_MINUTES: Long = 5
    }
}
