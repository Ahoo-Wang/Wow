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

package me.ahoo.wow.test.server

import me.ahoo.wow.http.internal.InternalPort
import me.ahoo.wow.spi.json.WowJson
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Starts the Wow application [App] for a test, with [profiles] active, as [wowServer] of its class does.
 *
 * ```kotlin
 * wowServer<BankServer>("dev").use { server ->
 *     server.http.post("/bank/account", """{"owner":"alice"}""").status.assert().isEqualTo(200)
 * }
 * ```
 */
inline fun <reified App : Any> wowServer(
    vararg profiles: String,
    properties: Map<String, Any?> = emptyMap(),
    sources: List<Class<*>> = emptyList(),
): WowServer = wowServer(App::class.java, profiles.toList(), properties, sources)

/**
 * Starts the Wow application of [application] and [sources] for a test, with [profiles] active, and gives it back
 * running: both its ports bound on the loopback interface, at ports the system chooses, and its drain, stop and wait
 * timeouts short ([TEST_PROPERTIES]), so the test reaches it at once and closing it ([WowServer.close]) is quick.
 * [properties] add to them and override them, as command-line arguments, over the application's own configuration;
 * a null value leaves a key out.
 */
fun wowServer(
    application: Class<*>,
    profiles: List<String> = emptyList(),
    properties: Map<String, Any?> = emptyMap(),
    sources: List<Class<*>> = emptyList(),
): WowServer {
    val arguments = (TEST_PROPERTIES + properties).mapNotNull { (key, value) -> value?.let { "--$key=$it" } }
    val context = SpringApplicationBuilder(application, *sources.toTypedArray())
        .profiles(*profiles.toTypedArray())
        .run(*arguments.toTypedArray())
    return WowServer(context)
}

/**
 * The properties a test server starts with: its public and internal ports on the loopback interface, each at a port the
 * system chooses, so no other process takes a port picked before it binds it, and the timeouts a node waits on at
 * its close short, as a test's few commands need.
 */
val TEST_PROPERTIES: Map<String, Any> = mapOf(
    "spring.main.banner-mode" to "off",
    "server.port" to 0,
    "server.address" to LOOPBACK,
    "wow.internal.port" to 0,
    "wow.internal.host" to LOOPBACK,
    "wow.cluster.drain-timeout" to "1s",
    "wow.subscription.stop-timeout" to "2s",
    "wow.subscription.checkpoint-interval" to "100ms",
    "wow.wait.max-timeout" to "15s",
)

private const val LOOPBACK = "127.0.0.1"

/**
 * A Wow application a test started ([wowServer]): its [context], the [publicPort] and [internalPort] it bound, and
 * clients of them ([http], [internal]). Closing it closes the application and the clients.
 */
class WowServer(val context: ConfigurableApplicationContext) : AutoCloseable {
    /** The port of the public server: the HTTP API. */
    val publicPort: Int = context.environment.getRequiredProperty("local.server.port", Int::class.java)

    /** The port of the internal server: the cluster channel, the wait signals, the services' calls. */
    val internalPort: Int = context.getBean(InternalPort::class.java).boundPort

    /** A client of the public port. */
    val http: WowHttp = WowHttp(URI.create("http://$LOOPBACK:$publicPort"))

    private var internalHttp: WowHttp? = null

    /** A client of the internal port, made on first use. */
    val internal: WowHttp
        get() = internalHttp ?: WowHttp(URI.create("http://$LOOPBACK:$internalPort")).also { internalHttp = it }

    /** The application's bean of [type]. */
    fun <T : Any> bean(type: Class<T>): T = context.getBean(type)

    /** The application's bean of [T]. */
    inline fun <reified T : Any> bean(): T = bean(T::class.java)

    override fun close() {
        try {
            context.close()
        } finally {
            http.close()
            internalHttp?.close()
        }
    }
}

/**
 * A JSON client of a server's port at [base], over HTTP/1.1: each call answers with the status, headers and body the
 * server gave, waiting at most [timeout] for it.
 */
class WowHttp(val base: URI, private val timeout: Duration = Duration.ofSeconds(30)) : AutoCloseable {
    private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build()

    /** `GET` [path] with [headers]. */
    fun get(path: String, headers: Map<String, String> = emptyMap()): Answer = request("GET", path, null, headers)

    /** `POST` [json] to [path] with [headers]; an empty object by default. */
    fun post(path: String, json: String? = "{}", headers: Map<String, String> = emptyMap()): Answer =
        request("POST", path, json, headers)

    /** [method] [path] with [headers], sending [json] as `application/json` when it is not null. */
    fun request(method: String, path: String, json: String? = null, headers: Map<String, String> = emptyMap()): Answer {
        val body = json?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        val request = HttpRequest.newBuilder(base.resolve(path)).timeout(timeout).method(method, body).apply {
            if (json != null) header("Content-Type", "application/json")
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return Answer(response.statusCode(), response.headers().map(), response.body())
    }

    override fun close() {
        client.shutdownNow()
    }
}

/** A server's answer: its [status], [headers] and body, as [text] and, when it is JSON, as [body]. */
class Answer(val status: Int, val headers: Map<String, List<String>>, val text: String) {
    /** The body as JSON; null when it is empty or not JSON. */
    val body: JsonNode? by lazy {
        text.takeIf { it.isNotBlank() }?.let { runCatching { WowJson.mapper.readTree(it) }.getOrNull() }
    }

    /** The first value of the header [name], whatever its case; null when the answer has none. */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    override fun toString(): String = "Answer($status, $text)"
}
