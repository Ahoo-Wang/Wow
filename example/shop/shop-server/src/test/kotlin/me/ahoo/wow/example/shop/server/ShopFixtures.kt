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

package me.ahoo.wow.example.shop.server

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.runtime.admission.Caller
import me.ahoo.wow.runtime.node.ServiceNode
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.JdkClientHttpConnector
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.TimeoutException

/**
 * The callers of the tests: the user the `X-User` header names owns what it creates, the scope of its commands
 * (decision D93). A request without the header carries no credentials. CoSec's resolver replaces this in a real
 * deployment.
 */
@Configuration(proxyBeanMethods = false)
class HeaderCallers {
    @Bean
    fun callerResolver(): CallerResolver = CallerResolver { request ->
        request.headers().firstHeader(USER)?.let { user -> Caller(scope = Scope(ownerId = user), subject = user) }
    }

    companion object {
        const val USER: String = "X-User"
    }
}

/** The response: its status, its headers and its body as JSON (null when it has none). */
class Answer(val status: Int, val headers: HttpHeaders, val body: JsonNode?) {
    val json: JsonNode get() = requireNotNull(body) { "The response has no body." }

    /** Asserts this is the problem of [code] with [status], and returns it. */
    fun assertProblem(status: Int, code: String): Answer {
        this.status.assert().isEqualTo(status)
        headers.contentType.toString().assert().isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        json["type"].asString().assert().isEqualTo("urn:wow:error:$code")
        json["code"].asString().assert().isEqualTo(code)
        json["status"].asInt().assert().isEqualTo(status)
        return this
    }
}

val json: JsonMapper = JsonMapper.builder().build()

fun WebTestClient.RequestHeadersSpec<*>.answer(): Answer {
    val result = try {
        exchange().expectBody(String::class.java).returnResult()
    } catch (timeout: IllegalStateException) {
        // A request that is never answered: the threads of this JVM say where it is held up.
        if (timeout.cause !is TimeoutException) throw timeout
        throw AssertionError("No answer in time; the threads of the test JVM:\n${threadDump()}", timeout)
    }
    return Answer(
        status = result.status.value(),
        headers = result.responseHeaders,
        body = result.responseBody?.takeIf { it.isNotBlank() }?.let { json.readTree(it) },
    )
}

/** Every live thread with its state and the top of its stack. */
fun threadDump(): String = Thread.getAllStackTraces().entries.sortedBy { it.key.name }.joinToString(
    "\n"
) { (t, stack) ->
    "\"${t.name}\" ${t.state}" + stack.take(DUMP_FRAMES).joinToString("") { "\n    at $it" }
}

private const val DUMP_FRAMES = 30

/**
 * A shop server started in this JVM: its public port at [publicPort], its internal one at [internalPort].
 *
 * Its [client] is the JDK's HTTP client, with threads and connections of its own: the server, its internal port and
 * WebTestClient's default Reactor Netty client would all run on Reactor Netty's global event loops and connection
 * pool, so a request held up on the client's side could not be told from one the server never answered.
 */
class ShopNode(val context: ConfigurableApplicationContext) : AutoCloseable {
    val publicPort: Int = context.environment.getRequiredProperty("local.server.port", Int::class.java)
    val node: ServiceNode get() = context.getBean(ServiceNode::class.java)

    /** The internal port the node is bound at. */
    val internalPort: Int get() = node.address.substringAfterLast(':').toInt()

    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    val client: WebTestClient = WebTestClient.bindToServer(JdkClientHttpConnector(http))
        .baseUrl("http://127.0.0.1:$publicPort")
        .responseTimeout(Duration.ofSeconds(30))
        .build()

    /** POSTs [body] to [path] as [user] (anonymous when null) with [headers]. */
    fun post(
        path: String,
        body: String = "{}",
        user: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Answer = client.post().uri(path)
        .headers { http ->
            user?.let { http.set(HeaderCallers.USER, it) }
            headers.forEach { (name, value) -> http.set(name, value) }
        }
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .answer()

    fun get(path: String, user: String? = null, headers: Map<String, String> = emptyMap()): Answer =
        client.get().uri(path)
            .headers { http ->
                user?.let { http.set(HeaderCallers.USER, it) }
                headers.forEach { (name, value) -> http.set(name, value) }
            }
            .answer()

    fun delete(path: String, user: String? = null): Answer =
        client.delete().uri(path)
            .headers { http -> user?.let { http.set(HeaderCallers.USER, it) } }
            .answer()

    override fun close() {
        try {
            context.close()
        } finally {
            http.shutdownNow()
        }
    }
}

/**
 * Starts the shop server with the test callers, its public port on a random port of the loopback interface and its
 * internal port at [internalPort] (by default one the system chooses as it binds, so no other process can take it
 * between choosing and binding), with [properties] over its `application.yaml`, in [profiles] and with the further
 * configuration [sources]. The properties are passed as command-line arguments, which take precedence over the
 * application's own configuration.
 */
fun startShop(
    vararg properties: Pair<String, Any>,
    profiles: List<String> = emptyList(),
    sources: List<Class<*>> = emptyList(),
    internalPort: Int = 0,
): ShopNode {
    val arguments = mapOf(
        "spring.main.banner-mode" to "off",
        "server.port" to 0,
        "server.address" to "127.0.0.1",
        "wow.internal.port" to internalPort,
        "wow.internal.host" to "127.0.0.1",
        "wow.cluster.drain-timeout" to "1s",
        "wow.subscription.stop-timeout" to "2s",
        "wow.wait.max-timeout" to "15s",
    ) + properties.toMap()
    val context = SpringApplicationBuilder(ShopServer::class.java, HeaderCallers::class.java, *sources.toTypedArray())
        .profiles(*profiles.toTypedArray())
        .run(*arguments.map { (name, value) -> "--$name=$value" }.toTypedArray())
    return ShopNode(context)
}
