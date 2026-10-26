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

import me.ahoo.wow.http.internal.InternalPort
import me.ahoo.wow.runtime.node.ServiceNode
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.ReadinessState
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.server.reactive.context.ReactiveWebServerApplicationContext
import org.springframework.context.ApplicationListener
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** The application of the host tests: the test module's models and the bean they take. */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
class HostTestApp {
    @Bean
    fun decidedCommands(): DecidedCommands = DecidedCommands()
}

/**
 * Records, in order, the readiness changes and the steps of the node's stop, each step with whether the public and
 * the internal ports still accept connections at that moment.
 */
class Timeline {
    val events: MutableList<String> = CopyOnWriteArrayList()
    lateinit var host: Host
}

@Configuration(proxyBeanMethods = false)
class TimelineConfiguration {
    @Bean
    fun timeline(): Timeline = Timeline()

    @Bean
    fun readinessRecorder(timeline: Timeline): ReadinessRecorder = ReadinessRecorder(timeline)

    @Bean
    fun stepRecorder(timeline: Timeline): ServiceNodeListener = ServiceNodeListener { step ->
        val host = timeline.host
        timeline.events += "$step public=${host.publicPortOpen()} internal=${host.internalPortOpen()}"
    }
}

class ReadinessRecorder(private val timeline: Timeline) : ApplicationListener<AvailabilityChangeEvent<*>> {
    override fun onApplicationEvent(event: AvailabilityChangeEvent<*>) {
        (event.state as? ReadinessState)?.let { timeline.events += "readiness $it" }
    }
}

private fun accepts(port: Int): Boolean = try {
    Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
    true
} catch (_: java.io.IOException) {
    false
}

/**
 * A host started in this JVM, its public port at [publicPort] and its internal one at [internalPort]: both the ports
 * the system chose, read once bound.
 */
class Host(val context: ConfigurableApplicationContext) : AutoCloseable {
    val publicPort: Int = (context as ReactiveWebServerApplicationContext).webServer!!.port
    val internalPort: Int = context.getBean(InternalPort::class.java).boundPort
    val node: ServiceNode get() = context.getBean(ServiceNode::class.java)
    val decided: DecidedCommands get() = context.getBean(DecidedCommands::class.java)

    val client: WebTestClient = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:$publicPort")
        .responseTimeout(Duration.ofSeconds(30))
        .build()

    fun publicPortOpen(): Boolean = accepts(publicPort)

    fun internalPortOpen(): Boolean = accepts(internalPort)

    fun post(path: String, body: String? = "{}", headers: Map<String, String> = emptyMap()): Answer =
        client.post().uri(path)
            .headers { http -> headers.forEach { (name, value) -> http.set(name, value) } }
            .contentType(MediaType.APPLICATION_JSON)
            .let { if (body == null) it else it.bodyValue(body) }
            .answer()

    fun get(path: String, headers: Map<String, String> = emptyMap()): Answer =
        client.get().uri(path).headers { http -> headers.forEach { (name, value) -> http.set(name, value) } }.answer()

    override fun close() {
        context.close()
    }
}

/** The response: its status, its content type and its body as JSON (null when it has none). */
class Answer(val status: Int, val contentType: String?, val body: JsonNode?, val headers: HttpHeaders)

private val json: JsonMapper = JsonMapper.builder().build()

fun WebTestClient.RequestHeadersSpec<*>.answer(): Answer {
    val result = exchange().expectBody(String::class.java).returnResult()
    return Answer(
        status = result.status.value(),
        contentType = result.responseHeaders.contentType?.toString(),
        body = result.responseBody?.takeIf { it.isNotBlank() }?.let { json.readTree(it) },
        headers = result.responseHeaders,
    )
}

/**
 * The properties every test host starts with: short cluster timings, the ports on the loopback interface, both bound
 * at a port the system chooses, so no other process can take a port picked before the host binds it.
 */
val hostProperties: Map<String, Any> = mapOf(
    "spring.application.name" to "bank-service",
    "spring.main.banner-mode" to "off",
    "server.port" to 0,
    "server.address" to "127.0.0.1",
    "wow.internal.port" to 0,
    "wow.internal.host" to "127.0.0.1",
    "wow.cluster.drain-timeout" to "1s",
    "wow.cluster.lease-ttl" to "3s",
    "wow.cluster.renew-interval" to "1s",
    "wow.cluster.member-ttl" to "3s",
    "wow.cluster.heartbeat-interval" to "1s",
    "wow.cluster.reconcile-interval" to "200ms",
    "wow.cluster.route-refresh-interval" to "200ms",
    "wow.subscription.checkpoint-interval" to "100ms",
    "wow.subscription.stop-timeout" to "2s",
    "wow.wait.max-timeout" to "15s",
    // springdoc is on the tests' classpath for the bridge's tests (HostSpringdocTest), which turn it on.
    "wow.http.openapi.springdoc.enabled" to false,
)

/** Starts a host of [sources] with [hostProperties] and [properties] over them. */
fun startHost(
    vararg properties: Pair<String, Any>,
    sources: List<Class<*>> = listOf(HostTestApp::class.java),
): Host {
    val context = SpringApplicationBuilder(*sources.toTypedArray())
        .properties(hostProperties + properties.toMap())
        .run()
    return Host(context)
}
