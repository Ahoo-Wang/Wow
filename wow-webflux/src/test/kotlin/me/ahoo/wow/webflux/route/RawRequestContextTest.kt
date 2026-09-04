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

package me.ahoo.wow.webflux.route

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class RawRequestContextTest {
    private val request = MockServerRequest.builder().build()

    @Test
    fun `should write and read raw request from mono context`() {
        Mono.deferContextual {
            it.getRawRequest().assert().isSameAs(request)
            Mono.empty<Void>()
        }.writeRawRequest(request)
            .test()
            .verifyComplete()
    }

    @Test
    fun `should write and read raw request from flux context`() {
        Flux.deferContextual {
            it.getRawRequest().assert().isSameAs(request)
            Flux.just(request)
        }.writeRawRequest(request)
            .test()
            .expectNext(request)
            .verifyComplete()
    }

    @Test
    fun `should return null when raw request is absent`() {
        Mono.deferContextual {
            it.getRawRequest().assert().isNull()
            Mono.empty<Void>()
        }.test()
            .verifyComplete()
    }

    @Test
    fun `should ignore legacy raw request context key`() {
        Mono.deferContextual {
            it.getRawRequest().assert().isNull()
            Mono.empty<Void>()
        }.contextWrite { it.put("__RAW_REQUEST___", request) }
            .test()
            .verifyComplete()
    }
}
