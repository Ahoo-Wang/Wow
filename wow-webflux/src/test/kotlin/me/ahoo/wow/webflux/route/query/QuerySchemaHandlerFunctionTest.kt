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

package me.ahoo.wow.webflux.route.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.RouterFunctions
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicInteger

class QuerySchemaHandlerFunctionTest {
    @Test
    fun `get should load schema`() {
        val provider = RecordingSchemaProvider()
        val handler = QuerySchemaHandlerFunction(
            provider = provider,
            exceptionHandler = WebFluxRequestExceptionHandler(),
            refresh = false,
        )

        val model = client(handler).get().uri("/").exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
            .toJsonNode<tools.jackson.databind.JsonNode>()["model"]
            .stringValue()

        model.assert().isEqualTo("EVENT_STREAM")
        provider.schemaCalls.get().assert().isOne()
        provider.refreshCalls.get().assert().isZero()
    }

    @Test
    fun `post should refresh schema`() {
        val provider = RecordingSchemaProvider()
        val handler = QuerySchemaHandlerFunction(
            provider = provider,
            exceptionHandler = WebFluxRequestExceptionHandler(),
            refresh = true,
        )

        client(handler).post().uri("/").exchange()
            .expectStatus().isOk

        provider.schemaCalls.get().assert().isZero()
        provider.refreshCalls.get().assert().isOne()
    }

    private fun client(handler: QuerySchemaHandlerFunction) = WebTestClient.bindToRouterFunction(
        RouterFunctions.route()
            .GET("/") { request -> handler.handle(request) }
            .POST("/") { request -> handler.handle(request) }
            .build()
    ).build()

    private class RecordingSchemaProvider : QueryModelSchemaProvider {
        val schemaCalls = AtomicInteger()
        val refreshCalls = AtomicInteger()

        override fun schema(): Mono<QueryModelSchema> = Mono.fromSupplier {
            schemaCalls.incrementAndGet()
            SCHEMA
        }

        override fun refresh(): Mono<QueryModelSchema> = Mono.fromSupplier {
            refreshCalls.incrementAndGet()
            SCHEMA
        }
    }

    private companion object {
        val SCHEMA = QueryModelSchema(QueryModel.EVENT_STREAM, emptySet(), emptyMap())
    }
}
