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
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

val json: JsonMapper = JsonMapper.builder().build()

/** A response: its status, its headers and its body as JSON (null when it has none). */
class Answer(val status: Int, val headers: HttpHeaders, val body: JsonNode?) {
    val json: JsonNode get() = requireNotNull(body) { "The response has no body." }

    /** Asserts this is the RFC 9457 problem of [code] with [status], and returns it. */
    fun assertProblem(status: Int, code: String): Answer {
        this.status.assert().describedAs(body.toString()).isEqualTo(status)
        headers.contentType.toString().assert().isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        json["type"].asString().assert().isEqualTo("urn:wow:error:$code")
        json["code"].asString().assert().isEqualTo(code)
        return this
    }

    /** Asserts the command or query succeeded, and returns it. */
    fun assertOk(): Answer {
        status.assert().describedAs(body.toString()).isEqualTo(OK)
        return this
    }

    private companion object {
        const val OK = 200
    }
}

/** The catalog server started in this JVM, called over HTTP on its public port. */
class CatalogNode(val context: ConfigurableApplicationContext) : AutoCloseable {
    private val port: Int = context.environment.getRequiredProperty("local.server.port", Int::class.java)

    private val client: WebTestClient = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:$port")
        .responseTimeout(Duration.ofSeconds(30))
        .build()

    fun post(path: String, body: String = "{}"): Answer =
        client.post().uri(path).contentType(MediaType.APPLICATION_JSON).bodyValue(body).answer()

    fun get(path: String, headers: Map<String, String> = emptyMap()): Answer =
        client.get().uri(path).headers { http -> headers.forEach { (name, value) -> http.set(name, value) } }.answer()

    private fun WebTestClient.RequestHeadersSpec<*>.answer(): Answer {
        val result = exchange().expectBody(String::class.java).returnResult()
        return Answer(
            status = result.status.value(),
            headers = result.responseHeaders,
            body = result.responseBody?.takeIf { it.isNotBlank() }?.let { json.readTree(it) },
        )
    }

    override fun close() {
        context.close()
    }
}

/**
 * Starts the catalog server on random ports of the loopback interface, with [properties] over its `application.yaml`,
 * in [profiles] and with the further configuration [sources]. Every request is the anonymous caller: the catalog has no `CallerResolver`, so its products belong to
 * the default tenant and every shopper sees them (example/eshop/README.md).
 */
fun startCatalog(
    vararg properties: Pair<String, Any>,
    profiles: List<String> = emptyList(),
    sources: List<Class<*>> = emptyList(),
): CatalogNode {
    val arguments = mapOf(
        "spring.main.banner-mode" to "off",
        "server.port" to 0,
        "server.address" to "127.0.0.1",
        "wow.internal.port" to 0,
        "wow.internal.host" to "127.0.0.1",
        "wow.cluster.drain-timeout" to "1s",
        "wow.subscription.stop-timeout" to "2s",
        "wow.wait.max-timeout" to "15s",
    ) + properties.toMap()
    val context = SpringApplicationBuilder(CatalogServer::class.java, *sources.toTypedArray())
        .profiles(*profiles.toTypedArray())
        .run(*arguments.map { (name, value) -> "--$name=$value" }.toTypedArray())
    return CatalogNode(context)
}
