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

package me.ahoo.wow.example.eshop.catalog.server

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.OWNER
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.ROLES
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.USER
import me.ahoo.wow.test.server.Answer
import me.ahoo.wow.test.server.WowServer
import me.ahoo.wow.test.server.wowServer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import tools.jackson.databind.JsonNode

private const val OK = 200
private const val BAD_REQUEST = 400

/** The body as JSON, which the response must have. */
val Answer.json: JsonNode get() = requireNotNull(body) { "The response has no body." }

/** Asserts this is the RFC 9457 problem of [code] with [status], and returns it. */
fun Answer.assertProblem(status: Int, code: String): Answer {
    this.status.assert().describedAs(text).isEqualTo(status)
    header(HttpHeaders.CONTENT_TYPE).assert().isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
    json["type"].asString().assert().isEqualTo("urn:wow:error:$code")
    json["code"].asString().assert().isEqualTo(code)
    return this
}

/**
 * Asserts this is the problem `wow.command.invalid` (400) the gateway answers a command that breaks its body's
 * constraints with, its violations exactly [violations] (path to constraint), and returns it.
 */
fun Answer.assertInvalid(vararg violations: Pair<String, String>): Answer {
    assertProblem(BAD_REQUEST, FrameworkErrors.COMMAND_INVALID)
    json["violations"].values().map { it["path"].asString() to it["code"].asString() }
        .assert().containsExactly(*violations)
    return this
}

/** Asserts the command or query succeeded, and returns it. */
fun Answer.assertOk(): Answer {
    status.assert().describedAs(text).isEqualTo(OK)
    return this
}

/** The catalog server started in this JVM, called over HTTP on its public port. */
class CatalogNode(private val server: WowServer) : AutoCloseable {
    val context: ConfigurableApplicationContext get() = server.context

    fun post(path: String, body: String = "{}", headers: Map<String, String> = emptyMap()): Answer =
        server.http.post(path, body, headers)

    fun get(path: String, headers: Map<String, String> = emptyMap()): Answer = server.http.get(path, headers)

    /** POSTs [body] to [path] asking for server-sent events: each event's name and data, in the order they came. */
    fun stream(path: String, body: String, headers: Map<String, String> = emptyMap()): List<Pair<String, String>> {
        val answer = post(path, body, headers + (HttpHeaders.ACCEPT to MediaType.TEXT_EVENT_STREAM_VALUE)).assertOk()
        MediaType.parseMediaType(
            answer.header(HttpHeaders.CONTENT_TYPE)!!
        ).isCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .assert().isTrue()
        return answer.text.split("\n\n").filter { it.isNotBlank() }.map { block ->
            val lines = block.lines()
            val name = lines.first { it.startsWith("event:") }.removePrefix("event:").trim()
            name to lines.filter { it.startsWith("data:") }.joinToString("") { it.removePrefix("data:") }
        }
    }

    override fun close() {
        server.close()
    }
}

/** An employee of the default tenant, a tenant-level caller ([DevelopmentCallerResolver]): it changes the catalog. */
val EMPLOYEE: Map<String, String> = mapOf(USER to "erin", ROLES to "employee")

/** A buyer of the default tenant, an owner-level caller ([DevelopmentCallerResolver]): it reads the catalog. */
val BUYER: Map<String, String> = mapOf(USER to "alice", OWNER to "alice")

/**
 * Starts the catalog server on random ports of the loopback interface, with [properties] over its `application.yaml`,
 * in [profiles], with the application's [sources] beside it. A request without headers is the anonymous caller; one
 * with [EMPLOYEE]'s or [BUYER]'s is an employee or a buyer ([DevelopmentCallerResolver], decision D189).
 */
fun startCatalog(
    vararg properties: Pair<String, Any>,
    // A development profile: the requests name their callers in the Wow-* headers.
    profiles: List<String> = listOf("dev"),
    sources: List<Class<*>> = emptyList(),
): CatalogNode = CatalogNode(wowServer(CatalogServer::class.java, profiles, properties.toMap(), sources))
