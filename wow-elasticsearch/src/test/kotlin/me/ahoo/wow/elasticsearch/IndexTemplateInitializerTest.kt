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

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.core.io.ClassPathResource
import org.springframework.data.elasticsearch.core.ReactiveElasticsearchOperations
import org.springframework.data.elasticsearch.core.ReactiveIndexOperations
import org.springframework.data.elasticsearch.core.index.PutIndexTemplateRequest
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates
import reactor.core.publisher.Mono
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class IndexTemplateInitializerTest {
    private val indexOperations = mockk<ReactiveIndexOperations>()
    private val elasticsearchOperations = mockk<ReactiveElasticsearchOperations> {
        every { indexOps(any<IndexCoordinates>()) } returns indexOperations
    }
    private val initializer = IndexTemplateInitializer(elasticsearchOperations)

    @Test
    fun `built-in templates should protect dynamic mappings`() {
        val eventMappings = readMappings("wow-event-stream-template")
        eventMappings["date_detection"].asBoolean().assert().isEqualTo(false)
        eventMappings["properties"]["body"]["type"].asString().assert().isEqualTo("nested")
        eventMappings["properties"]["body"]["properties"]["body"]["enabled"]
            .asBoolean().assert().isEqualTo(false)
        eventMappings["dynamic_templates"][0]["string_as_keyword"]["mapping"]["ignore_above"]
            .asInt().assert().isEqualTo(8191)

        val snapshotMappings = readMappings("wow-snapshot-template")
        snapshotMappings["date_detection"].asBoolean().assert().isEqualTo(false)
        // Every dynamic string keeps a keyword whose ignore_above indexes every value Lucene can hold; ES's own
        // dynamic mapping would cap it at 256, which the query schema refuses.
        val snapshotTemplates = snapshotMappings["dynamic_templates"].associate { template ->
            template.properties().single().let { it.key to it.value["mapping"] }
        }
        listOf("tags_strings_as_keyword", "id_string_as_keyword", "id_suffix_string_as_keyword").forEach {
            snapshotTemplates.getValue(it)["ignore_above"].asInt().assert().isEqualTo(8191)
        }
        val text = snapshotTemplates.getValue("string_as_text_with_keyword")
        text["type"].asString().assert().isEqualTo("text")
        text["fields"]["keyword"]["ignore_above"].asInt().assert().isEqualTo(8191)
        // A dynamic floating value is a double, not ES's default 32-bit float.
        snapshotTemplates.getValue("floating_as_double")["type"].asString().assert().isEqualTo("double")
        snapshotTemplates.keys.last().assert().isEqualTo("floating_as_double")
    }

    @Test
    fun `init all should complete both template requests before returning`() {
        val completedRequests = AtomicInteger()
        every { indexOperations.putIndexTemplate(any()) } returns Mono.delay(Duration.ofMillis(25))
            .map { true }
            .doOnSuccess { completedRequests.incrementAndGet() }

        initializer.initAll()

        completedRequests.get().assert().isEqualTo(2)
        verify(exactly = 1) {
            indexOperations.putIndexTemplate(match { it.name == "wow-event-stream-template" })
        }
        verify(exactly = 1) {
            indexOperations.putIndexTemplate(match { it.name == "wow-snapshot-template" })
        }
    }

    @Test
    fun `default template names and patterns are Wow's own`() {
        val requests = mutableListOf<PutIndexTemplateRequest>()
        every { indexOperations.putIndexTemplate(capture(requests)) } returns Mono.just(true)

        initializer.initAll()

        requests.associate { it.name() to it.indexPatterns().toList() }.assert().isEqualTo(
            mapOf(
                "wow-event-stream-template" to listOf("wow.*.es"),
                "wow-snapshot-template" to listOf("wow.*.snapshot"),
            ),
        )
    }

    @Test
    fun `a prefix should name the templates and their patterns`() {
        val requests = mutableListOf<PutIndexTemplateRequest>()
        val coordinates = mutableListOf<IndexCoordinates>()
        val operations = mockk<ReactiveElasticsearchOperations> {
            every { indexOps(capture(coordinates)) } returns indexOperations
        }
        every { indexOperations.putIndexTemplate(capture(requests)) } returns Mono.just(true)

        IndexTemplateInitializer(operations, ElasticsearchIndexNaming("staging.")).initAll()

        requests.associate { it.name() to it.indexPatterns().toList() }.assert().isEqualTo(
            mapOf(
                "staging.wow-event-stream-template" to listOf("staging.wow.*.es"),
                "staging.wow-snapshot-template" to listOf("staging.wow.*.snapshot"),
            ),
        )
        coordinates.map { it.indexName }.assert()
            .containsExactly("staging.wow-event-stream-template", "staging.wow-snapshot-template")
        // The mappings are the templates' own.
        requests.first().mapping()!!["date_detection"].assert().isEqualTo(false)
    }

    @Test
    fun `init all should propagate request failure`() {
        val failure = IllegalStateException("template initialization failed")
        every { indexOperations.putIndexTemplate(any()) } returns Mono.error(failure)

        val actual = assertThrows<IllegalStateException> {
            initializer.initAll()
        }

        actual.assert().isSameAs(failure)
    }

    @Test
    fun `init all should reject unacknowledged request`() {
        every { indexOperations.putIndexTemplate(any()) } returns Mono.just(false)

        assertThrows<IllegalStateException> {
            initializer.initAll()
        }
    }

    @Test
    fun `init all should reject missing acknowledgement`() {
        every { indexOperations.putIndexTemplate(any()) } returns Mono.empty()

        assertThrows<IllegalStateException> {
            initializer.initAll()
        }
    }

    private fun readMappings(templateName: String): JsonNode =
        ClassPathResource("templates/$templateName.json").inputStream.use {
            JsonSerializer.readValue(it, JsonNode::class.java)["template"]["mappings"]
        }
}
