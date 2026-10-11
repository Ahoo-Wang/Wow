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

import com.clickhouse.client.api.Client
import me.ahoo.wow.clickhouse.query.ClickHouseDatasetQueryBackendFactory
import me.ahoo.wow.clickhouse.query.ClickHouseExecutor
import me.ahoo.wow.clickhouse.query.ClickHouseSource
import me.ahoo.wow.query.dataset.DatasetQueryBackendFactory
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.query.QueryAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import java.time.temporal.ChronoUnit

/**
 * Serves the datasets of `wow.query.datasets` from ClickHouse when `wow-clickhouse` is on the classpath
 * (`clickhouse-support`) and `wow.query.clickhouse.enabled` is `true`: a ClickHouse client over the configured
 * endpoints, and the dataset query backend the Catalog and the dataset gateways read.
 */
@AutoConfiguration(before = [QueryAutoConfiguration::class])
@ConditionalOnWowEnabled
@ConditionalOnClass(ClickHouseDatasetQueryBackendFactory::class)
@ConditionalOnProperty(prefix = ClickHouseDatasetProperties.PREFIX, name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(ClickHouseDatasetProperties::class)
class ClickHouseDatasetAutoConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    fun clickHouseDatasetClient(properties: ClickHouseDatasetProperties): Client =
        client(properties.defaultSource(), ClickHouseDatasetProperties.PREFIX)

    @Bean
    @ConditionalOnMissingBean
    fun clickHouseExecutor(client: Client): ClickHouseExecutor = ClickHouseExecutor(client)

    /** The named sources of `wow.query.clickhouse.sources`, each over its own client. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    fun clickHouseDatasetSources(properties: ClickHouseDatasetProperties): ClickHouseDatasetSources =
        ClickHouseDatasetSources(
            properties.sources.mapValues { (name, source) ->
                client(source, "${ClickHouseDatasetProperties.PREFIX}.sources.$name") to source.database
            },
        )

    @Bean
    @ConditionalOnMissingBean(DatasetQueryBackendFactory::class)
    fun clickHouseDatasetQueryBackendFactory(
        executor: ClickHouseExecutor,
        sources: ClickHouseDatasetSources,
        properties: ClickHouseDatasetProperties,
    ): DatasetQueryBackendFactory =
        ClickHouseDatasetQueryBackendFactory(executor, properties.toOptions(), sources.sources)

    private fun client(source: ClickHouseDatasetProperties.Source, prefix: String): Client {
        require(source.endpoints.isNotEmpty()) { "$prefix.endpoints must not be empty." }
        val builder = Client.Builder()
        source.endpoints.forEach { builder.addEndpoint(it.toASCIIString()) }
        return builder
            .setUsername(source.username)
            .setPassword(source.password)
            .setConnectTimeout(source.connectionTimeout.toMillis())
            .setSocketTimeout(source.socketTimeout.toMillis())
            .setMaxConnections(source.maxConnections)
            .setConnectionRequestTimeout(source.connectionTimeout.toMillis(), ChronoUnit.MILLIS)
            .useAsyncRequests(true)
            .build()
    }
}

/** The named ClickHouse sources and the clients they own, closed with the context. */
class ClickHouseDatasetSources(clients: Map<String, Pair<Client, String>>) : AutoCloseable {
    private val clients: List<Client> = clients.values.map { it.first }

    /** Each named source, by name. */
    val sources: Map<String, ClickHouseSource> =
        clients.mapValues { (_, client) -> ClickHouseSource(ClickHouseExecutor(client.first), client.second) }

    override fun close() {
        clients.forEach(Client::close)
    }
}
