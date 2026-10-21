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

package me.ahoo.wow.rehearsal.driver

import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** A view of the rehearsal: its id, its tenant and its owner (`(shared)` for a shared one), as both versions address it. */
data class ViewRef(val id: String, val tenant: String, val owner: String)

/**
 * One version's routes for the view store: the 9.x view store the example-server embeds (`/view-store/tenant/{tenant}
 * /owner/{owner}/…`, the application in `CoSec-App-Id`), or the V10 view store service (`/view-store/<aggregate>/{id}
 * /<command>`, the caller in `X-User`, `X-Tenant` and `CoSec-App-Id`, with `wow.platform.trust-identity-headers`).
 * Every command is answered once its events are stored. The 9.x store closes its state routes, so a 9.x view is read
 * from its snapshot, which the example-server writes at every version.
 */
sealed class ViewEndpoints(val base: URI) {
    protected val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    /** Creates a view of [owner]'s; the store chooses its id, which [Outcome.id] holds. */
    abstract fun create(tenant: String, owner: String, body: String): Outcome

    abstract fun rename(view: ViewRef, title: String): Outcome

    abstract fun save(view: ViewRef, config: String): Outcome

    abstract fun share(view: ViewRef): Outcome

    abstract fun claim(view: ViewRef, toOwner: String): Outcome

    abstract fun delete(view: ViewRef): Outcome

    abstract fun setPreferences(tenant: String, owner: String, definition: String, body: String): Outcome

    abstract fun setWording(tenant: String, dataset: String, body: String): Outcome

    abstract fun clearWording(tenant: String, dataset: String): Outcome

    /** The views' versions and states, by id; a view that is not there (or deleted) is missing. */
    abstract fun states(views: List<ViewRef>): Map<String, Pair<Int, JsonNode>>

    protected fun send(request: HttpRequest.Builder, body: String?, method: String): HttpResponse<String> = client.send(
        request.timeout(Endpoints.REQUEST_TIMEOUT)
            .header("Content-Type", JSON)
            .header("Accept", JSON)
            .header(APP_ID, APP)
            .method(
                method,
                body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
            )
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    /** The 9.x view store, under the example-server's `/view-store` prefix. */
    class NineX(base: URI) : ViewEndpoints(base) {
        private fun scope(tenant: String, owner: String) = "/view-store/tenant/${segment(
            tenant
        )}/owner/${segment(owner)}"

        override fun create(tenant: String, owner: String, body: String): Outcome =
            command("${scope(tenant, owner)}/view", body, "POST")

        override fun rename(view: ViewRef, title: String): Outcome =
            command("${scope(view.tenant, view.owner)}/view/${view.id}/rename", """{"title":"$title"}""", "PUT")

        override fun save(view: ViewRef, config: String): Outcome =
            command("${scope(view.tenant, view.owner)}/view/${view.id}/save", """{"config":$config}""", "PUT")

        override fun share(view: ViewRef): Outcome =
            command("${scope(view.tenant, view.owner)}/view/${view.id}/share", "{}", "PUT")

        /** A claim is the claiming user's: the route names them as the owner. */
        override fun claim(view: ViewRef, toOwner: String): Outcome =
            command("${scope(view.tenant, toOwner)}/view/${view.id}/claim", """{"toOwnerId":"$toOwner"}""", "PUT")

        override fun delete(view: ViewRef): Outcome =
            command("${scope(view.tenant, view.owner)}/view/${view.id}", "{}", "DELETE")

        override fun setPreferences(tenant: String, owner: String, definition: String, body: String): Outcome =
            command("${scope(tenant, owner)}/definitions/$definition/preferences", body, "PUT")

        override fun setWording(tenant: String, dataset: String, body: String): Outcome =
            command("${scope(tenant, SHARED)}/datasets/$dataset/wording", body, "PUT")

        override fun clearWording(tenant: String, dataset: String): Outcome =
            command("${scope(tenant, SHARED)}/datasets/$dataset/wording", null, "DELETE")

        override fun states(views: List<ViewRef>): Map<String, Pair<Int, JsonNode>> =
            views.groupBy { it.tenant to it.owner }.flatMap { (owner, group) ->
                group.chunked(QUERY_PAGE).flatMap { page -> snapshots(owner.first, owner.second, page) }
            }.toMap()

        /** The snapshots of [page], views of one tenant and owner, by id. */
        private fun snapshots(
            tenant: String,
            owner: String,
            page: List<ViewRef>
        ): List<Pair<String, Pair<Int, JsonNode>>> {
            val ids = page.joinToString(",") { "\"${it.id}\"" }
            val response = send(
                HttpRequest.newBuilder(base.resolve("${scope(tenant, owner)}/view/snapshot/list")),
                """{"condition":{"field":"aggregateId","operator":"IN","value":[$ids]},"limit":${page.size}}""",
                "POST",
            )
            check(response.statusCode() == Endpoints.OK) {
                "9.x snapshot list: http-${response.statusCode()} ${response.body().take(ERROR_BODY)}"
            }
            return Endpoints.MAPPER.readTree(response.body()).iterator().asSequence().map { snapshot ->
                snapshot.get("aggregateId").asString() to (snapshot.get("version").asInt() to snapshot.get("state"))
            }.toList()
        }

        private fun command(path: String, body: String?, method: String): Outcome {
            val request = HttpRequest.newBuilder(base.resolve(path))
                .header("Command-Wait-Stage", "PROCESSED")
                .header("Command-Wait-Timeout", Endpoints.WAIT_MILLIS.toString())
            val response = send(request, body, method)
            val node = runCatching { Endpoints.MAPPER.readTree(response.body()) }.getOrNull()
            val code = node?.get("errorCode")?.asString()
            return if (response.statusCode() == Endpoints.OK && code == "Ok") {
                Outcome(true, node.get("aggregateVersion")?.asInt(), null, node.get("aggregateId")?.asString())
            } else {
                Outcome(false, null, "http-${response.statusCode()} ${code ?: ""}")
            }
        }
    }

    /** The V10 view store service. */
    class V10(base: URI) : ViewEndpoints(base) {
        override fun create(tenant: String, owner: String, body: String): Outcome =
            command(tenant, owner, "view/create-view", body)

        override fun rename(view: ViewRef, title: String): Outcome =
            command(view.tenant, view.owner, "view/${view.id}/rename-view", """{"title":"$title"}""")

        override fun save(view: ViewRef, config: String): Outcome =
            command(view.tenant, view.owner, "view/${view.id}/save-view", """{"config":$config}""")

        override fun share(view: ViewRef): Outcome = command(
            view.tenant,
            view.owner,
            "view/${view.id}/share-view",
            "{}"
        )

        override fun claim(view: ViewRef, toOwner: String): Outcome =
            command(view.tenant, toOwner, "view/${view.id}/claim-view", """{"toOwnerId":"$toOwner"}""")

        override fun delete(view: ViewRef): Outcome =
            throw UnsupportedOperationException("The V10 view store deletes no view in the rehearsal.")

        override fun setPreferences(tenant: String, owner: String, definition: String, body: String): Outcome =
            command(tenant, owner, "view_preferences/set-view-preferences", body)

        override fun setWording(tenant: String, dataset: String, body: String): Outcome =
            command(tenant, SHARED, "dataset_wording/set-dataset-wording", body)

        override fun clearWording(tenant: String, dataset: String): Outcome =
            throw UnsupportedOperationException("The V10 view store clears no wording in the rehearsal.")

        override fun states(views: List<ViewRef>): Map<String, Pair<Int, JsonNode>> = views.mapNotNull { view ->
            val response = send(
                caller(
                    HttpRequest.newBuilder(base.resolve("/view-store/view/${view.id}/state")),
                    view.tenant,
                    view.owner
                ),
                null,
                "GET",
            )
            if (response.statusCode() != Endpoints.OK) {
                return@mapNotNull null
            }
            val node = Endpoints.MAPPER.readTree(response.body())
            if (node.get("deleted")?.asBoolean() == true) null else view.id to (node.get("version").asInt() to node.get("state"))
        }.toMap()

        private fun command(tenant: String, owner: String, path: String, body: String): Outcome {
            val request = caller(HttpRequest.newBuilder(base.resolve("/view-store/$path")), tenant, owner)
                .header("Wow-Wait", "committed")
            val response = send(request, body, "POST")
            val node = runCatching { Endpoints.MAPPER.readTree(response.body()) }.getOrNull()
            val result = node?.get("result") ?: node
            return if (response.statusCode() == Endpoints.OK && result?.get("status")?.asString() == "SUCCEEDED") {
                Outcome(true, result.get("version")?.asInt(), null, node?.get("ref")?.get("id")?.asString())
            } else {
                Outcome(false, null, "http-${response.statusCode()} ${node?.get("code")?.asString() ?: ""}")
            }
        }

        /** The caller V10 reads from the headers: the owner as the user, or the reserved owner `(shared)`. */
        private fun caller(request: HttpRequest.Builder, tenant: String, owner: String): HttpRequest.Builder =
            request.header("X-Tenant", tenant).apply {
                if (owner == SHARED) {
                    header(
                        "Wow-Scope",
                        "owner=\"$SHARED\""
                    ).header("X-User", "rehearsal")
                } else {
                    header("X-User", owner)
                }
            }
    }

    companion object {
        /** The one application the rehearsal's views, preferences and wordings are kept in. */
        const val APP = "rehearsal-app"
        const val SHARED = "(shared)"
        private const val APP_ID = "CoSec-App-Id"
        private const val JSON = "application/json"
        private const val QUERY_PAGE = 200
        private const val ERROR_BODY = 200

        private fun segment(value: String): String = URLEncoder.encode(
            value,
            StandardCharsets.UTF_8
        ).replace("+", "%20")

        fun of(version: String, base: String): ViewEndpoints = when (version) {
            "9x" -> NineX(URI.create(base))
            "v10" -> V10(URI.create(base))
            else -> throw IllegalArgumentException("Unknown version '$version': 9x or v10.")
        }
    }
}
