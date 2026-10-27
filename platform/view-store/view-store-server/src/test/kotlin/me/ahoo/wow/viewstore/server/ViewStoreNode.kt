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
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.OWNER
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.ROLES
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.TENANT
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.USER
import me.ahoo.wow.test.server.Answer
import me.ahoo.wow.test.server.WowServer
import me.ahoo.wow.test.server.wowServer
import me.ahoo.wow.viewstore.api.ViewStoreContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import tools.jackson.databind.JsonNode
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.APP as APP_HEADER

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

/** The id of the aggregate a command's result names. */
val Answer.id: String get() = json["ref"]["id"].asString()

/** The ids of the snapshots of a list, in their order. */
val Answer.ids: List<String> get() = json.values().map { it["aggregateId"].asString() }

/** Who sends a request: its headers, those the host's development callers read and the application. */
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
    fun inApp(app: String): Who = Who(headers + (APP_HEADER to app))

    /** The same caller without an application. */
    val withoutApp: Who get() = Who(headers - APP_HEADER)

    /** The same caller with [name] set to [value]. */
    fun with(name: String, value: String): Who = Who(headers + (name to value))

    companion object {
        const val APP: String = "orders-console"

        /** A user of [tenant] with [roles], in [APP]. */
        fun user(id: String, tenant: String? = null, vararg roles: String): Who = Who(
            listOfNotNull(
                USER to id,
                OWNER to id,
                APP_HEADER to APP,
                tenant?.let { TENANT to it },
                roles.takeIf { it.isNotEmpty() }?.let { ROLES to it.joinToString(",") },
            ).toMap(),
        )

        /** The anonymous caller in [APP]. */
        val ANONYMOUS: Who = Who(mapOf(APP_HEADER to APP))
    }
}

/** The view store server started in this JVM, called over HTTP on its public port. */
class ViewStoreNode(val server: WowServer) : AutoCloseable {
    val context: ConfigurableApplicationContext get() = server.context

    fun post(path: String, body: String, who: Who, headers: Map<String, String> = emptyMap()): Answer =
        server.http.post(path, body, who.headers + headers)

    fun get(path: String, who: Who): Answer = server.http.get(path, who.headers)

    fun delete(path: String, who: Who): Answer = server.http.request("DELETE", path, headers = who.headers)

    override fun close() {
        server.close()
    }
}

/**
 * Starts the view store server on random ports of the loopback interface, with [properties] over its
 * `application.yaml`, in [profiles], with the further configuration [sources].
 */
fun startViewStore(
    vararg properties: Pair<String, Any>,
    // A development profile: the requests name their callers in the Wow-* headers.
    profiles: List<String> = listOf("dev"),
    sources: List<Class<*>> = emptyList(),
): ViewStoreNode = ViewStoreNode(wowServer(ViewStoreServer::class.java, profiles, properties.toMap(), sources))
