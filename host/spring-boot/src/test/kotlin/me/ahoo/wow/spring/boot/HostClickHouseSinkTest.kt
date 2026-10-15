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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.clickhouse.sink.AggregateSinkOptions
import me.ahoo.wow.clickhouse.sink.DdlMode
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.PublishedManifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.runtime.manifest.ManifestLoader
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.sink.PublishedSinks
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.time.Duration.Companion.days

/**
 * The ClickHouse Sink wired by [ClickHouseAutoConfiguration] (decisions D142, D164): with `wow.clickhouse.endpoint`
 * set, every stream the Manifest publishes is written to ClickHouse by a subscription `clickhouse-sink.<aggregate>`.
 */
class HostClickHouseSinkTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ClickHouseAutoConfiguration::class.java))
        .withBean(Manifest::class.java, { Manifest(service = ServiceInfo("bank-service")) })

    @Test
    fun `without an endpoint nothing should be wired`() {
        runner.run { context ->
            context.getBeansOfType(Client::class.java).assert().isEmpty()
            context.getBeansOfType(SubscriptionFactory::class.java).assert().isEmpty()
        }
    }

    @Test
    fun `with an endpoint the client and the sinks should be wired`() {
        runner.withPropertyValues("wow.clickhouse.endpoint=http://127.0.0.1:1").run { context ->
            context.getBean(Client::class.java).assert().isNotNull()
            val sinks = context.getBean(ClickHouseAutoConfiguration.CLICKHOUSE_SINKS, PublishedSinks::class.java)
            sinks.nameOf(PublishedManifest("account", "t")).assert()
                .isEqualTo("clickhouse-sink.account")
            // The Manifest publishes nothing: no sink reads any state.
            sinks.factory.viewTypes(BANK, emptyList()).assert().isEmpty()
        }
    }

    @Test
    fun `the sink should be switched off by its property`() {
        runner.withPropertyValues("wow.clickhouse.endpoint=http://127.0.0.1:1", "wow.clickhouse.sink.enabled=false")
            .run { context ->
                context.getBeansOfType(Client::class.java).assert().hasSize(1)
                context.getBeansOfType(SubscriptionFactory::class.java).assert().isEmpty()
            }
    }

    @Test
    fun `the properties should become the Sink's options`() {
        runner.withPropertyValues(
            "wow.clickhouse.endpoint=http://127.0.0.1:1",
            "wow.clickhouse.database=analytics",
            "wow.clickhouse.sink.ddl=validate",
            "wow.clickhouse.sink.batch-size=50",
            "wow.clickhouse.sink.deduplication-window=200",
            "wow.clickhouse.sink.aggregates.bank.account.history.ttl=30d",
            "wow.clickhouse.sink.aggregates.bank.audit.history.enabled=false",
        ).run { context ->
            val options = ClickHouseAutoConfiguration.options(context.getBean(ClickHouseProperties::class.java))
            options.database.assert().isEqualTo("analytics")
            options.ddl.assert().isEqualTo(DdlMode.VALIDATE)
            options.batchSize.assert().isEqualTo(50)
            options.deduplicationWindow.assert().isEqualTo(200)
            options.aggregates.assert().isEqualTo(
                mapOf(
                    AggregateType(BANK, "account") to AggregateSinkOptions(historyTtl = 30.days),
                    AggregateType(BANK, "audit") to AggregateSinkOptions(history = false),
                ),
            )
        }
        ClickHouseAutoConfiguration.options(ClickHouseProperties()).ddl.assert().isEqualTo(DdlMode.AUTO)
        assertThrows<IllegalArgumentException> {
            ClickHouseAutoConfiguration.options(ClickHouseProperties().apply { sink.ddl = "drop" })
        }
    }

    @Test
    fun `a host should run a ClickHouse sink subscription per published aggregate`() {
        startHost(
            "wow.clickhouse.endpoint" to "http://127.0.0.1:1",
            "wow.clickhouse.sink.ddl" to "none",
            sources = listOf(HostTestApp::class.java, PublishingManifest::class.java),
        ).use { host ->
            host.node.context(BANK).views.readers.keys.assert().contains("clickhouse-sink.account")
        }
    }

    /** The Manifest of the host's models, publishing every aggregate. */
    @Configuration(proxyBeanMethods = false)
    class PublishingManifest {
        @Bean
        fun publishingManifest(loader: ManifestLoader, models: WowModels, identity: NodeIdentity): Manifest =
            loader.load(ServiceInfo(identity.service), models.providers).let { manifest ->
                manifest.copy(
                    contexts = manifest.contexts.map { context ->
                        context.copy(
                            published = context.aggregates.map {
                                PublishedManifest(it.name, PublishedManifest.defaultTopic(context.name, it.name))
                            },
                        )
                    },
                )
            }
    }
}
