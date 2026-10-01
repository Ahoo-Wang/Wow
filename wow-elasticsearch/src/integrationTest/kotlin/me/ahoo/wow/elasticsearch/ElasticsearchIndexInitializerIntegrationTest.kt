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

package me.ahoo.wow.elasticsearch

import co.elastic.clients.elasticsearch.indices.CreateIndexRequest
import co.elastic.clients.elasticsearch.indices.DeleteIndexRequest
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.configuration.WowResourceLocator
import me.ahoo.wow.elasticsearch.IndexNameConverter.toEventStreamIndexName
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.elasticsearch.TemplateInitializer.initEventStreamTemplate
import me.ahoo.wow.elasticsearch.TemplateInitializer.initSnapshotTemplate
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMappingResolver
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

class ElasticsearchIndexInitializerIntegrationTest {
    @JvmField
    @RegisterExtension
    val elasticsearch = ElasticsearchTestFixture()

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `should create configured index with queryable mapping`() {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        val indexName = MOCK_AGGREGATE_METADATA.toSnapshotIndexName()
        val file = tempDir.resolve("wow/elasticsearch/$indexName.json")
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            """{"mappings":{"properties":{"state":{"properties":{"status":{"type":"keyword"}}}}}}""",
        )

        ElasticsearchSnapshotIndexInitializer(
            client,
            WowResourceLocator(configDirectory = tempDir),
            listOf(MOCK_AGGREGATE_METADATA),
        ).ensureAll().block()

        ElasticsearchIndexMappingResolver(client).refresh(indexName).block()!!
            .fields.getValue("state.status").aggregatable.assert().isTrue()
    }

    @Test
    fun `an event stream definition maps the body the template leaves unindexed, and drift is read off a real mapping`() {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        client.initEventStreamTemplate()
        val indexName = MOCK_AGGREGATE_METADATA.toEventStreamIndexName()
        val definition =
            """{"mappings":{"properties":{"body":{"type":"nested","properties":{""" +
                """"body":{"type":"object","dynamic":false,"properties":{"audience":{"type":"keyword"}}}}}}}}"""
        val file = tempDir.resolve("wow/elasticsearch/$indexName.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, definition)
        val initializer = ElasticsearchEventStreamIndexInitializer(
            client,
            WowResourceLocator(configDirectory = tempDir),
            listOf(MOCK_AGGREGATE_METADATA),
        )
        fun mapping() = client.indices().getMapping(GetMappingRequest.of { it.index(indexName) }).block()!!
            .mappings().getValue(indexName).mappings()
        val expected = definition.byteInputStream().use {
            CreateIndexRequest.Builder().withJson(it).index(indexName).build()
        }.mappings()!!

        initializer.ensureAll().block()

        // The template's fields stay; the definition's body wins over the template's.
        val created = ElasticsearchIndexMappingResolver(client).refresh(indexName).block()!!
        created.fields.getValue("body.body.audience").indexed.assert().isTrue()
        created.fields.getValue("requestId").indexed.assert().isTrue()
        IndexMappingDrift.between(expected, mapping()).assert().isEmpty()

        // An index the template alone created (before the definition shipped) is left as it is, and drifts.
        client.indices().delete(DeleteIndexRequest.of { it.index(indexName) }).block()
        client.indices().create(CreateIndexRequest.of { it.index(indexName) }).block()
        initializer.ensureAll().block()
        IndexMappingDrift.between(expected, mapping()).assert()
            .containsExactly("body.body", "body.body.audience")
        client.indices().delete(DeleteIndexRequest.of { it.index(indexName) }).block()
    }

    /**
     * Every index definition the repository ships under `META-INF/wow/elasticsearch/` is created on the templates Wow
     * installs at startup, reads back without drift, and a second startup (the indices now exist) reports none either.
     */
    @Test
    fun `every shipped definition is created as written and a second startup finds no drift`() {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        client.initSnapshotTemplate()
        client.initEventStreamTemplate()
        val definitions = shippedDefinitions()
        definitions.map { it.name }.assert().contains(
            "wow.view-store.view.snapshot",
            "wow.view-store.view_preferences.snapshot",
            "wow.view-store.view.es",
            "wow.compensation.execution_failed.snapshot",
            "wow.example.order.snapshot",
        )
        val directory = tempDir.resolve("wow/elasticsearch")
        Files.createDirectories(directory)
        definitions.forEach { it.path.copyTo(directory.resolve(it.path.name)) }
        val locator = WowResourceLocator(configDirectory = tempDir)
        fun aggregates(suffix: String) = definitions.filter { it.name.endsWith(suffix) }.map { it.aggregate }
        val initializers = listOf(
            ElasticsearchSnapshotIndexInitializer(client, locator, aggregates(IndexNameConverter.SNAPSHOT_SUFFIX)),
            ElasticsearchEventStreamIndexInitializer(
                client,
                locator,
                aggregates(IndexNameConverter.EVENT_STREAM_SUFFIX),
            ),
        )
        fun assertNoDrift() = definitions.forEach { definition ->
            val expected = definition.path.readText().byteInputStream().use {
                CreateIndexRequest.Builder().withJson(it).index(definition.name).build()
            }.mappings()!!
            val actual = client.indices().getMapping(GetMappingRequest.of { it.index(definition.name) }).block()!!
                .mappings().getValue(definition.name).mappings()
            IndexMappingDrift.between(expected, actual).assert().describedAs(definition.name).isEmpty()
        }

        initializers.forEach { it.ensureAll().block() }
        assertNoDrift()
        initializers.forEach { it.ensureAll().block() }
        assertNoDrift()
    }

    private class ShippedDefinition(val path: Path) {
        val name: String = path.name.removeSuffix(".json")
        val aggregate: MaterializedNamedAggregate = name.removePrefix("wow.").substringBeforeLast('.').let {
            MaterializedNamedAggregate(it.substringBeforeLast('.'), it.substringAfterLast('.'))
        }
    }

    private fun shippedDefinitions(): List<ShippedDefinition> {
        var root = Path.of("").toAbsolutePath()
        while (!root.resolve("settings.gradle.kts").exists()) {
            root = checkNotNull(root.parent) { "No Gradle root above ${Path.of("").toAbsolutePath()}." }
        }
        return SHIPPING_MODULES.map { root.resolve(it).resolve("src/main/resources/META-INF/wow/elasticsearch") }
            .flatMap { it.listDirectoryEntries("*.json") }
            .map(::ShippedDefinition)
            .sortedBy { it.name }
    }

    companion object {
        private val SHIPPING_MODULES = listOf(
            "view-store/wow-view-store-starter",
            "compensation/wow-compensation-server",
            "example/example-server",
        )
    }
}
