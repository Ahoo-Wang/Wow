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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.elasticsearch.core.ReactiveElasticsearchOperations
import org.springframework.data.elasticsearch.core.ReactiveIndexOperations
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class IndexTemplateInitializerTest {
    private val indexOperations = mockk<ReactiveIndexOperations>()
    private val elasticsearchOperations = mockk<ReactiveElasticsearchOperations> {
        every { indexOps(any<IndexCoordinates>()) } returns indexOperations
    }
    private val initializer = IndexTemplateInitializer(elasticsearchOperations)

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

    @Test
    @Suppress("DEPRECATION")
    fun `legacy init subscriber should retain its name`() {
        IndexTemplateInitializer.InitSubscriber("legacy").name.assert().isEqualTo("legacy")
    }
}
