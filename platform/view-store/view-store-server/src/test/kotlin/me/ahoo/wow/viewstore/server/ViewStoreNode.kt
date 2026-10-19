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

package me.ahoo.wow.viewstore.server

import me.ahoo.test.asserts.assert
import me.ahoo.wow.http.WowHeaders
import me.ahoo.wow.viewstore.api.ViewStoreContext
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
        headers.contentType.toString().assert().isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        json["code"].asString().assert().isEqualTo(code)
        return this
    }

    /** Asserts the command or query succeeded, and returns it. */
    fun assertOk(): Answer {
        status.assert().describedAs(body.toString()).isEqualTo(OK)
        return this
    }

    /** The id of the aggregate a command's result names. */
    val id: String get() = json["ref"]["id"].asString()

    /** The ids of the snapshots of a list, in their order. */
    val ids: List<String> get() = json.values().map { it["aggregateId"].asString() }

    private companion object {
        const val OK = 200
    }
}

/** Who sends a request: its headers, those of [ViewStoreCallers] and the application. */
data class Who(val headers: Map<String, String>) {
    /** The same caller acting as the owner `(shared)` (`Wow-Scope`). */
    val shared: Who get() = Who(headers + (WowHeaders.SCOPE to """owner="${ViewStoreContext.SHARED_OWNER_ID}""""))

    /** The same caller acting as the owner `(system)` of the tenant `(platform)`. */
    val system: Who
        get() = Who(
            headers + (
                WowHeaders.SCOPE to
                    """tenant="${ViewStoreContext.SYSTEM_TENANT_ID}", owner="${ViewStoreContext.SYSTEM_OWNER_ID}""""
                ),
        )

    /** The same caller in the application [app]. */
    fun inApp(app: String): Who = Who(headers + (ViewStoreContext.APP_ID_HEADER to app))

    /** The same caller without an application. */
    val withoutApp: Who get() = Who(headers - ViewStoreContext.APP_ID_HEADER)

    /** The same caller with [name] set to [value]. */
    fun with(name: String, value: String): Who = Who(headers + (name to value))

    companion object {
        const val APP: String = "orders-console"

        /** A user of [tenant] with [roles], in [APP]. */
        fun user(id: String, tenant: String? = null, vararg roles: String): Who = Who(
            listOfNotNull(
                ViewStoreCallers.USER to id,
                ViewStoreContext.APP_ID_HEADER to APP,
                tenant?.let { ViewStoreCallers.TENANT to it },
                roles.takeIf { it.isNotEmpty() }?.let { ViewStoreCallers.ROLES to it.joinToString(",") },
            ).toMap(),
        )

        /** The anonymous caller in [APP]. */
        val ANONYMOUS: Who = Who(mapOf(ViewStoreContext.APP_ID_HEADER to APP))
    }
}

/** The view store server started in this JVM, called over HTTP on its public port. */
class ViewStoreNode(val context: ConfigurableApplicationContext) : AutoCloseable {
    private val port: Int = context.environment.getRequiredProperty("local.server.port", Int::class.java)

    private val client: WebTestClient = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:$port")
        .responseTimeout(Duration.ofSeconds(30))
        .build()

    fun post(path: String, body: String, who: Who, headers: Map<String, String> = emptyMap()): Answer =
        send(HttpMethod.POST, path, body, who, headers)

    fun get(path: String, who: Who): Answer = send(HttpMethod.GET, path, null, who)

    fun delete(path: String, who: Who): Answer = send(HttpMethod.DELETE, path, null, who)

    private fun send(
        method: HttpMethod,
        path: String,
        body: String?,
        who: Who,
        headers: Map<String, String> = emptyMap(),
    ): Answer {
        val request = client.method(method).uri(path)
            .headers { http -> (who.headers + headers).forEach { (name, value) -> http.set(name, value) } }
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
}

/**
 * Starts the view store server on random ports of the loopback interface, with [properties] over its
 * `application.yaml`, in [profiles], with the further configuration [sources].
 */
fun startViewStore(
    vararg properties: Pair<String, Any>,
    profiles: List<String> = emptyList(),
    sources: List<Class<*>> = emptyList(),
): ViewStoreNode {
    val arguments = mapOf(
        "spring.main.banner-mode" to "off",
        "server.port" to 0,
        "server.address" to "127.0.0.1",
        "wow.internal.port" to 0,
        "wow.internal.host" to "127.0.0.1",
        "wow.cluster.drain-timeout" to "1s",
        "wow.subscription.stop-timeout" to "2s",
        "wow.wait.max-timeout" to "15s",
        ViewStoreConfiguration.TRUST_IDENTITY_HEADERS to true,
    ) + properties.toMap()
    val context = SpringApplicationBuilder(ViewStoreServer::class.java, *sources.toTypedArray())
        .profiles(*profiles.toTypedArray())
        .run(*arguments.map { (name, value) -> "--$name=$value" }.toTypedArray())
    return ViewStoreNode(context)
}
