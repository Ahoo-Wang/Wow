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
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
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
}
