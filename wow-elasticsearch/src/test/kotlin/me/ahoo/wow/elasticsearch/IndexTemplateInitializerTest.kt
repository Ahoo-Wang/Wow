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
        snapshotMappings["dynamic_templates"].forEach { template ->
            template.properties().single().value["mapping"]["ignore_above"]
                .asInt().assert().isEqualTo(8191)
        }
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
