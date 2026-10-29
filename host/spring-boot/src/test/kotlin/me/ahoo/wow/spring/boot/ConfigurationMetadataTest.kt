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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.query.dataset.QueryDatasetProvider
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.sink.SinkProvider
import me.ahoo.wow.spi.store.StoreProvider
import me.ahoo.wow.spi.transport.TransportProvider
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.ServiceLoader

/**
 * The host's Spring configuration metadata, `META-INF/spring-configuration-metadata.json`, generated
 * from its properties classes and their KDoc ([ConfigurationMetadata]): this test fails when the committed file is not
 * what the classes give, and `WOW_UPDATE_SNAPSHOTS=1` rewrites it.
 */
class ConfigurationMetadataTest {
    private val metadata = ConfigurationMetadata(
        ROOTS,
        providers = ServiceLoader.load(StoreProvider::class.java).filter { it.type in WOW_STORES }.sortedBy { it.type }
            .map { ConfigurationMetadata.ProviderOptions.of(it) } +
            ServiceLoader.load(TransportProvider::class.java).filter {
                it.type == KAFKA
            }.map { ConfigurationMetadata.ProviderOptions.of(it) } +
            ServiceLoader.load(SinkProvider::class.java).filter { it.type == CLICKHOUSE }.map { sink ->
                ConfigurationMetadata.ProviderOptions(
                    CLICKHOUSE,
                    sink.javaClass,
                    sink.options,
                    "The settings of ClickHouse: the connection the Sink and the dataset backend share, the Sink's " +
                        "and the datasets'.",
                )
            } +
            ServiceLoader.load(QueryDatasetProvider::class.java).filter {
                it.type == CLICKHOUSE
            }.map { ConfigurationMetadata.ProviderOptions.of(it) } +
            ConfigurationMetadata.ProviderOptions.of(DnsPeerDiscovery()),
    )

    @Test
    fun `the committed keys under the maps should be what their value classes and the store providers give`() {
        val stores = ServiceLoader.load(StoreProvider::class.java).filter { it.type in WOW_STORES }.sortedBy { it.type }
            .map { ConfigurationMetadata.ProviderOptions.of(it) }
        val document = metadata.mapsDocument(
            ConfigurationKeys.MAPS.map { ConfigurationMetadata.MapKeys(it.name, it.entry, it.type) },
            storesMap = "wow.stores",
            stores = stores,
        )
        val (committed, expected) = metadata.committedMaps(MAPS, document)
        committed.assert().describedAs(
            "Run with ${ConfigurationMetadata.UPDATE}=1 to rewrite $MAPS."
        ).isEqualTo(expected)
        WowJson.mapper.readTree(expected)["properties"].toList().map { it["name"].asString() }.assert().contains(
            "wow.stores.<name>.type",
            "wow.stores.<name>.query.max-concurrent",
            "wow.stores.<name>.data-source",
            "wow.aggregates.[<context>.<aggregate>].routing",
            "wow.callers.<service>.commands",
        )
    }

    @Test
    fun `the committed metadata should be what the properties classes give`() {
        val (committed, expected) = metadata.committed()
        committed.assert()
            .describedAs("Run with ${ConfigurationMetadata.UPDATE}=1 to rewrite ${ConfigurationMetadata.FILE}.")
            .isEqualTo(expected)
    }

    @Test
    fun `the keys the modules read from the environment should be in the metadata`() {
        val names = WowJson.mapper.readTree(metadata.document())["properties"].mapNotNull { it["name"]?.asString() }
        names.assert().contains(
            "wow.cosec.authorizer.enabled",
            "wow.cosec.cross-scope.roles",
            "wow.cosec.tenant-level.policies",
            "wow.cosec.mode",
            "wow.tenant.default",
            "wow.executor.max-in-flight",
            "wow.mongo.append-timeout",
            "wow.mongo.query-read-preference",
            "wow.postgres.operation-timeout",
            "wow.elasticsearch.refresh",
            "wow.kafka.bootstrap-servers",
            "wow.kafka.properties",
            "wow.kafka.topics.prefix",
            "wow.kafka.command-intake.enabled",
            "wow.kafka.topics.partition-refresh",
            "wow.kafka.topics.configs",
            "wow.clickhouse.endpoint",
            "wow.clickhouse.sink.batch-size",
            "wow.clickhouse.query.settings",
        )
    }

    @Test
    fun `every key should have a plain description`() {
        metadata.undocumented().assert().describedAs("Write a KDoc on the property of each of these keys.").isEmpty()
        metadata.withIds().assert()
            .describedAs("Users cannot look up decision or review IDs: explain them in plain words instead.")
            .isEmpty()
    }

    private companion object {
        /** The keys under the maps of beans, read by the configuration reference (.github/scripts/docs-reference.mjs). */
        val MAPS: Path = Path.of("src/test/resources/configuration-maps.json")

        /** Wow's own store types, whose options the host's metadata lists; a third party's ship their own. */
        val WOW_STORES = setOf("postgres", "mongo", "elasticsearch")

        const val CLICKHOUSE = "clickhouse"

        const val KAFKA = "kafka"

        val ROOTS: List<Pair<Class<*>, String>> = listOf(
            WowProperties::class.java to WowProperties.PREFIX,
            QueryProperties::class.java to QueryProperties.PREFIX,
            ObservabilityProperties::class.java to ObservabilityProperties.PREFIX,
        )
    }
}
