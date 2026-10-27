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

package me.ahoo.wow.analytics.catalog.server

import me.ahoo.test.asserts.assert
import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContext
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.ROLES
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.TENANT
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.USER
import me.ahoo.wow.test.server.Answer
import me.ahoo.wow.test.server.WowServer
import me.ahoo.wow.test.server.wowServer
import org.springframework.context.ConfigurableApplicationContext
import tools.jackson.databind.JsonNode
import java.time.Duration

private const val OK = 200

/** The body as JSON, which the response must have. */
val Answer.json: JsonNode get() = requireNotNull(body) { "The response has no body." }

/** Asserts this is the RFC 9457 problem of [code] with [status], and returns it. */
fun Answer.assertProblem(status: Int, code: String): Answer {
    this.status.assert().describedAs(text).isEqualTo(status)
    json["code"].asString().assert().describedAs(text).isEqualTo(code)
    return this
}

fun Answer.assertOk(): Answer {
    status.assert().describedAs(text).isEqualTo(OK)
    return this
}

/** Who sends a request: the headers the host's development callers read, a caller of the catalog's tenant. */
data class Who(val headers: Map<String, String>) {
    companion object {
        val ADMIN: Who = of("ada", AnalyticsCatalogRoles.ADMIN)
        val APPROVER: Who = of("abe", AnalyticsCatalogRoles.APPROVER)
        val EDITOR: Who = of("eve", AnalyticsCatalogRoles.EDITOR)
        val VIEWER: Who = of("vic", AnalyticsCatalogRoles.VIEWER)
        val NOBODY_IN_PARTICULAR: Who = of("nat", "someone")

        fun of(user: String, roles: String): Who =
            Who(mapOf(USER to user, ROLES to roles, TENANT to AnalyticsCatalogContext.TENANT_ID))
    }
}

/** The analytics catalog started in this JVM, called over HTTP on its public port. */
class CatalogNode(val server: WowServer) : AutoCloseable {
    val context: ConfigurableApplicationContext get() = server.context

    fun post(path: String, body: String, who: Who): Answer = server.http.post(path, body, who.headers)

    fun get(path: String, who: Who): Answer = server.http.get(path, who.headers)

    /** Sends the source command [command] (`draft-source`, ...) of [id] with [body]. */
    fun command(id: String, command: String, body: String, who: Who = Who.ADMIN): Answer =
        post("/analytics-catalog/source/$id/$command", body, who)

    /** Sends the dataset command [command] (`draft-dataset`, ...) of [id] with [body]. */
    fun datasetCommand(id: String, command: String, body: String, who: Who = Who.ADMIN): Answer =
        post("/analytics-catalog/dataset/$id/$command", body, who)

    /** The state of dataset [id]. */
    fun dataset(id: String, who: Who = Who.VIEWER): JsonNode =
        get("/analytics-catalog/dataset/$id/state", who).assertOk().json

    /** The sources' snapshots, as [who] lists them. */
    fun sources(who: Who = Who.VIEWER): Answer =
        post(QUERY, """{"kind":"list","filter":{"op":"MATCH_ALL"}}""", who)

    /** The state of source [id]. */
    fun state(id: String, who: Who = Who.VIEWER): JsonNode =
        get("/analytics-catalog/source/$id/state", who).assertOk().json

    override fun close() {
        server.close()
    }

    companion object {
        const val QUERY: String = "/analytics-catalog/source/snapshot/query"
    }
}

/** Starts the analytics catalog on random ports of the loopback interface, with [properties] and further [sources]. */
fun startCatalog(
    vararg properties: Pair<String, Any>,
    // A development profile: the requests name their callers in the Wow-* headers.
    profiles: List<String> = listOf("dev"),
    sources: List<Class<*>> = emptyList(),
): CatalogNode = CatalogNode(
    wowServer(
        AnalyticsCatalogServer::class.java,
        profiles,
        mapOf("wow.log.partitions" to 2) + properties.toMap(),
        sources,
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
