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

package me.ahoo.wow.ops.server

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.http.ProblemEncoder
import me.ahoo.cosec.runtime.http.ProblemRenderer
import me.ahoo.cosec.runtime.http.ProblemType
import me.ahoo.cosec.runtime.http.routeProblem
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.cosec.CoSecIdentity
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Bean
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * CoSec's refusals as a service behind the CoSec gateway answers them (the `cosec` profile, CoSec's gateway mode):
 * written by CoSec as they are (`wow.cosec.problems` left out, or `cosec`), or as Wow's problems (`wow`), the body
 * alone changed, the status and CoSec's headers kept (#6591).
 */
@OptIn(ExperimentalWowApi::class, InternalCoSecApi::class)
class OpsCoSecProblemsTest {
    /** A refusal: its status, body, content type and the request id CoSec named it by. */
    private class Refusal(val status: Int, val body: String, val contentType: String?, val requestId: String?)

    /** `GET [path]`, as received, with the identity header [identity] when given. */
    private fun OpsNode.refused(path: String = STATE, identity: String? = null): Refusal {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.publicPort}$path"))
            .apply { identity?.let { header(CoSecIdentity.HEADER, it) } }
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return Refusal(
            response.statusCode(),
            response.body(),
            response.headers().firstValue("Content-Type").orElse(null),
            response.headers().firstValue(REQUEST_ID).orElse(null),
        )
    }

    /**
     * The refusals of CoSec's gateway mode, its identity gate: a malformed identity, and an expired one. The gate checks
     * no path: the gateway in front does, whose refusals keep CoSec's bodies (a path's refusal by a host that decides
     * itself is `CoSecProblemRendererTest`'s).
     */
    private fun OpsNode.refusals(): List<Pair<ProblemType, Refusal>> = listOf(
        ProblemType.INVALID_REQUEST to refused(identity = "not an identity"),
        ProblemType.TOKEN_EXPIRED to refused(identity = CoSecIdentity("alice", ttl = Duration.ofMinutes(-5)).header()),
    )

    @Test
    fun `by default CoSec's refusals are CoSec's bodies, byte for byte`() {
        listOf(emptyArray(), arrayOf<Pair<String, Any>>(PROBLEMS to "cosec")).forEach { properties ->
            startOps(*properties, profiles = listOf("cosec")).use { ops ->
                ops.refusals().forEach { (type, refusal) ->
                    refusal.status.assert().isEqualTo(type.status)
                    refusal.contentType.assert().isEqualTo("application/problem+json")
                    val requestId = checkNotNull(refusal.requestId) { "CoSec names the request it refused." }
                    refusal.body.assert()
                        .isEqualTo(ProblemEncoder.encode(routeProblem(type, requestId)).toString(Charsets.US_ASCII))
                }
            }
        }
    }

    @Test
    fun `with wow the bodies are Wow's problems, CoSec's own in their member, the status and headers CoSec's`() {
        startOps(PROBLEMS to "wow", profiles = listOf("cosec")).use { ops ->
            val (malformed, expired) = ops.refusals().map { it.second }
            malformed.status.assert().isEqualTo(400)
            malformed.contentType.assert().isEqualTo("application/problem+json")
            WowJson.mapper.readTree(malformed.body).let { body ->
                body.propertyNames().toList().assert()
                    .containsExactly("type", "title", "status", "detail", "code", "retry", "requestId", "cosec")
                body["type"].asString().assert().isEqualTo("urn:wow:error:wow.request.malformed")
                body["title"].asString().assert().isEqualTo("Malformed request")
                body["status"].asInt().assert().isEqualTo(400)
                body["detail"].asString().assert().isEqualTo("CoSec refused the request: Invalid Request.")
                body["code"].asString().assert().isEqualTo("wow.request.malformed")
                body["retry"].asString().assert().isEqualTo("none")
                body["requestId"].asString().assert().isEqualTo(malformed.requestId)
                body["cosec"]["type"].asString().assert().isEqualTo("https://cosec.ahoo.me/problems/invalid-request")
                body["cosec"]["code"].asString().assert().isEqualTo("INVALID_REQUEST")
            }
            expired.status.assert().isEqualTo(401)
            WowJson.mapper.readTree(expired.body).let { body ->
                body["type"].asString().assert().isEqualTo("urn:wow:error:wow.auth.unauthenticated")
                body["title"].asString().assert().isEqualTo("Unauthenticated")
                body["code"].asString().assert().isEqualTo("wow.auth.unauthenticated")
                body["detail"].asString().assert().isEqualTo("CoSec refused the request: Token Expired.")
                body["retry"].asString().assert().isEqualTo("none")
                body["requestId"].asString().assert().isEqualTo(expired.requestId)
                body["cosec"]["code"].asString().assert().isEqualTo("TOKEN_EXPIRED")
            }
        }
    }

    @Test
    fun `the application's own renderer wins, and one that fails is replaced by CoSec's body`() {
        val failing = listOf(FailingRenderer::class.java)
        startOps(PROBLEMS to "wow", profiles = listOf("cosec"), sources = failing).use { ops ->
            ops.context.getBeansOfType(ProblemRenderer::class.java).keys.assert().containsExactly("failingRenderer")
            ops.refused(identity = "not an identity").status.assert().isEqualTo(400)
        }
    }

    /**
     * An application's renderer that fails on every refusal: a source of the test that names it, not a configuration
     * the server's component scan finds.
     */
    class FailingRenderer {
        @Bean
        fun failingRenderer(): ProblemRenderer = ProblemRenderer { error("The renderer fails.") }
    }

    private companion object {
        const val PROBLEMS = "wow.cosec.problems"
        const val STATE = "/ops/parked/p-1/state"
        const val REQUEST_ID = "CoSec-Request-Id"
        val client: HttpClient = HttpClient.newHttpClient()
    }
}
