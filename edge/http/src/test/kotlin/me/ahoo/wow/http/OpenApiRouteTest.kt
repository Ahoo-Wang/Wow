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

package me.ahoo.wow.http

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.schedule.JobScope
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.http.command.CommandHandler
import me.ahoo.wow.http.query.QueryHandler
import me.ahoo.wow.http.read.StateHandler
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.kernel.schedule.ScheduleJob
import me.ahoo.wow.manifest.QueryManifest
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.openapi.ManifestOpenApi
import me.ahoo.wow.openapi.WowHttpContract
import me.ahoo.wow.runtime.admission.CommandInput
import me.ahoo.wow.runtime.admission.CommandTarget
import me.ahoo.wow.runtime.admission.CommandTransformer
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import tools.jackson.databind.JsonNode

/**
 * `GET /wow/openapi` (roadmap 5a.2): the document of the routes the node serves, from the same Manifest; every route
 * it lists is one the node routes, and what the routes answer holds to its schemas.
 */
class OpenApiRouteTest {
    @Test
    fun `the document should list the routes the node serves`() {
        val node = QueryNode()
        val answer = node.http.get(WowPaths.OPENAPI)
        answer.status.assert().isEqualTo(200)
        answer.contentType.assert().isEqualTo("application/json")
        val document = answer.body!!
        document["openapi"].asString().assert().isEqualTo(ManifestOpenApi.OPENAPI_VERSION)
        document["info"]["title"].asString().assert().isEqualTo("shop-service")
        val paths = document["paths"].propertyNames().toList()
        paths.assert().contains(
            WowPaths.MANIFEST,
            WowPaths.OPENAPI,
            WowPaths.GLOBAL_ID,
            "/shop/cart/{id}/open-cart",
            "/shop/cart/open-cart",
            "/shop/cart/{id}",
            "/shop/cart/{id}/state",
            "/shop/cart/{id}/state/trace",
            "/shop/cart/snapshot/query",
            "/shop/cart/events/query",
        )
        // No dataset backend, no dataset routes.
        paths.assert().doesNotContain(WowPaths.DATASETS)
    }

    @Test
    fun `the document should follow the options and the models the node serves`() {
        val manifest = queryManifest().let { manifest ->
            manifest.copy(
                contexts = manifest.contexts.map { context ->
                    context.copy(
                        aggregates = context.aggregates.map {
                            it.copy(
                                queries = it.queries + QueryManifest("custom", QueryRoutes.of(CONTEXT, "cart", "custom"))
                            )
                        },
                    )
                },
            )
        }
        val node = HttpNode(
            manifest = manifest,
            options = WowHttpOptions(manifestEndpoint = false, errorStatuses = mapOf(CART_CLOSED to 409)),
        )
        val document = node.get(WowPaths.OPENAPI).body!!
        val paths = document["paths"].propertyNames().toList()
        paths.assert().doesNotContain(WowPaths.MANIFEST, "/shop/cart/snapshot/query", "/shop/cart/custom/query")
        document["components"]["responses"]["shop.command.409"]["x-wow-error-codes"].values().map { it.asString() }
            .assert().contains(CART_EMPTY)
        HttpNode(options = WowHttpOptions(openApiEndpoint = false)).get(WowPaths.OPENAPI).status.assert().isEqualTo(404)
    }

    @Test
    fun `the document should list the route without an id of a command the node transforms`() {
        val transformer = object : CommandTransformer {
            override val input: CommandType = CommandType("$CONTEXT.cart.Checkout", Checkout::class.java)

            override suspend fun transform(input: CommandInput): CommandTarget =
                CommandTarget(input.type, input.aggregateId, input.body, input.headers)
        }
        val document = HttpNode(transformers = listOf(transformer)).get(WowPaths.OPENAPI).body!!
        document["paths"]["/shop/cart/checkout"]["post"]["operationId"].asString()
            .assert().isEqualTo("shop.cart.Checkout.withoutId")
    }

    @Test
    fun `the document should be served to an authenticated caller, as JSON`() {
        val node = HttpNode()
        node.get(WowPaths.OPENAPI, user = null).status.assert().isEqualTo(401)
        node.get(WowPaths.OPENAPI, headers = mapOf("Accept" to "text/plain")).status.assert().isEqualTo(406)
    }

    @Test
    fun `every route the document lists should be routed`() {
        val node = QueryNode()
        val document = node.http.get(WowPaths.OPENAPI).body!!
        val operations = document["paths"].properties().flatMap { (path, item) ->
            item.propertyNames().map { method -> method to path }
        }
        operations.assert().hasSizeGreaterThan(10)
        operations.forEach { (method, path) ->
            val uri = path.replace("{id}", "c-1")
            val answer = node.http.client.method(HttpMethod.valueOf(method.uppercase())).uri(uri)
                .header("X-User", "alice")
                .header("Content-Type", "application/json")
                .bodyValue("""{"kind":"count","filter":{"op":"MATCH_ALL"}}""")
                .exchange().expectBody(String::class.java).returnResult()
            val unrouted = answer.status.value() == 404 && answer.responseBody.orEmpty().contains("No route")
            unrouted.assert().describedAs("$method $path is not routed").isFalse()
        }
    }

    @Test
    fun `the job routes of a context with jobs should be documented and routed`() {
        val report = ScheduleJob("report", ScheduleRule.Cron("0 0 9 * * *"), JobScope.TENANT)
        val node = HttpNode(jobs = listOf(report))
        val paths = node.get(WowPaths.OPENAPI).body!!["paths"]
        listOf("enable", "reschedule", "disable").forEach { command ->
            val operation = paths["/shop/schedule/{id}/wow.schedule.$command"]["post"]
            operation["tags"][0].asString().assert().isEqualTo("shop.schedule")
            operation["description"].asString().assert().contains("`report`")
        }
        val enabled = node.post("/shop/schedule/report/wow.schedule.enable", """{"job":"report"}""")
        enabled.status.assert().isEqualTo(200)
        HttpNode().get(
            WowPaths.OPENAPI
        ).body!!["paths"].propertyNames().assert().noneMatch { it.contains("/schedule/") }
    }

    @Test
    fun `the names the document gives should be the edge's`() {
        WowHttpContract.MANIFEST_PATH.assert().isEqualTo(WowPaths.MANIFEST)
        WowHttpContract.OPENAPI_PATH.assert().isEqualTo(WowPaths.OPENAPI)
        WowHttpContract.GLOBAL_ID_PATH.assert().isEqualTo(WowPaths.GLOBAL_ID)
        WowHttpContract.DATASETS_PATH.assert().isEqualTo(WowPaths.DATASETS)
        WowHttpContract.STATE_SEGMENT.assert().isEqualTo(WowPaths.STATE)
        WowHttpContract.TRACE_SEGMENT.assert().isEqualTo(WowPaths.TRACE)
        WowHttpContract.VERSION_PARAMETER.assert().isEqualTo(WowPaths.VERSION)
        WowHttpContract.AT_PARAMETER.assert().isEqualTo(WowPaths.AT)
        WowHttpContract.FROM_PARAMETER.assert().isEqualTo(WowPaths.FROM)
        WowHttpContract.TO_PARAMETER.assert().isEqualTo(WowPaths.TO)
        WowHttpContract.LIMIT_PARAMETER.assert().isEqualTo(WowPaths.LIMIT)
        WowHttpContract.REQUEST_ID_HEADER.assert().isEqualTo(WowHeaders.REQUEST_ID)
        WowHttpContract.WAIT_HEADER.assert().isEqualTo(WowHeaders.WAIT)
        WowHttpContract.WAIT_TIMEOUT_HEADER.assert().isEqualTo(WowHeaders.WAIT_TIMEOUT)
        WowHttpContract.SCOPE_HEADER.assert().isEqualTo(WowHeaders.SCOPE)
        WowHttpContract.SPACE_HEADER.assert().isEqualTo(WowHeaders.SPACE)
        WowHttpContract.IF_MATCH_HEADER.assert().isEqualTo(WowHeaders.IF_MATCH)
        WowHttpContract.ETAG_HEADER.assert().isEqualTo(WowHeaders.ETAG)
        WowHttpContract.TRACEPARENT_HEADER.assert().isEqualTo(CommandHandler.TRACEPARENT)
        WowHttpContract.MAX_TRACE_LIMIT.assert().isEqualTo(StateHandler.MAX_TRACE_LIMIT)
        WowHttpContract.DEFAULT_TRACE_LIMIT.assert().isEqualTo(StateHandler.DEFAULT_TRACE_LIMIT)
        WowHttpContract.ROW_EVENT.assert().isEqualTo(QueryHandler.ROW)
        WowHttpContract.ERROR_EVENT.assert().isEqualTo(QueryHandler.ERROR)
    }

    @Test
    fun `what the routes answer should hold to the document's schemas`() {
        val node = QueryNode()
        val document = node.http.get(WowPaths.OPENAPI).body!!
        val opened = node.http.post("/shop/cart/c-1/open-cart", """{"note":"n"}""")
        document.validate("wow.CommandResult", opened.body!!)
        val rejected = node.http.post("/shop/cart/c-1/checkout", """{"note":"closed"}""")
        rejected.status.assert().isEqualTo(422)
        document.validate("wow.Problem", rejected.body!!)
        val state = node.http.get("/shop/cart/c-1/state?version=1")
        state.status.assert().isEqualTo(200)
        document.validate("wow.StateView", state.body!!)
        document.validate("wow.StateTrace", node.http.get("/shop/cart/c-1/state/trace").body!!)
        node.save("c-1")
        val rows = node.query("""{"kind":"list","filter":{"op":"MATCH_ALL"}}""").body!!
        rows.size().assert().isEqualTo(1)
        document.validate("wow.SnapshotRecord", rows[0])
        val streams = node.query(
            """{"kind":"list","filter":{"op":"AGGREGATE_ID","value":"c-1"}}""",
            model = QueryRoutes.EVENTS,
        ).body!!
        document.validate("wow.EventStreamRecord", streams[0])
        val paged = node.query("""{"kind":"paged","filter":{"op":"MATCH_ALL"}}""").body!!
        document.validate("wow.PagedList", paged)
        document.validate("wow.CountResult", node.query("""{"kind":"count","filter":{"op":"MATCH_ALL"}}""").body!!)
    }

    private fun JsonNode.validate(name: String, instance: JsonNode) {
        val root = json.createObjectNode()
            .put("\$schema", ManifestOpenApi.JSON_SCHEMA_DIALECT)
            .put("\$ref", "#/components/schemas/$name")
            .set("components", get("components"))
        val errors = REGISTRY.getSchema(json.writeValueAsString(root), InputFormat.JSON)
            .validate(json.writeValueAsString(instance), InputFormat.JSON)
        errors.map { it.toString() }.assert().describedAs("$name: $instance").isEmpty()
    }

    private companion object {
        val REGISTRY: SchemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
    }
}
