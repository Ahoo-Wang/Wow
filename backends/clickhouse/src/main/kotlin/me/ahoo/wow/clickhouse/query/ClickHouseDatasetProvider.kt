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

package me.ahoo.wow.clickhouse.query

import com.clickhouse.client.api.Client
import me.ahoo.wow.query.backend.QueryBindings
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasetConfig
import me.ahoo.wow.query.dataset.QueryDatasetProvider
import me.ahoo.wow.spi.config.ProviderOption
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * The ClickHouse dataset backend (`wow.clickhouse.*`), found by `ServiceLoader`: the [ClickHouseDatasetBindings] over
 * the application's client-v2 [Client] (the one the Sink writes with), available when it has one and
 * `wow.clickhouse.query.enabled` is not `false`. A dataset's bare `table` is in `wow.clickhouse.database`, where the Sink
 * writes; its statements run under `wow.clickhouse.query.settings.*`.
 */
internal class ClickHouseDatasetProvider : QueryDatasetProvider {
    override val type: String = TYPE

    override val options: List<ProviderOption<*>> = listOf(ENABLED, DATABASE, METADATA_TTL)

    override fun available(config: QueryDatasetConfig): Boolean =
        config.option(ENABLED) != false && config.resources.has(Client::class.java)

    override fun open(config: QueryDatasetConfig): QueryBindings<QueryDataset> = ClickHouseDatasetBindings(
        config.resources.resource(Client::class.java, null, "${config.key}.endpoint"),
        optionsOf(config),
    )

    /** The dataset options [config] gives: its database, the metadata's time to live and the statements' settings. */
    fun optionsOf(config: QueryDatasetConfig): ClickHouseDatasetOptions = ClickHouseDatasetOptions(
        database = checkNotNull(config.option(DATABASE)),
        metadataTtl = checkNotNull(config.option(METADATA_TTL)).toJavaDuration(),
        settings = config.options(SETTINGS),
    )

    companion object {
        const val TYPE: String = "clickhouse"

        private const val SETTINGS = "query.settings"

        /** Whether the datasets are served over ClickHouse. */
        val ENABLED: ProviderOption<Boolean> = ProviderOption.boolean(
            "query.enabled",
            "whether the datasets are served over ClickHouse when the application has a ClickHouse client.",
            true,
        )

        /** The database of a dataset whose `table` names none. */
        val DATABASE: ProviderOption<String> = ProviderOption.string(
            "database",
            "the database of the Sink's tables and of a dataset whose table names none.",
            "default",
        )

        /** How long a table's columns and engine are reused before `system.columns` is read again. */
        val METADATA_TTL: ProviderOption<kotlin.time.Duration> = ProviderOption.duration(
            "query.metadata-ttl",
            "how long a dataset table's columns and engine are reused before system.columns is read again.",
            1.minutes,
        )
    }
}
