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
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.ops.api.parked.ParkedEntry
import me.ahoo.wow.ops.api.parked.ParkedResolution
import me.ahoo.wow.ops.testing.InMemoryOpsTopics
import me.ahoo.wow.ops.testing.LedgerSimulator
import me.ahoo.wow.runtime.cluster.CommandWire
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.ROLES
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.TENANT
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.USER
import me.ahoo.wow.test.server.Answer
import me.ahoo.wow.test.server.WowServer
import me.ahoo.wow.test.server.wowServer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import tools.jackson.databind.JsonNode
import java.time.Duration

private const val OK = 200

/** The body as JSON, which the response must have. */
val Answer.json: JsonNode get() = requireNotNull(body) { "The response has no body." }

/** Asserts this is the RFC 9457 problem of [code] with [status], and returns it. */
fun Answer.assertProblem(status: Int, code: String): Answer {
    this.status.assert().describedAs(text).isEqualTo(status)
    header(HttpHeaders.CONTENT_TYPE).assert().isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
    json["code"].asString().assert().isEqualTo(code)
    return this
}

/** Asserts the command or query succeeded, and returns it. */
fun Answer.assertOk(): Answer {
    status.assert().describedAs(text).isEqualTo(OK)
    return this
}

/** The ids of the snapshots of a list, in their order. */
val Answer.ids: List<String> get() = json.values().map { it["aggregateId"].asString() }

/** Who sends a request: the headers the host's development callers read. */
data class Who(val headers: Map<String, String>) {
    companion object {
        /** An operator, who acts on the entries. */
        val OPERATOR: Who = Who(mapOf(USER to "alice", ROLES to "viewer, ${OpsRoles.OPERATOR}"))

        /** A caller of the tenant that reads the entries, and may not act on them. */
        val VIEWER: Who = Who(mapOf(USER to "victor", ROLES to "viewer"))

        /** A caller of another tenant. */
        val OUTSIDER: Who = Who(
            mapOf(USER to "olga", TENANT to "t-other", ROLES to OpsRoles.OPERATOR),
        )

        /** No caller: no `Wow-User`. */
        val NOBODY: Who = Who(emptyMap())
    }
}

/**
 * The ops server started in this JVM, called over HTTP on its public port, its topics in memory ([InMemoryOpsTopics]):
 * [publish] and [resolve] stand in for an owning service's ledger, and [ledgerCommands] read what the ops saga sends
 * to an owning context ([LedgerSimulator]).
 */
class OpsNode(val server: WowServer) : AutoCloseable {
    val context: ConfigurableApplicationContext get() = server.context
    val topics: InMemoryTopics = server.bean<InMemoryTopics>()
    private val ledger = LedgerSimulator(server.bean<Manifest>(), topics)

    fun post(path: String, body: String, who: Who): Answer = server.http.post(path, body, who.headers)

    fun get(path: String, who: Who): Answer = server.http.get(path, who.headers)

    /** Publishes [entry] to `wow.ops.commands` as an owning service's ledger does; the id of its ops aggregate. */
    fun publish(entry: ParkedEntry): String = runBlocking { ledger.publish(entry) }

    /** Publishes that [resolution]'s entry is resolved, as the owning service's ledger does. */
    fun resolve(resolution: ParkedResolution) {
        runBlocking { ledger.resolve(resolution) }
    }

    /** The ledger's system commands the ops saga sent to [context]'s command topic, in their order per partition. */
    fun ledgerCommands(context: String): List<CommandWire> = ledger.ledgerCommands(context)

    /** Refuses [command] with [code] and [message], as the owning service does (decision D226). */
    fun refuse(command: CommandWire, code: String, message: String) {
        runBlocking { ledger.refuse(command, code, message) }
    }

    override fun close() {
        server.close()
    }
}

/**
 * Starts the ops server on random ports of the loopback interface, with [properties] over its `application.yaml`, in
 * [profiles], its topics in memory and the further configuration [sources].
 */
fun startOps(
    vararg properties: Pair<String, Any>,
    // A development profile: the requests name their callers in the Wow-* headers.
    profiles: List<String> = listOf("dev"),
    sources: List<Class<*>> = emptyList(),
): OpsNode = OpsNode(
    wowServer(
        OpsServer::class.java,
        profiles,
        mapOf("wow.log.partitions" to 2) + properties.toMap(),
        listOf(InMemoryOpsTopics::class.java) + sources,
    ),
)

/** Waits until [condition] holds, polling, for at most [timeout]. */
fun eventually(what: String, timeout: Duration = Duration.ofSeconds(30), condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (!condition()) {
        check(System.nanoTime() < deadline) { "Not reached in $timeout: $what." }
        Thread.sleep(POLL_MILLIS)
    }
}

private const val POLL_MILLIS = 100L
