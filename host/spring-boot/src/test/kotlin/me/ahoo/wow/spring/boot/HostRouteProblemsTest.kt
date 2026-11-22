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

package me.ahoo.wow.spring.boot

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.FrameworkCodes
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBody
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * #6591: what each failure Spring WebFlux raises on the application's own routes is answered with, row by row: a
 * request that cannot be read is `wow.request.malformed` naming what does not read; a status with nothing more to say
 * (no route, a method, a media type) stays the problem of its status alone (`about:blank`), on purpose, with the
 * headers its status calls for.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HostRouteProblemsTest {
    private val host = startHost(sources = listOf(HostTestApp::class.java, ProblemRoutesApp::class.java))

    @AfterAll
    fun close() = host.close()

    class Answered(val status: Int, val contentType: String?, val headers: HttpHeaders, val body: JsonNode)

    private fun answer(spec: WebTestClient.RequestHeadersSpec<*>): Answered {
        val result = spec.exchange().expectBody(String::class.java).returnResult()
        return Answered(
            result.status.value(),
            result.responseHeaders.contentType?.toString(),
            result.responseHeaders,
            json.readTree(result.responseBody.orEmpty()),
        )
    }

    private fun get(path: String): Answered = answer(host.client.get().uri(path))

    private fun postJson(path: String, body: String): Answered =
        answer(host.client.post().uri(path).contentType(MediaType.APPLICATION_JSON).bodyValue(body))

    private fun Answered.assertStatusAlone(status: Int, detail: String?) {
        this.status.assert().isEqualTo(status)
        contentType.assert().isEqualTo("application/problem+json")
        body["type"].asString().assert().isEqualTo("about:blank")
        body.has("code").assert().isFalse()
        body["title"].asString().assert().isEqualTo(HttpStatus.valueOf(status).reasonPhrase)
        if (detail == null) body.has("detail").assert().isFalse() else body["detail"].asString().assert().isEqualTo(detail)
    }

    private fun Answered.assertMalformed(path: String, detail: String) {
        status.assert().isEqualTo(400)
        contentType.assert().isEqualTo("application/problem+json")
        body["type"].asString().assert().isEqualTo("urn:wow:error:${FrameworkCodes.REQUEST_MALFORMED}")
        body["code"].asString().assert().isEqualTo(FrameworkCodes.REQUEST_MALFORMED)
        body["title"].asString().assert().isEqualTo("Malformed request")
        body["detail"].asString().assert().isEqualTo(detail)
        body["retry"].asString().assert().isEqualTo("none")
        body["violations"].size().assert().isEqualTo(1)
        body["violations"][0]["path"].asString().assert().isEqualTo(path)
        body["violations"][0]["code"].asString().assert().isEqualTo(FrameworkCodes.REQUEST_MALFORMED)
        body["violations"][0]["message"].asString().assert().isEqualTo(detail)
    }

    @Test
    fun `no route is 404 of its status alone, its detail naming the method and path and not the host`() {
        val answer = get("/nowhere?x=1")
        answer.assertStatusAlone(404, "No route GET /nowhere.")
        answer.body.toString().assert().doesNotContain("127.0.0.1")
    }

    @Test
    fun `no route under a context is Wow's own 404 of its status alone`() {
        get("/bank/nowhere/x").assertStatusAlone(404, "No route GET /bank/nowhere/x.")
    }

    @Test
    fun `a method the route does not serve is 405 of its status alone, with Allow`() {
        val answer = answer(host.client.delete().uri("/app/taken"))
        answer.assertStatusAlone(405, "Request method 'DELETE' is not supported.")
        answer.headers.allow.map { it.name() }.assert().containsExactly("GET")
    }

    @Test
    fun `a body of a media type the route does not take is 415 of its status alone, with Accept`() {
        val answer = answer(
            host.client.post().uri("/app/items").contentType(MediaType.TEXT_PLAIN).bodyValue("x"),
        )
        answer.assertStatusAlone(415, "Content type 'text/plain' not supported")
        answer.headers.accept.assert().containsExactly(MediaType.APPLICATION_JSON)
    }

    @Test
    fun `an Accept the route does not answer is 406 of its status alone`() {
        answer(host.client.get().uri("/app/count?n=1").accept(MediaType.APPLICATION_XML))
            .assertStatusAlone(406, "Could not find acceptable representation")
    }

    @Test
    fun `a body that cannot be read is wow-request-malformed naming the body, on annotated and functional routes`() {
        postJson("/app/items", """{"name":""").assertMalformed("body", "Failed to read HTTP message")
        postJson("/app/fn/items", """{"name":""").assertMalformed("body", "Failed to read HTTP message")
    }

    @Test
    fun `a missing parameter is wow-request-malformed naming it`() {
        get("/app/count").assertMalformed("n", "Required query parameter 'n' is not present.")
    }

    @Test
    fun `a parameter of the wrong type is wow-request-malformed naming it`() {
        get("/app/count?n=many").assertMalformed("n", "Type mismatch.")
    }

    @Test
    fun `a body that fails its @Valid constraints is 400 of its status alone, saying only that validation failed`() {
        postJson("/app/items", """{"name":" ","quantity":0}""").assertStatusAlone(400, "Validation failure")
    }

    @Test
    fun `a parameter that fails its method's constraints is 400 of its status alone, saying only that validation failed`() {
        get("/app/limit?limit=0").assertStatusAlone(400, "Validation failure")
    }

    @Test
    fun `a service's ConstraintViolationException is a 500 that says nothing of it`() {
        get("/app/service?n=0").assertStatusAlone(500, "The request failed with an unexpected error.")
    }

    @Test
    fun `the application's own ResponseStatusException is the problem of its status and reason alone`() {
        get("/app/taken").assertStatusAlone(409, "Taken.")
        get("/app/gone").assertStatusAlone(404, null)
    }

    private companion object {
        val json: JsonMapper = JsonMapper.builder().build()
    }
}

/** A body the application's routes read, with its constraints. */
data class ProblemItem(@field:NotBlank val name: String, @field:Min(1) val quantity: Int)

/** A service whose method checks its arguments (`@Validated`): a violation is its own contract's. */
@Validated
open class ProblemLimits {
    open fun take(@Min(1) n: Int): Int = n
}

/** The application's annotated routes, beside Wow's. */
@RestController
class ProblemController(private val limits: ProblemLimits) {
    @GetMapping("/app/count", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun count(@RequestParam n: Int): Map<String, Int> = mapOf("n" to n)

    @PostMapping("/app/items", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun items(@Valid @RequestBody item: ProblemItem): ProblemItem = item

    @GetMapping("/app/limit")
    fun limit(@RequestParam @Min(1) limit: Int): Int = limit

    @GetMapping("/app/service")
    fun service(@RequestParam n: Int): Int = limits.take(n)

    @GetMapping("/app/taken")
    fun taken(): String = throw ResponseStatusException(HttpStatus.CONFLICT, "Taken.")

    @GetMapping("/app/gone")
    fun gone(): String = throw ResponseStatusException(HttpStatus.NOT_FOUND)
}

/** The application's routes: annotated, and one functional route that reads its body. */
@Configuration(proxyBeanMethods = false)
class ProblemRoutesApp {
    @Bean
    fun problemLimits(): ProblemLimits = ProblemLimits()

    @Bean
    fun problemController(limits: ProblemLimits): ProblemController = ProblemController(limits)

    @Bean
    fun problemFunctionalRoutes(): RouterFunction<ServerResponse> = coRouter {
        POST("/app/fn/items") { request -> ServerResponse.ok().bodyValueAndAwait(request.awaitBody<ProblemItem>()) }
    }
}
