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
    fun clickHouseDatasetClient(properties: ClickHouseDatasetProperties): Client {
        require(
            properties.endpoints.isNotEmpty()
        ) { "${ClickHouseDatasetProperties.PREFIX}.endpoints must not be empty." }
        val builder = Client.Builder()
        properties.endpoints.forEach { builder.addEndpoint(it.toASCIIString()) }
        return builder
            .setUsername(properties.username)
            .setPassword(properties.password)
            .setConnectTimeout(properties.connectionTimeout.toMillis())
            .setSocketTimeout(properties.socketTimeout.toMillis())
            .setMaxConnections(properties.maxConnections)
            .setConnectionRequestTimeout(properties.connectionTimeout.toMillis(), ChronoUnit.MILLIS)
            .useAsyncRequests(true)
            .build()
    }

    @Bean
    @ConditionalOnMissingBean
    fun clickHouseExecutor(client: Client): ClickHouseExecutor = ClickHouseExecutor(client)

    @Bean
    @ConditionalOnMissingBean(DatasetQueryBackendFactory::class)
    fun clickHouseDatasetQueryBackendFactory(
        executor: ClickHouseExecutor,
        properties: ClickHouseDatasetProperties,
    ): DatasetQueryBackendFactory = ClickHouseDatasetQueryBackendFactory(executor, properties.toOptions())
}
