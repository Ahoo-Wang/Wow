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
import me.ahoo.wow.clickhouse.sink.ClickHouseSinkProvider
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.PublishedManifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.runtime.manifest.ManifestLoader
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.sink.SinkConfig
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The ClickHouse Sink, found as the Sink provider of type `clickhouse` (decisions D142, D164, finding R5.B-5): with
 * `wow.clickhouse.sink.enabled` set, every stream the Manifest publishes is written to ClickHouse by a subscription
 * `clickhouse-sink.<aggregate>`, over the application's ClickHouse client or one the Sink opens itself.
 */
class HostClickHouseSinkTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                TransportAutoConfiguration::class.java,
                SinkAutoConfiguration::class.java,
            ),
        )
        .withBean(Manifest::class.java, { Manifest(service = ServiceInfo("bank-service")) })

    private val client: Client = Client.Builder().addEndpoint("http://127.0.0.1:1").setUsername("default").build()

    @Test
    fun `a client or an endpoint alone should start no Sink`() {
        runner.run { context -> context.getBean(ProvidedSinks::class.java).sinks.assert().isEmpty() }
        // The analytics catalog's case: a client declared to read the warehouse writes nothing to it.
        runner.withBean(Client::class.java, { client }).run { context ->
            context.getBean(ProvidedSinks::class.java).sinks.assert().isEmpty()
        }
        runner.withPropertyValues("wow.clickhouse.endpoint=http://127.0.0.1:1").run { context ->
            context.getBean(ProvidedSinks::class.java).sinks.assert().isEmpty()
        }
    }

    @Test
    fun `a Sink switched on should write over the application's client`() {
        runner.withPropertyValues(ENABLED).withBean(Client::class.java, { client }).run { context ->
            val sinks = context.getBean(ProvidedSinks::class.java).sinks.single()
            sinks.nameOf(PublishedManifest("account", "t")).assert().isEqualTo("clickhouse-sink.account")
            // The Manifest publishes nothing: no sink reads any state.
            sinks.factory.viewTypes(BANK, emptyList()).assert().isEmpty()
        }
    }

    @Test
    fun `a Sink switched on should write over a client of the endpoint, its own`() {
        runner.withPropertyValues(ENABLED, "wow.clickhouse.endpoint=http://127.0.0.1:1").run { context ->
            context.getBeansOfType(Client::class.java).assert().isEmpty()
            context.getBean(ProvidedSinks::class.java).sinks.single().nameOf(PublishedManifest("account", "t"))
                .assert().isEqualTo("clickhouse-sink.account")
        }
    }

    @Test
    fun `a Sink switched on with neither a client nor an endpoint should refuse the start, naming both`() {
        runner.withPropertyValues(ENABLED).run { context ->
            val failure = generateSequence(context.startupFailure) { it.cause }
                .filterIsInstance<WowConfigurationException>().first()
            failure.key.assert().isEqualTo("wow.clickhouse")
            failure.message.assert().contains("ClickHouse Client bean", "wow.clickhouse.endpoint")
        }
    }

    @Test
    fun `the properties should become the Sink's options`() {
        runner.withPropertyValues(
            "wow.clickhouse.endpoint=http://127.0.0.1:1",
            "wow.clickhouse.database=analytics",
            "wow.clickhouse.ddl=validate",
            "wow.clickhouse.sink.batch-size=50",
            "wow.clickhouse.sink.deduplication-window=200",
        ).run { context ->
            val config = SinkConfig.of(
                "clickhouse",
                "clickhouse",
                PortProviders.optionsOf(context.environment, "clickhouse")
            )
            val options = ClickHouseSinkProvider.options(config)
            options.database.assert().isEqualTo("analytics")
            options.ddl.assert().isEqualTo(Ddl.VALIDATE)
            options.batchSize.assert().isEqualTo(50)
            options.deduplicationWindow.assert().isEqualTo(200)
        }
        runner.withPropertyValues(
            ENABLED,
            "wow.clickhouse.endpoint=http://127.0.0.1:1",
            "wow.aggregates.[account].clickhouse.history.enabled=false",
        )
            .run { context ->
                // A setting the provider refuses fails the startup naming its key (finding R5.B-5).
                context.startupFailure.assert().hasRootCauseInstanceOf(IllegalArgumentException::class.java)
                generateSequence(context.startupFailure) { it.cause }.filterIsInstance<WowConfigurationException>()
                    .first().key.assert().isEqualTo("wow.clickhouse")
            }
    }

    @Test
    fun `a host should run a ClickHouse sink subscription per published aggregate`() {
        startHost(
            "wow.clickhouse.sink.enabled" to "true",
            "wow.clickhouse.endpoint" to "http://127.0.0.1:1",
            "wow.clickhouse.ddl" to "none",
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
                manifest.copyWith(
                    contexts = manifest.contexts.map { context ->
                        context.copyWith(
                            published = context.aggregates.map {
                                PublishedManifest(it.name, PublishedManifest.defaultTopic(context.name, it.name))
                            },
                        )
                    },
                )
            }
    }

    private companion object {
        const val ENABLED = "wow.clickhouse.sink.enabled=true"
    }
}
