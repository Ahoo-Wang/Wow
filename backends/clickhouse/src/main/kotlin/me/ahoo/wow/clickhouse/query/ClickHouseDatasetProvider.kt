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
import me.ahoo.wow.clickhouse.ClickHouseClients
import me.ahoo.wow.query.backend.QueryBindings
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasetConfig
import me.ahoo.wow.query.dataset.QueryDatasetProvider
import me.ahoo.wow.spi.config.ProviderOption
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * The ClickHouse dataset backend (`wow.clickhouse.*`), found by `ServiceLoader`: the [ClickHouseDatasetBindings] over
 * the client the Sink's rule gives ([ClickHouseClients.clientOf]): the application's client-v2 [Client] when it has
 * one, which it never closes, else one of `endpoint` and the credentials, which the bindings close when the host
 * closes them. It is available when
 * either is there and `wow.clickhouse.query.enabled` is not `false`. A dataset's bare `table` is in
 * `wow.clickhouse.database`, where the Sink writes; its statements run under `wow.clickhouse.query.settings.*`.
 */
internal class ClickHouseDatasetProvider : QueryDatasetProvider {
    override val type: String = TYPE

    override val options: List<ProviderOption<*>> = listOf(
        ClickHouseClients.ENDPOINT,
        ClickHouseClients.USERNAME,
        ClickHouseClients.PASSWORD,
        ClickHouseClients.DATABASE,
        ENABLED,
        METADATA_TTL,
        SETTINGS,
    )

    override fun available(config: QueryDatasetConfig): Boolean = config.option(ENABLED) &&
        (config.resources.has(Client::class.java) || config.option(ClickHouseClients.ENDPOINT) != null)

    override fun open(config: QueryDatasetConfig): QueryBindings<QueryDataset> {
        val client = ClickHouseClients.clientOf(config.resources, config.key, "The ClickHouse datasets", config::option)
        val bindings = ClickHouseDatasetBindings(client.client, optionsOf(config))
        return if (client.owned) OwnedClientBindings(bindings, client) else bindings
    }

    /** The dataset options [config] gives: its database, the metadata's time to live and the statements' settings. */
    fun optionsOf(config: QueryDatasetConfig): ClickHouseDatasetOptions = ClickHouseDatasetOptions(
        database = config.option(ClickHouseClients.DATABASE),
        metadataTtl = config.option(METADATA_TTL).toJavaDuration(),
        settings = config.options(SETTINGS),
    )

    /** [bindings] over a [client] of their own, which closing them closes. */
    private class OwnedClientBindings(
        private val bindings: QueryBindings<QueryDataset>,
        private val client: ClickHouseClients.OwnedClient,
    ) : QueryBindings<QueryDataset> by bindings, AutoCloseable {
        override fun close() = client.close()
    }

    companion object {
        const val TYPE: String = "clickhouse"

        /** Whether the datasets are served over ClickHouse. */
        val ENABLED: ProviderOption.Defaulted<Boolean> = ProviderOption.boolean(
            "query.enabled",
            "whether the datasets are served over ClickHouse when the endpoint is set or the application has a " +
                "ClickHouse client.",
            true,
        )

        /** How long a table's columns and engine are reused before `system.columns` is read again. */
        val METADATA_TTL: ProviderOption.Defaulted<kotlin.time.Duration> = ProviderOption.duration(
            "query.metadata-ttl",
            "how long a dataset table's columns and engine are reused before system.columns is read again.",
            1.minutes,
        )

        /** The server settings every dataset statement runs under. */
        val SETTINGS: ProviderOption<Map<String, String>> = ProviderOption.map(
            "query.settings",
            "server settings every dataset query runs under, such as max_memory_usage; the execution time comes from " +
                "each query's budget (wow.query.budgets.http.timeout), not from here.",
        )
    }
}
