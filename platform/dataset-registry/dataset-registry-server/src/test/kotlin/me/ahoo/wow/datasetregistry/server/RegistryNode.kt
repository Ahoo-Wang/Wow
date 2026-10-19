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

package me.ahoo.wow.datasetregistry.server

import me.ahoo.test.asserts.assert
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
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
        json["code"].asString().assert().describedAs(body.toString()).isEqualTo(code)
        return this
    }

    fun assertOk(): Answer {
        status.assert().describedAs(body.toString()).isEqualTo(OK)
        return this
    }

    private companion object {
        const val OK = 200
    }
}

/** Who sends a request: the headers [DatasetRegistryCallers] reads. */
data class Who(val headers: Map<String, String>) {
    companion object {
        val ADMIN: Who = of("ada", DatasetRegistryCallers.ADMIN)
        val APPROVER: Who = of("abe", DatasetRegistryCallers.APPROVER)
        val VIEWER: Who = of("vic", DatasetRegistryCallers.VIEWER)
        val NOBODY_IN_PARTICULAR: Who = of("nat", "someone")

        fun of(user: String, roles: String): Who =
            Who(mapOf(DatasetRegistryCallers.USER to user, DatasetRegistryCallers.ROLES to roles))
    }
}

/** The dataset registry started in this JVM, called over HTTP on its public port. */
class RegistryNode(val context: ConfigurableApplicationContext) : AutoCloseable {
    private val port: Int = context.environment.getRequiredProperty("local.server.port", Int::class.java)

    private val client: WebTestClient = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:$port")
        .responseTimeout(Duration.ofSeconds(30))
        .build()

    fun post(path: String, body: String, who: Who): Answer = send(HttpMethod.POST, path, body, who)

    fun get(path: String, who: Who): Answer = send(HttpMethod.GET, path, null, who)

    /** Sends the source command [command] (`draft-source`, ...) of [id] with [body]. */
    fun command(id: String, command: String, body: String, who: Who = Who.ADMIN): Answer =
        post("/dataset-registry/source/$id/$command", body, who)

    /** The sources' snapshots, as [who] lists them. */
    fun sources(who: Who = Who.VIEWER): Answer =
        post(QUERY, """{"kind":"list","filter":{"op":"MATCH_ALL"}}""", who)

    /** The state of source [id]. */
    fun state(id: String, who: Who = Who.VIEWER): JsonNode =
        get("/dataset-registry/source/$id/state", who).assertOk().json

    private fun send(method: HttpMethod, path: String, body: String?, who: Who): Answer {
        val request = client.method(method).uri(path)
            .headers { http -> who.headers.forEach { (name, value) -> http.set(name, value) } }
        val spec = if (body == null) request else request.contentType(MediaType.APPLICATION_JSON).bodyValue(body)
        val result = spec.exchange().expectBody(String::class.java).returnResult()
        return Answer(
            status = result.status.value(),
            headers = result.responseHeaders,
            body = result.responseBody?.takeIf { it.isNotBlank() }?.let { json.readTree(it) },
        )
    }

    override fun close() {
        context.close()
    }

    companion object {
        const val QUERY: String = "/dataset-registry/source/snapshot/query"
    }
}

/** Starts the dataset registry on random ports of the loopback interface, with [properties] and further [sources]. */
fun startRegistry(
    vararg properties: Pair<String, Any>,
    profiles: List<String> = emptyList(),
    sources: List<Class<*>> = emptyList(),
): RegistryNode {
    val arguments = mapOf(
        "spring.main.banner-mode" to "off",
        "server.port" to 0,
        "server.address" to "127.0.0.1",
        "wow.internal.port" to 0,
        "wow.internal.host" to "127.0.0.1",
        "wow.log.partitions" to 2,
        "wow.cluster.drain-timeout" to "1s",
        "wow.subscription.stop-timeout" to "2s",
        "wow.wait.max-timeout" to "15s",
        DatasetRegistryConfiguration.TRUST_IDENTITY_HEADERS to true,
    ) + properties.toMap()
    val context = SpringApplicationBuilder(DatasetRegistryServer::class.java, *sources.toTypedArray())
        .profiles(*profiles.toTypedArray())
        .run(*arguments.map { (name, value) -> "--$name=$value" }.toTypedArray())
    return RegistryNode(context)
}

/** Waits until [condition] holds, polling, for at most [timeout]. */
fun eventually(what: String, timeout: Duration = Duration.ofSeconds(30), condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (!condition()) {
        check(System.nanoTime() < deadline) { "Not reached in $timeout: $what." }
        Thread.sleep(POLL_MILLIS)
    }
}

private const val POLL_MILLIS = 100L
