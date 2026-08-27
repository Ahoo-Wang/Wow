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

package me.ahoo.wow.elasticsearch.query

import co.elastic.clients.elasticsearch.core.ClosePointInTimeRequest
import co.elastic.clients.elasticsearch.core.ClosePointInTimeResponse
import co.elastic.clients.elasticsearch.core.OpenPointInTimeRequest
import co.elastic.clients.elasticsearch.core.OpenPointInTimeResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.time.Duration

class ElasticsearchPointInTimeTest {
    private val client = mockk<ReactiveElasticsearchClient>()

    @Test
    fun `should close latest non-empty pit id on cancellation`() {
        val closeRequest = slot<ClosePointInTimeRequest>()
        val openResponse = mockk<OpenPointInTimeResponse> { every { id() } returns "pit-1" }
        val closeResponse = mockk<ClosePointInTimeResponse> { every { succeeded() } returns true }
        every { client.openPointInTime(any<OpenPointInTimeRequest>()) } returns Mono.just(openResponse)
        every { client.closePointInTime(capture(closeRequest)) } returns Mono.just(closeResponse)

        ElasticsearchPointInTime(client, "index", Duration.ofMinutes(1)).use { session ->
            session.update("pit-2")
            session.update("")
            Flux.never<String>()
        }.test().thenAwait(Duration.ofMillis(1)).thenCancel().verify()

        closeRequest.captured.id().assert().isEqualTo("pit-2")
    }
}
