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
 * The analytics catalog service's settings, `wow.analytics.catalog.*` (decision D241):
 *
 * - `connection`: the name of the warehouse connection, the first part of each source's id (`default`).
 * - `engine`: the engine type of that connection (decision D251 ⑤): `clickhouse`, the one 10.0 implements.
 * - `databases`: the databases whose tables and views are sources; `wow.clickhouse.database` when empty.
 * - `trusted-writers.<account>`: the contexts whose tables the `wow_catalog` rows of the warehouse account `<account>`
 *   are trusted to describe (D241 ③): the account each context's Sink inserts as. Nobody is trusted by default.
 * - `refresh-interval`: how often the catalog compares `system.tables` and `wow_catalog` with what it saw last
 *   and brings each changed table's source up to date (D241 ④; 30 seconds).
 * - `watch`: whether it does at all (on by default).
 * - `datasets.database`: the warehouse database the datasets' views are created in (`wow_datasets`, decision D259).
 * - `datasets.retention`: how long a retired dataset's views are kept before they are dropped (7 days).
 * - `datasets.serve`: whether this node serves the published datasets' queries (on: the catalog serves them by
 *   default, decision D238 ⑦), `datasets.refresh-interval` how often it reads what changed (5 seconds), and
 *   `datasets.reconcile-interval` how often it reads every dataset (5 minutes).
 * - `approval.sources`: whether a dataset reads only the sources an approver approved (`ApproveSource`), and
 *   `approval.publications` whether every publication takes an approver (`ApproveDatasetPublication`); both off by
 *   default (decisions D238 ③b, D259). A publication that computes from sensitive values or lowers a protection takes
 *   an approver either way.
 */
@ConfigurationProperties(prefix = AnalyticsCatalogProperties.PREFIX)
class AnalyticsCatalogProperties(
    var connection: String = DEFAULT_CONNECTION,
    var engine: String = DEFAULT_ENGINE,
    var databases: MutableList<String> = mutableListOf(),
    var trustedWriters: MutableMap<String, MutableList<String>> = linkedMapOf(),
    var refreshInterval: Duration = DEFAULT_REFRESH_INTERVAL,
    var watch: Boolean = true,
    var datasets: Datasets = Datasets(),
    var approval: Approval = Approval(),
) {
    /** The approval switches, `wow.analytics.catalog.approval.*`. */
    class Approval(var sources: Boolean = false, var publications: Boolean = false)

    /** The datasets' settings, `wow.analytics.catalog.datasets.*`. */
    class Datasets(
        var database: String = DatasetCompiler.DEFAULT_DATABASE,
        var retention: Duration = Duration.ofDays(DEFAULT_RETENTION_DAYS),
        var serve: Boolean = true,
        var refreshInterval: Duration = Duration.ofSeconds(DEFAULT_REFRESH_SECONDS),
        var reconcileInterval: Duration = Duration.ofMinutes(DEFAULT_RECONCILE_MINUTES),
    )

    companion object {
        const val PREFIX: String = "wow.analytics.catalog"
        const val DEFAULT_CONNECTION: String = "default"
        const val DEFAULT_ENGINE: String = ClickHouseWarehouseCatalog.ENGINE

        @JvmField
        val DEFAULT_REFRESH_INTERVAL: Duration = Duration.ofSeconds(30)
        const val DEFAULT_RETENTION_DAYS: Long = 7
        const val DEFAULT_REFRESH_SECONDS: Long = 5
        const val DEFAULT_RECONCILE_MINUTES: Long = 5
    }
}
