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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.ledger.LedgerEntry
import me.ahoo.wow.api.ledger.LedgerResolution
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.ops.testing.InMemoryOpsTopics
import me.ahoo.wow.ops.testing.LedgerSimulator
import me.ahoo.wow.runtime.cluster.CommandWire
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

    /** The ids of the snapshots of a list, in their order. */
    val ids: List<String> get() = json.values().map { it["aggregateId"].asString() }

    private companion object {
        const val OK = 200
    }
}

/** Who sends a request: the headers [OpsCallers] reads. */
data class Who(val headers: Map<String, String>) {
    companion object {
        /** An operator, who acts on the entries. */
        val OPERATOR: Who = Who(mapOf(OpsCallers.USER to "alice", OpsCallers.ROLES to "viewer, ${OpsCallers.OPERATOR}"))

        /** A caller of the tenant that reads the entries, and may not act on them. */
        val VIEWER: Who = Who(mapOf(OpsCallers.USER to "victor", OpsCallers.ROLES to "viewer"))

        /** A caller of another tenant. */
        val OUTSIDER: Who = Who(
            mapOf(OpsCallers.USER to "olga", OpsCallers.TENANT to "t-other", OpsCallers.ROLES to OpsCallers.OPERATOR),
        )

        /** No caller: no `X-User`. */
        val NOBODY: Who = Who(emptyMap())
    }
}

/**
 * The ops server started in this JVM, called over HTTP on its public port, its topics in memory ([InMemoryOpsTopics]):
 * [publish] and [resolve] stand in for an owning service's ledger, and [ledgerCommands] read what the ops saga sends
 * to an owning context ([LedgerSimulator]).
 */
class OpsNode(val context: ConfigurableApplicationContext) : AutoCloseable {
    private val port: Int = context.environment.getRequiredProperty("local.server.port", Int::class.java)
    val topics: InMemoryTopics = context.getBean(InMemoryTopics::class.java)
    private val ledger = LedgerSimulator(context.getBean(Manifest::class.java), topics)

    private val client: WebTestClient = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:$port")
        .responseTimeout(Duration.ofSeconds(30))
        .build()

    fun post(path: String, body: String, who: Who): Answer = send(HttpMethod.POST, path, body, who)

    fun get(path: String, who: Who): Answer = send(HttpMethod.GET, path, null, who)

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

    /** Publishes [entry] to `wow.ops.commands` as an owning service's ledger does; the id of its ops aggregate. */
    fun publish(entry: LedgerEntry): String = runBlocking { ledger.publish(entry) }

    /** Publishes that [resolution]'s entry is resolved, as the owning service's ledger does. */
    fun resolve(resolution: LedgerResolution) {
        runBlocking { ledger.resolve(resolution) }
    }

    /** The ledger's system commands the ops saga sent to [context]'s command topic, in their order per partition. */
    fun ledgerCommands(context: String): List<CommandWire> = ledger.ledgerCommands(context)

    override fun close() {
        context.close()
    }
}

/**
 * Starts the ops server on random ports of the loopback interface, with [properties] over its `application.yaml`, in
 * [profiles], its topics in memory and the further configuration [sources].
 */
fun startOps(
    vararg properties: Pair<String, Any>,
    profiles: List<String> = emptyList(),
    sources: List<Class<*>> = emptyList(),
): OpsNode {
    val arguments = mapOf(
        "spring.main.banner-mode" to "off",
        "server.port" to 0,
        "server.address" to "127.0.0.1",
        "wow.internal.port" to 0,
        "wow.internal.host" to "127.0.0.1",
        "wow.log.partitions" to 2,
        "wow.cluster.drain-timeout" to "1s",
        "wow.subscription.stop-timeout" to "2s",
        "wow.subscription.checkpoint-interval" to "100ms",
        "wow.wait.max-timeout" to "15s",
    ) + properties.toMap()
    val context = SpringApplicationBuilder(
        OpsServer::class.java,
        InMemoryOpsTopics::class.java,
        *sources.toTypedArray()
    )
        .profiles(*profiles.toTypedArray())
        .run(*arguments.map { (name, value) -> "--$name=$value" }.toTypedArray())
    return OpsNode(context)
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
