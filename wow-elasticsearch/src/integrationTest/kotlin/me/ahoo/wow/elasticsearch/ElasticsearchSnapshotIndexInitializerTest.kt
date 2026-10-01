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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.configuration.WowResourceLocator
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest
import co.elastic.clients.elasticsearch.indices.DeleteIndexRequest
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import me.ahoo.wow.elasticsearch.IndexNameConverter.toEventStreamIndexName
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.elasticsearch.TemplateInitializer.initEventStreamTemplate
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMappingResolver
import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ElasticsearchSnapshotIndexInitializerTest {
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
}
