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

import com.clickhouse.client.api.Client
import me.ahoo.wow.spi.config.configNotNull
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The ClickHouse client (wow-clickhouse), active when the application has
 * wow-clickhouse on its classpath and sets `wow.clickhouse.endpoint` ([ClickHouseProperties]). The ClickHouse Sink is
 * the Sink provider of type `clickhouse` (`me.ahoo.wow.clickhouse.sink.ClickHouseSinkProvider`), which
 * [SinkAutoConfiguration] opens over the client below.
 *
 * - **Client**: a client-v2 [Client] of the endpoint and credentials, unless the application has one of its own;
 *   closed with the context.
 * - **Datasets**: the ClickHouse dataset backend, found by `ServiceLoader` and opened by the query plane
 *   ([QueryAutoConfiguration], [HostDatasets.provided]) over this client unless `wow.clickhouse.query.enabled=false`
 *   or the application has a `QueryBindings<QueryDataset>` of its own.
 */
@AutoConfiguration
@ConditionalOnClass(name = [CLICKHOUSE_MODULE])
@EnableConfigurationProperties(ClickHouseProperties::class)
class ClickHouseAutoConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = ClickHouseProperties.PREFIX, name = ["endpoint"])
    class ClickHouseEndpoint {
        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean(Client::class)
        fun wowClickHouseClient(properties: ClickHouseProperties): Client = Client.Builder()
            .addEndpoint(
                configNotNull(
                    properties.endpoint,
                    "wow.clickhouse.endpoint",
                    "Set it to the ClickHouse HTTP endpoint."
                ) {
                    "wow.clickhouse.endpoint is not set."
                },
            )
            .setUsername(properties.username)
            .setPassword(properties.password)
            .build()
    }
}

/** A class of wow-clickhouse, present only when the application has the module. */
internal const val CLICKHOUSE_MODULE = "me.ahoo.wow.clickhouse.ClickHouseModule"
