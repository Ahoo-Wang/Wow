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
import me.ahoo.wow.clickhouse.query.ClickHouseDatasetBindings
import me.ahoo.wow.clickhouse.query.ClickHouseDatasetOptions
import me.ahoo.wow.query.backend.QueryBindings
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.spi.config.configNotNull
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.ConfigurationCondition
import org.springframework.core.ResolvableType
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * The ClickHouse client and dataset backend (wow-clickhouse), active when the application has
 * wow-clickhouse on its classpath and sets `wow.clickhouse.endpoint` ([ClickHouseProperties]). The ClickHouse Sink is
 * the Sink provider of type `clickhouse` (`me.ahoo.wow.clickhouse.sink.ClickHouseSinkProvider`), which
 * [SinkAutoConfiguration] opens over the client below.
 *
 * - **Client**: a client-v2 [Client] of the endpoint and credentials, unless the application has one of its own;
 *   closed with the context.
 * - **Datasets** (unless `wow.clickhouse.query.enabled=false` or the application has a `QueryBindings<QueryDataset>`
 *   of its own): a [ClickHouseDatasetBindings] over the same client, a dataset's bare `table` in
 *   `wow.clickhouse.database`, where the Sink writes. The query plane ([QueryAutoConfiguration]) serves the datasets
 *   of the dataset files over it, such as a `<context>_<aggregate>_state_flat` view the Sink keeps.
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

        @Bean
        @Conditional(OnMissingDatasetBindings::class)
        @ConditionalOnProperty(
            prefix = "${ClickHouseProperties.PREFIX}.query",
            name = ["enabled"],
            matchIfMissing = true
        )
        fun wowClickHouseDatasets(
            client: Client,
            properties: ClickHouseProperties
        ): ClickHouseDatasetBindings =
            ClickHouseDatasetBindings(client, datasetOptions(properties))
    }

    companion object {
        /** The dataset backend's options from [properties]. */
        fun datasetOptions(properties: ClickHouseProperties): ClickHouseDatasetOptions = ClickHouseDatasetOptions(
            database = properties.database,
            metadataTtl = properties.query.metadataTtl,
            settings = properties.query.settings.toMap(),
        )
    }
}

/**
 * Matches when the application has no `QueryBindings<QueryDataset>` bean of its own: `@ConditionalOnMissingBean`
 * names a class, and every query bindings bean is a `QueryBindings`, so it reads the generic type.
 */
internal class OnMissingDatasetBindings : ConfigurationCondition {
    override fun getConfigurationPhase(): ConfigurationCondition.ConfigurationPhase =
        ConfigurationCondition.ConfigurationPhase.REGISTER_BEAN

    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
        context.beanFactory?.getBeanNamesForType(DATASET_BINDINGS, true, false).isNullOrEmpty()

    private companion object {
        val DATASET_BINDINGS: ResolvableType =
            ResolvableType.forClassWithGenerics(QueryBindings::class.java, QueryDataset::class.java)
    }
}

/** A class of wow-clickhouse, present only when the application has the module. */
internal const val CLICKHOUSE_MODULE = "me.ahoo.wow.clickhouse.ClickHouseModule"
