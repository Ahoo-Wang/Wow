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
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse
import co.elastic.clients.elasticsearch.indices.ExistsRequest
import co.elastic.clients.transport.endpoints.BooleanResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.configuration.WowResourceLocator
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchIndicesClient
import reactor.core.publisher.Mono
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

class ElasticsearchSnapshotIndexInitializerTest {
    @TempDir
    lateinit var tempDir: Path

    private val client = mockk<ReactiveElasticsearchClient>()
    private val indices = mockk<ReactiveElasticsearchIndicesClient>()

    init {
        every { client.indices() } returns indices
    }

    @Test
    fun `missing resource should not call Elasticsearch`() {
        initializer().ensureAll().block()

        verify(exactly = 0) { client.indices() }
    }

    @Test
    fun `existing index should skip create`() {
        writeWorkingResource(indexJson())
        every { indices.exists(any<ExistsRequest>()) } returns Mono.just(BooleanResponse(true))

        initializer().ensureAll().block()

        verify(exactly = 0) { indices.create(any<CreateIndexRequest>()) }
    }

    @Test
    fun `missing index should be created from native json`() {
        writeWorkingResource(indexJson())
        every { indices.exists(any<ExistsRequest>()) } returns Mono.just(BooleanResponse(false))
        val request = slot<CreateIndexRequest>()
        every { indices.create(capture(request)) } returns Mono.just(response(acknowledged = true))

        initializer().ensureAll().block()

        request.captured.index().assert().isEqualTo(INDEX)
        request.captured.settings()!!.numberOfShards().assert().isEqualTo("1")
        request.captured.aliases().assert().containsKey("snapshot-read")
        request.captured.mappings()!!.properties()["state"]!!.`object`()
            .properties()["status"]!!._kind().jsonValue().assert().isEqualTo("keyword")
    }

    @Test
    fun `working resource should suppress duplicate classpath resources`() {
        writeWorkingResource(indexJson())
        val first = tempDir.resolve("a")
        val second = tempDir.resolve("z")
        writeClasspathResource(first, indexJson())
        writeClasspathResource(second, indexJson())
        every { indices.exists(any<ExistsRequest>()) } returns Mono.just(BooleanResponse(true))

        URLClassLoader(arrayOf(first.toUri().toURL(), second.toUri().toURL()), null).use { loader ->
            initializer(WowResourceLocator(configDirectory = tempDir, classLoader = loader)).ensureAll().block()
            Unit
        }
    }

    @Test
    fun `duplicate classpath resources should fail before Elasticsearch`() {
        val first = tempDir.resolve("a")
        val second = tempDir.resolve("z")
        writeClasspathResource(first, indexJson())
        writeClasspathResource(second, indexJson())

        URLClassLoader(arrayOf(first.toUri().toURL(), second.toUri().toURL()), null).use { loader ->
            val error = assertThrows<IllegalStateException> {
                initializer(WowResourceLocator(configDirectory = tempDir, classLoader = loader)).ensureAll().block()
            }
            error.message.assert().contains(INDEX).contains("2")
        }
        verify(exactly = 0) { client.indices() }
    }

    @Test
    fun `malformed resource should retain index and location`() {
        writeWorkingResource("{not-json")

        val error = assertThrows<IllegalStateException> { initializer().ensureAll().block() }

        error.message.assert().contains(INDEX).contains(tempDir.toString())
        error.cause.assert().isNotNull()
    }

    @Test
    fun `empty create response should fail`() {
        writeWorkingResource(indexJson())
        every { indices.exists(any<ExistsRequest>()) } returns Mono.just(BooleanResponse(false))
        every { indices.create(any<CreateIndexRequest>()) } returns Mono.empty()

        assertThrows<IllegalStateException> { initializer().ensureAll().block() }
    }

    @Test
    fun `empty exists response should fail`() {
        writeWorkingResource(indexJson())
        every { indices.exists(any<ExistsRequest>()) } returns Mono.empty()

        val error = assertThrows<IllegalStateException> { initializer().ensureAll().block() }

        error.message.assert().contains(INDEX).contains(tempDir.resolve("wow/elasticsearch/$INDEX.json").toString())
    }

    @Test
    fun `unacknowledged create response should fail`() {
        writeWorkingResource(indexJson())
        every { indices.exists(any<ExistsRequest>()) } returns Mono.just(BooleanResponse(false))
        every { indices.create(any<CreateIndexRequest>()) } returns Mono.just(response(acknowledged = false))

        assertThrows<IllegalStateException> { initializer().ensureAll().block() }
    }

    @Test
    fun `concurrent resource already exists should complete`() {
        writeWorkingResource(indexJson())
        every { indices.exists(any<ExistsRequest>()) } returns Mono.just(BooleanResponse(false))
        every { indices.create(any<CreateIndexRequest>()) } returns Mono.error(
            co.elastic.clients.elasticsearch._types.ElasticsearchException(
                "indices.create",
                co.elastic.clients.elasticsearch._types.ErrorResponse.of { response ->
                    response.status(400).error { error ->
                        error.type("resource_already_exists_exception").reason("already exists")
                    }
                },
            ),
        )

        initializer().ensureAll().block()
    }

    @Test
    fun `other Elasticsearch failures should retain cause`() {
        writeWorkingResource(indexJson())
        val expected = IllegalStateException("cluster unavailable")
        every { indices.exists(any<ExistsRequest>()) } returns Mono.just(BooleanResponse(false))
        every { indices.create(any<CreateIndexRequest>()) } returns Mono.error(expected)

        val actual = assertThrows<IllegalStateException> { initializer().ensureAll().block() }

        actual.message.assert().contains(INDEX)
        actual.cause.assert().isSameAs(expected)
    }

    private fun initializer(
        locator: WowResourceLocator = WowResourceLocator(
            configDirectory = tempDir,
            classLoader = object : ClassLoader(null) {},
        ),
    ) = ElasticsearchSnapshotIndexInitializer(client, locator, listOf(MOCK_AGGREGATE_METADATA))

    private fun writeWorkingResource(json: String) {
        val file = tempDir.resolve("wow/elasticsearch/$INDEX.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, json)
    }

    private fun writeClasspathResource(root: Path, json: String) {
        val file = root.resolve("META-INF/wow/elasticsearch/$INDEX.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, json)
    }

    private fun indexJson() = """
        {
          "settings":{"number_of_shards":"1"},
          "mappings":{"properties":{"state":{"properties":{"status":{"type":"keyword"}}}}},
          "aliases":{"snapshot-read":{}}
        }
    """.trimIndent()

    private fun response(acknowledged: Boolean) = CreateIndexResponse.of {
        it.acknowledged(acknowledged).shardsAcknowledged(acknowledged).index(INDEX)
    }

    companion object {
        private val INDEX = MOCK_AGGREGATE_METADATA.toSnapshotIndexName()
    }
}
