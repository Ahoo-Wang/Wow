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

package me.ahoo.wow.cosec

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.format.ProblemTypes
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.http.ProblemBodies
import me.ahoo.cosec.runtime.http.ProblemEncoder
import me.ahoo.cosec.runtime.http.ProblemType
import me.ahoo.cosec.runtime.http.routeProblem
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.webflux.PipelineWebFilter
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.manifest.FrameworkErrors
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse
import java.net.URI

/**
 * CoSec's refusals as Wow's problems ([CoSecProblemRenderer], `wow.cosec.problems: wow`, #6591): every type CoSec
 * answers with, by CoSec's own table of them, and a path's refusal behind CoSec's pipeline.
 */
@OptIn(ExperimentalWowApi::class, ExperimentalCoSecApi::class, InternalCoSecApi::class)
class CoSecProblemRendererTest {
    @Test
    fun `every type CoSec answers with should be one Wow writes, so a type CoSec adds fails here`() {
        // CoSec's table of its problem types, which its enum is built over: a new row is a type Wow has not mapped.
        val types = ProblemTypes.rows.map { row -> ProblemType.entries.single { it.slug == row.slug } }
        types.toSet().assert().isEqualTo(CoSecProblemRenderer.KNOWN)
        ProblemType.entries.toSet().assert().isEqualTo(CoSecProblemRenderer.KNOWN)
    }

    @Test
    fun `each type should be the framework code of its status, and an unexpected failure no code`() {
        ProblemType.entries.associateWith(CoSecProblemRenderer::codeOf).assert().isEqualTo(
            mapOf(
                ProblemType.INVALID_REQUEST to FrameworkCodes.REQUEST_MALFORMED,
                ProblemType.UNAUTHORIZED to FrameworkCodes.AUTH_UNAUTHENTICATED,
                ProblemType.TOKEN_INVALID to FrameworkCodes.AUTH_UNAUTHENTICATED,
                ProblemType.TOKEN_EXPIRED to FrameworkCodes.AUTH_UNAUTHENTICATED,
                ProblemType.TOKEN_REVOKED to FrameworkCodes.AUTH_UNAUTHENTICATED,
                ProblemType.FORBIDDEN to FrameworkCodes.AUTH_FORBIDDEN,
                ProblemType.SCOPE_REJECTED to FrameworkCodes.AUTH_FORBIDDEN,
                ProblemType.TOO_MANY_REQUESTS to FrameworkCodes.REQUEST_RATE_LIMITED,
                ProblemType.UNAVAILABLE to FrameworkCodes.AUTH_UNAVAILABLE,
                ProblemType.INTERNAL_ERROR to null,
            ),
        )
        // The status CoSec writes is the one Wow's catalog gives the code.
        ProblemType.entries.forEach { type ->
            CoSecProblemRenderer.codeOf(type)?.let { code ->
                FrameworkErrors.BY_CODE.getValue(code).status.assert().describedAs(type.name).isEqualTo(type.status)
            }
        }
    }

    @Test
    fun `each refusal's body should be Wow's problem, CoSec's type and code in their member`() {
        val renderer = CoSecProblemRenderer()
        ProblemType.entries.forEach { type ->
            val body = json.readTree(renderer.render(routeProblem(type, "req-1")))
            val code = CoSecProblemRenderer.codeOf(type)
            body["status"].asInt().assert().describedAs(type.name).isEqualTo(type.status)
            body["requestId"].asString().assert().isEqualTo("req-1")
            // A refusal with no trace has no traceId.
            body.has("traceId").assert().isFalse()
            body[CoSecProblemRenderer.COSEC]["type"].asString().assert().isEqualTo(type.uri)
            body[CoSecProblemRenderer.COSEC]["code"].asString().assert().isEqualTo(type.code)
            if (code == null) {
                body["type"].asString().assert().isEqualTo("about:blank")
                body["title"].asString().assert().isEqualTo("Internal Server Error")
                body["detail"].asString().assert().isEqualTo("The request failed with an unexpected error.")
                body.has("code").assert().isFalse()
            } else {
                body["type"].asString().assert().isEqualTo("urn:wow:error:$code")
                body["title"].asString().assert().isEqualTo(FrameworkErrors.TITLES.getValue(code))
                body["detail"].asString().assert().isEqualTo("CoSec refused the request: ${type.title}.")
                body["code"].asString().assert().isEqualTo(code)
            }
            body["retry"].asString().assert().describedAs(type.name).isEqualTo(
                if (type == ProblemType.TOO_MANY_REQUESTS || type == ProblemType.UNAVAILABLE) "new-request" else "none",
            )
        }
    }

    @Test
    fun `a 503 says a new request may get past it, with no Retry-After, on purpose, as its headers are CoSec's`() {
        // No platform document: CoSec answers every request 503 unavailable, and sets no Retry-After (RFC 0006 Q1).
        val pipeline = Pipeline.builder({ AuthnResult.Anonymous }, InMemorySnapshotDocumentStore()).build()
        val answer = WebTestClient
            .bindToRouterFunction(RouterFunctions.route().GET("/**") { ServerResponse.ok().build() }.build())
            .webFilter<WebTestClient.RouterFunctionSpec>(
                PipelineWebFilter(pipeline, bodies = ProblemBodies(CoSecProblemRenderer())),
            )
            .build()
            .get().uri("/shop/cart/c-1")
            .exchange()
            .expectBody(ByteArray::class.java)
            .returnResult()
        answer.status.value().assert().isEqualTo(503)
        answer.responseHeaders.containsHeader("Retry-After").assert().isFalse()
        json.readTree(answer.responseBody).let { body ->
            body["code"].asString().assert().isEqualTo(FrameworkCodes.AUTH_UNAVAILABLE)
            body["retry"].asString().assert().isEqualTo("new-request")
            body[CoSecProblemRenderer.COSEC]["code"].asString().assert().isEqualTo("UNAVAILABLE")
        }
        // The catalog's wait, which a client takes without the header, is the other 503s'.
        FrameworkErrors.BY_CODE.getValue(FrameworkCodes.AUTH_UNAVAILABLE).retryAfter.assert()
            .isEqualTo(FrameworkErrors.BY_CODE.getValue(FrameworkCodes.STORAGE_UNAVAILABLE).retryAfter)
    }

    @Test
    fun `a path CoSec's pipeline refuses should be Wow's problem with the renderer, and CoSec's body without`() {
        val cosec = CoSecPipeline()
        fun client(bodies: ProblemBodies) = WebTestClient
            .bindToRouterFunction(RouterFunctions.route().GET("/**") { ServerResponse.ok().build() }.build())
            .webFilter<WebTestClient.RouterFunctionSpec>(PipelineWebFilter(cosec.pipeline, bodies = bodies))
            .build()

        fun refused(client: WebTestClient) = client.get().uri(URI.create("http://localhost/shop/cart/a%3Bb"))
            .exchange()
            .expectBody(ByteArray::class.java)
            .returnResult()

        val wow = refused(client(ProblemBodies(CoSecProblemRenderer())))
        wow.status.value().assert().isEqualTo(400)
        wow.responseHeaders.contentType.toString().assert().isEqualTo("application/problem+json")
        val requestId = wow.responseHeaders.getFirst("CoSec-Request-Id")!!
        json.readTree(wow.responseBody).let { body ->
            body["code"].asString().assert().isEqualTo(FrameworkCodes.REQUEST_MALFORMED)
            body["requestId"].asString().assert().isEqualTo(requestId)
            body[CoSecProblemRenderer.COSEC]["code"].asString().assert().isEqualTo("INVALID_REQUEST")
        }
        val cosecs = refused(client(ProblemBodies.DEFAULT))
        cosecs.status.value().assert().isEqualTo(400)
        cosecs.responseBody!!.toString(Charsets.US_ASCII).assert().isEqualTo(
            ProblemEncoder.encode(
                routeProblem(ProblemType.INVALID_REQUEST, cosecs.responseHeaders.getFirst("CoSec-Request-Id")!!),
            ).toString(Charsets.US_ASCII),
        )
    }
}
