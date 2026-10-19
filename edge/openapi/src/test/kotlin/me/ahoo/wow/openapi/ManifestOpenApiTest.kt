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

package me.ahoo.wow.openapi

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Creation
import me.ahoo.wow.manifest.ErrorManifest
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.FunctionKind
import me.ahoo.wow.manifest.FunctionManifest
import me.ahoo.wow.manifest.JobManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.QueryManifest
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.SnapshotManifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.manifest.SubscriptionSource
import me.ahoo.wow.manifest.copyWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ManifestOpenApiTest {
    private val document = ManifestOpenApi.write(MANIFEST)

    @Test
    fun `the document should be a well-formed OpenAPI 3_1 document`() {
        OpenApiChecks.check(document)
        document["openapi"].assert().isEqualTo("3.1.0")
        document["jsonSchemaDialect"].assert().isEqualTo("https://json-schema.org/draft/2020-12/schema")
        document.containsKey("servers").assert().isFalse()
    }

    @Test
    fun `info should name the service, or the options' title and no version for a fragment`() {
        info(document).assert().isEqualTo(mapOf("title" to "shop-service", "version" to "1.2.0"))
        val fragment = ManifestOpenApi.write(MANIFEST.copyWith(service = null), OpenApiOptions(title = "Shop"))
        info(fragment).assert().isEqualTo(mapOf("title" to "Shop", "version" to ManifestOpenApi.UNVERSIONED))
        ManifestOpenApi.write(MANIFEST.copyWith(service = ServiceInfo("shop")))
            .let(::info)["version"].assert().isEqualTo(ManifestOpenApi.UNVERSIONED)
    }

    @Test
    fun `tags should be one per aggregate, described by it or by its context, then the framework's`() {
        @Suppress("UNCHECKED_CAST")
        val tags = document["tags"] as List<Map<String, Any?>>
        tags.map { it["name"] }.assert()
            .containsExactly("shop.cart", "shop.order", "shop.snapshot", ManifestOpenApi.FRAMEWORK_TAG)
        tags[0]["description"].assert().isEqualTo("The shop")
        tags[1]["description"].assert().isEqualTo("An order")
    }

    @Test
    fun `a creation command should have a route with the id and one without`() {
        val byId = operation("post", "/shop/cart/{id}/create-cart")
        byId["operationId"].assert().isEqualTo("shop.cart.CreateCart")
        parameters(byId).assert().containsExactly(
            "id",
            "Wow-Request-Id",
            "Wow-Wait",
            "Wow-Wait-Timeout",
            "Wow-Scope",
            "Wow-Space",
            "traceparent",
            "tracestate",
            "baggage",
            "If-Match",
        )
        schemaOf(byId["requestBody"]).assert().isEqualTo("#/components/schemas/sample.CreateCart")
        byId["description"].toString().assert().contains("Creates the aggregate; one that exists is refused.")
            .contains("Produces `CartCreated`.")
        byId["x-wow-command"].assert().isEqualTo(
            mapOf("type" to "shop.cart.CreateCart", "creation" to "REQUIRED", "produces" to listOf("CartCreated")),
        )
        val withoutId = operation("post", "/shop/cart/create-cart")
        withoutId["operationId"].assert().isEqualTo("shop.cart.CreateCart.withoutId")
        parameters(withoutId).assert().containsExactly(
            "Wow-Request-Id",
            "Wow-Wait",
            "Wow-Wait-Timeout",
            "Wow-Scope",
            "Wow-Space",
            "traceparent",
            "tracestate",
            "baggage",
        )
        withoutId["description"].toString().assert().contains("names no aggregate id")
        responses(withoutId).keys.assert().doesNotContain("412")
        responses(byId)["412"].assert().isEqualTo(ref("responses", "shop.command.412"))
    }

    @Test
    fun `commands should describe how they create, what they produce and what they are mounted as`() {
        val put = operation("post", "/shop/cart/{id}/put-item")
        put["description"].toString().assert().contains("Acts on an aggregate that exists.").doesNotContain("Produces")
        put["x-wow-command"].assert().isEqualTo(
            mapOf("type" to "shop.cart.PutItem", "aliases" to listOf("AddItem"), "creation" to "NONE"),
        )
        paths().containsKey("/shop/cart/put-item").assert().isFalse()
        val open = operation("post", "/shop/cart/open")
        open["description"].toString().assert().contains("Creates the aggregate when it does not exist.")
            .doesNotContain("Produces")
        val import = operation("post", "/shop/cart/import")
        import["description"].toString().assert().contains("A mounted command (decision D182)")
        import["x-wow-command"].assert().isEqualTo(
            mapOf(
                "type" to "shop.cart.Import",
                "creation" to "NONE",
                "mounted" to true,
                "transformsTo" to listOf("shop.cart.PutItem"),
            ),
        )
        operation("post", "/shop/cart/{id}/import")["operationId"].assert().isEqualTo("shop.cart.Import")
    }

    @Test
    fun `a command should answer its result as JSON or as server-sent events, and every error as a problem`() {
        val responses = responses(operation("post", "/shop/cart/{id}/put-item"))
        responses.keys.assert().containsExactly(
            "200", "202", "400", "401", "403", "404", "406", "409", "410", "412", "415", "422", "429", "500", "503",
            "504",
        )
        val ok = response("wow.command.200")
        content(ok).keys.assert().containsExactly("application/json", "text/event-stream")
        content(ok)["text/event-stream"].assert().isEqualTo(
            mapOf(
                "schema" to mapOf("type" to "string", "description" to "Server-sent events."),
                "x-wow-events" to mapOf(
                    "event" to listOf("accepted", "committed", "handled"),
                    "data" to ref("schemas", WowSchemas.COMMAND_RESULT),
                ),
            ),
        )
        responses["409"].assert().isEqualTo(ref("responses", "shop.command.409"))
        val conflict = response("shop.command.409")
        conflict["x-wow-error-codes"].assert().isEqualTo(
            listOf(
                FrameworkErrors.AGGREGATE_EXISTS,
                FrameworkErrors.AGGREGATE_VERSION_MISMATCH,
                FrameworkErrors.COMMAND_REQUEST_ID_REUSED,
                FrameworkErrors.COMMAND_CONTENDED,
                "shop.cart.full",
            ),
        )
        conflict["description"].toString().assert().contains("`shop.cart.full`: The cart is full.")
        content(conflict).keys.assert().containsExactly("application/problem+json")
        val rejected = response("shop.command.422")
        rejected["x-wow-error-codes"].assert().isEqualTo(listOf("shop.cart.closed"))
        rejected["description"].toString().assert().startsWith("`shop.cart.closed`\nA rejection by the domain")
        response("shop.command.503")["headers"].assert().isEqualTo(
            mapOf("Retry-After" to ref("headers", "Retry-After")),
        )
        response("shop.command.406").containsKey("x-wow-error-codes").assert().isFalse()
    }

    @Test
    fun `lifecycle commands should be routed as the edge routes them, with their own bodies`() {
        val delete = operation("delete", "/shop/cart/{id}")
        delete["operationId"].assert().isEqualTo("shop.cart.wow.lifecycle.DeleteAggregate")
        delete["summary"].assert().isEqualTo("DeleteAggregate")
        schemaOf(delete["requestBody"]).assert().isEqualTo("#/components/schemas/wow.lifecycle.DeleteAggregate")
        val tags = operation("post", "/shop/cart/{id}/wow.lifecycle.apply-tags")
        schemaOf(tags["requestBody"]).assert().isEqualTo("#/components/schemas/wow.lifecycle.ApplyTags")
        responses(tags)["409"].assert().isEqualTo(ref("responses", "wow.command.409"))
        responses(tags).keys.assert().doesNotContain("422")
        @Suppress("UNCHECKED_CAST")
        val archive = (operation("post", "/shop/cart/{id}/wow.lifecycle.archive")["requestBody"] as Map<String, Any?>)
        archive["content"].assert().isEqualTo(
            mapOf(
                "application/json" to mapOf("schema" to mapOf("type" to "object", "properties" to emptyMap<String, Any?>()))
            ),
        )
    }

    @Test
    fun `a lifecycle body and a command description the Manifest holds should be the document's`() {
        val body = mapOf(
            "type" to "object",
            "properties" to mapOf("tags" to mapOf("type" to "object")),
            "required" to listOf("tags"),
        )
        val described = MANIFEST.copyWith(
            contexts = MANIFEST.contexts.map { context ->
                context.copyWith(
                    aggregates = context.aggregates.map { aggregate ->
                        aggregate.copyWith(
                            commands = aggregate.commands.map {
                                if (it.name == "PutItem") {
                                    it.copyWith(description = "Puts an item into the cart.")
                                } else {
                                    it
                                }
                            },
                        )
                    },
                )
            },
            defs = MANIFEST.defs + ("wow.lifecycle.ApplyTags" to body),
        )
        val written = ManifestOpenApi.write(described)
        OpenApiChecks.check(written)
        @Suppress("UNCHECKED_CAST")
        val schemas = (written["components"] as Map<String, Any?>)["schemas"] as Map<String, Any?>
        schemas["wow.lifecycle.ApplyTags"].assert().isEqualTo(body)
        val applyTags = schemas.keys.filter { it.startsWith("wow.lifecycle.ApplyTags") }
        applyTags.assert().containsExactly("wow.lifecycle.ApplyTags")
        val delete = schemas["wow.lifecycle.DeleteAggregate"]
        delete.assert().isEqualTo(WowSchemas.LIFECYCLE["wow.lifecycle.DeleteAggregate"])
        @Suppress("UNCHECKED_CAST")
        val put = ((written["paths"] as Map<String, Any?>)["/shop/cart/{id}/put-item"] as Map<String, Any?>)["post"]
            as Map<String, Any?>
        put["description"].toString().assert().startsWith("Puts an item into the cart. Sends `shop.cart.PutItem`.")
    }

    @Test
    fun `a context with jobs should have the job routes, and the job codes among the errors`() {
        val document = ManifestOpenApi.write(
            MANIFEST.copyWith(
                contexts = MANIFEST.contexts.map {
                    it.copyWith(
                        subscriptions = listOf(
                            SubscriptionManifest(
                                name = "Jobs.report",
                                kind = SubscriptionKind.SAGA,
                                source = SubscriptionSource("shop", listOf("schedule")),
                                function = FunctionManifest(FunctionKind.SAGA, "shop", "Jobs", "report"),
                                job = JobManifest("report", "0 0 9 * * *", scope = "TENANT"),
                            ),
                        ),
                    )
                },
            ),
        )
        OpenApiChecks.check(document)
        @Suppress("UNCHECKED_CAST")
        val paths = document["paths"] as Map<String, Map<String, Map<String, Any?>>>
        val enable = paths.getValue("/shop/schedule/{id}/wow.schedule.enable").getValue("post")
        enable["operationId"].assert().isEqualTo("shop.schedule.wow.schedule.Enable")
        schemaOf(enable["requestBody"]).assert().isEqualTo("#/components/schemas/wow.schedule.Enable")
        paths.keys.assert().contains(
            "/shop/schedule/{id}/wow.schedule.reschedule",
            "/shop/schedule/{id}/wow.schedule.disable"
        )
        @Suppress("UNCHECKED_CAST")
        (document["tags"] as List<Map<String, Any?>>).map { it["name"] }.assert().contains("shop.schedule")
        OpenApiChecks.validate(document, "wow.schedule.Enable", """{"job":"report","rule":{"misfire":"CATCH_UP"}}""")
            .assert().isEmpty()
        val invalid = response("wow.command.400")["x-wow-error-codes"] as List<*>
        invalid.assert().contains(FrameworkErrors.SCHEDULE_JOB_INVALID)
        paths().keys.assert().noneMatch { it.contains("/schedule/") }
    }

    @Test
    fun `a context that keeps snapshots should have the snapshot rebuild route`() {
        val rebuild = operation("post", "/shop/snapshot/{id}/wow.snapshot.rebuild")
        rebuild["operationId"].assert().isEqualTo("shop.snapshot.wow.snapshot.Rebuild")
        schemaOf(rebuild["requestBody"]).assert().isEqualTo("#/components/schemas/wow.snapshot.Rebuild")
        OpenApiChecks.validate(document, "wow.snapshot.Rebuild", """{"ids":["c-1"]}""").assert().isEmpty()
        OpenApiChecks.validate(document, "wow.snapshot.Rebuild", """{"ids":[]}""").assert().isNotEmpty()
        val none = ManifestOpenApi.write(
            MANIFEST.copyWith(
                contexts = MANIFEST.contexts.map { context ->
                    context.copyWith(
                        aggregates = context.aggregates.map { it.copyWith(snapshot = SnapshotManifest(false)) }
                    )
                },
            ),
        )
        @Suppress("UNCHECKED_CAST")
        (none["paths"] as Map<String, Any?>).keys.assert().noneMatch { it.contains("/snapshot/{id}/") }
    }

    @Test
    fun `state reads and traces should carry the aggregate's state schema`() {
        val state = operation("get", "/shop/cart/{id}/state")
        state["operationId"].assert().isEqualTo("shop.cart.state")
        parameters(state).assert().containsExactly("id", "version", "at", "Wow-Scope", "If-None-Match")
        @Suppress("UNCHECKED_CAST")
        val ok = responses(state)["200"] as Map<String, Any?>
        schema(ok).assert().isEqualTo(
            mapOf(
                "\$ref" to "#/components/schemas/wow.StateView",
                "properties" to mapOf("state" to ref("schemas", "sample.Cart_State")),
            ),
        )
        ok["headers"].assert().isEqualTo(mapOf("ETag" to ref("headers", "ETag")))
        responses(state).keys.assert().containsExactly("200", "304", "400", "401", "403", "404", "406", "503")
        val trace = operation("get", "/shop/cart/{id}/state/trace")
        parameters(trace).assert().containsExactly("id", "from", "to", "limit", "Wow-Scope")
    }

    @Test
    fun `query routes should be documented for the models the edge serves`() {
        val snapshot = operation("post", "/shop/cart/snapshot/query")
        snapshot["operationId"].assert().isEqualTo("shop.cart.snapshot.query")
        snapshot["description"].toString().assert().contains("`aggregate`")
        @Suppress("UNCHECKED_CAST")
        val kinds = ((schema(responses(snapshot)["200"]) as Map<String, Any?>)["anyOf"] as List<Any?>)
        kinds.assert().hasSize(6)
        kinds[3].assert().isEqualTo(
            mapOf(
                "\$ref" to "#/components/schemas/wow.SnapshotRecord",
                "properties" to mapOf("state" to ref("schemas", "sample.Cart_State")),
            ),
        )
        operation("get", "/shop/cart/snapshot/query")["operationId"].assert().isEqualTo("shop.cart.snapshot.describe")
        val events = operation("post", "/shop/order/events/query")
        events["description"].toString().assert().contains("this model does not aggregate")
        @Suppress("UNCHECKED_CAST")
        ((schema(responses(events)["200"]) as Map<String, Any?>)["anyOf"] as List<Any?>).assert().hasSize(5)
        paths().containsKey("/shop/cart/custom/query").assert().isFalse()
        responses(events).keys.assert().containsExactly("200", "400", "401", "403", "404", "406", "415", "500", "503")
    }

    @Test
    fun `the framework's endpoints should follow the options`() {
        paths().keys.assert().contains(
            "/wow/manifest",
            "/wow/openapi",
            "/wow/id/global",
            "/wow/dataset",
            "/wow/dataset/{context}/{dataset}/query",
        )
        responses(operation("get", "/wow/id/global")).keys.assert().containsExactly("200", "401")
        responses(operation("post", "/wow/dataset/{context}/{dataset}/query"))["404"]
            .assert().isEqualTo(ref("responses", "wow.dataset-query.404"))
        val none = ManifestOpenApi.write(
            MANIFEST,
            OpenApiOptions(
                datasets = false,
                manifestEndpoint = false,
                openApiEndpoint = false,
                servers = listOf("https://shop.example"),
            ),
        )
        @Suppress("UNCHECKED_CAST")
        (none["paths"] as Map<String, Any?>).keys.filter {
            it.startsWith(
                "/wow"
            )
        }.assert().containsExactly("/wow/id/global")
        none["servers"].assert().isEqualTo(listOf(mapOf("url" to "https://shop.example")))
    }

    @Test
    fun `the security schemes should be the document's components and its global security`() {
        document.containsKey("security").assert().isFalse()
        @Suppress("UNCHECKED_CAST")
        (document["components"] as Map<String, Any?>).containsKey("securitySchemes").assert().isFalse()
        val secured = ManifestOpenApi.write(
            MANIFEST,
            OpenApiOptions().withSecuritySchemes(
                linkedMapOf(
                    "bearer" to OpenApiSecurityScheme.bearer("JWT", "CoSec's access token."),
                    "user" to OpenApiSecurityScheme.header("X-User"),
                ),
            ),
        )
        OpenApiChecks.check(secured)
        secured["security"].assert().isEqualTo(
            listOf(mapOf("bearer" to emptyList<String>()), mapOf("user" to emptyList()))
        )
        @Suppress("UNCHECKED_CAST")
        (secured["components"] as Map<String, Any?>)["securitySchemes"].assert().isEqualTo(
            mapOf(
                "bearer" to mapOf(
                    "type" to "http",
                    "description" to "CoSec's access token.",
                    "scheme" to "bearer",
                    "bearerFormat" to "JWT",
                ),
                "user" to mapOf("type" to "apiKey", "name" to "X-User", "in" to "header"),
            ),
        )
    }

    @Test
    fun `a header security scheme should name its header`() {
        assertThrows<IllegalArgumentException> { OpenApiSecurityScheme.header(" ") }
    }

    @Test
    fun `the host's error statuses should win over the tables`() {
        val document = ManifestOpenApi.write(
            MANIFEST,
            OpenApiOptions(
                errorStatuses = mapOf(FrameworkErrors.AGGREGATE_VERSION_MISMATCH to 409, "shop.cart.full" to 400),
            ),
        )

        @Suppress("UNCHECKED_CAST")
        val responses = (document["components"] as Map<String, Any?>)["responses"] as Map<String, Map<String, Any?>>
        responses.keys.assert().doesNotContain("shop.command.412")
        (responses.getValue("shop.command.400")["x-wow-error-codes"] as List<*>).assert().contains("shop.cart.full")
        OpenApiChecks.check(document)
    }

    @Test
    fun `the Manifest's schemas should become components, named as OpenAPI allows`() {
        val schemas = components("schemas")
        schemas.keys.assert().contains("sample.Cart_State", "sample.Cart_State_2", "wow.CommandResult_2", "sample.Item")
        schemas["sample.Cart_State"].assert().isEqualTo(
            mapOf("type" to "object", "properties" to mapOf("item" to ref("schemas", "sample.Item"))),
        )
        schemas["wow.CommandResult_2"].assert().isEqualTo(mapOf("type" to "string", "minLength" to 1))
        val unresolved = SchemaComponents(emptyMap(), emptySet())
        unresolved.ref(Manifest.schemaRef("a.B\$C")).assert().isEqualTo(ref("schemas", "a.B_C"))
        // Only a reference into $defs moves: not another document's, nor a property named $ref.
        val kept = mapOf(
            "\$ref" to "https://example.com/schema",
            "properties" to mapOf("\$ref" to mapOf("type" to "string")),
        )
        SchemaComponents(mapOf("a.B" to kept), emptySet()).schemas.assert().isEqualTo(mapOf("a.B" to kept))
    }

    @Test
    fun `a response registered twice with another content should fail`() {
        val responses = ErrorResponses(MANIFEST, emptyMap())
        responses.register("x", mapOf("description" to "one"))
        responses.register("x", mapOf("description" to "one")).assert().isEqualTo(ref("responses", "x"))
        assertThrows<IllegalStateException> { responses.register("x", mapOf("description" to "two")) }
        assertThrows<IllegalStateException> { responses.of("command", emptyList(), domain = true) }
    }

    @Test
    fun `the checks should catch a schema that is not JSON Schema and a dangling reference`() {
        val broken = ManifestOpenApi.write(
            MANIFEST.copyWith(
                defs = MANIFEST.defs + ("sample.Item" to mapOf("type" to 5, "items" to mapOf("\$ref" to "#/\$defs/x"))),
            ),
        )
        OpenApiChecks.problems(broken).assert().anyMatch { it.startsWith("schema sample.Item:") }
            .anyMatch { it == "\$ref #/components/schemas/x resolves to nothing" }
    }

    @Test
    fun `the framework schemas should hold what the edge writes`() {
        OpenApiChecks.validate(document, WowSchemas.PROBLEM, PROBLEM).assert().isEmpty()
        OpenApiChecks.validate(document, WowSchemas.STATE_VIEW, STATE).assert().isEmpty()
        OpenApiChecks.validate(document, WowSchemas.STATE_TRACE, TRACE).assert().isEmpty()
        OpenApiChecks.validate(document, "wow.lifecycle.TransferOwner", "{}").assert().isNotEmpty()
    }

    private fun info(document: Map<String, Any?>): Map<String, Any?> =
        (document["info"] as Map<*, *>).filterKeys { it != "description" }.mapKeys { it.key.toString() }

    @Suppress("UNCHECKED_CAST")
    private fun paths(): Map<String, Map<String, Any?>> = document["paths"] as Map<String, Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun operation(method: String, path: String): Map<String, Any?> =
        checkNotNull(paths()[path]?.get(method)) { "No $method $path in ${paths().keys}" } as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun components(kind: String): Map<String, Any?> =
        (document["components"] as Map<String, Any?>)[kind] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun response(name: String): Map<String, Any?> = components("responses")[name] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun responses(operation: Map<String, Any?>): Map<String, Any?> = operation["responses"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun content(response: Map<String, Any?>): Map<String, Any?> = response["content"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun schema(response: Any?): Any? =
        (content(response as Map<String, Any?>)["application/json"] as Map<String, Any?>)["schema"]

    @Suppress("UNCHECKED_CAST")
    private fun schemaOf(body: Any?): Any? = (schema(body) as Map<String, Any?>)["\$ref"]

    private fun parameters(operation: Map<String, Any?>): List<String> =
        (operation["parameters"] as List<*>).map { (it as Map<*, *>)["\$ref"].toString().substringAfterLast('/') }

    private fun ref(kind: String, name: String): Map<String, Any?> = mapOf("\$ref" to "#/components/$kind/$name")

    companion object {
        private fun command(name: String, creation: Creation, mounted: Boolean = false) = CommandManifest(
            name = name,
            schema = Manifest.schemaRef("sample.$name"),
            creation = creation,
            routes = CommandRoutes.of("shop", "cart", name, creation, mounted),
            mounted = mounted,
        )

        val MANIFEST = Manifest(
            service = ServiceInfo("shop-service", "1.2.0"),
            contexts = listOf(
                ContextManifest(
                    name = "shop",
                    description = "The shop",
                    aggregates = listOf(
                        AggregateManifest(
                            name = "cart",
                            state = StateManifest(Manifest.schemaRef("sample.Cart\$State")),
                            commands = listOf(
                                command("CreateCart", Creation.REQUIRED).copyWith(produces = listOf("CartCreated")),
                                command("PutItem", Creation.NONE).copyWith(aliases = listOf("AddItem")),
                                command("Open", Creation.ALLOWED).copyWith(produces = emptyList()),
                                command("Import", Creation.NONE, mounted = true)
                                    .copyWith(transformsTo = listOf("shop.cart.PutItem")),
                            ),
                            lifecycle = listOf(
                                "wow.lifecycle.DeleteAggregate",
                                "wow.lifecycle.ApplyTags",
                                "wow.lifecycle.Archive",
                            ),
                            queries = QueryRoutes.queries("shop", "cart", snapshots = true) +
                                QueryManifest("custom", QueryRoutes.of("shop", "cart", "custom")),
                        ),
                        AggregateManifest(
                            name = "order",
                            description = "An order",
                            state = StateManifest(Manifest.schemaRef("sample.Item")),
                            queries = QueryRoutes.queries("shop", "order", snapshots = false),
                        ),
                    ),
                    errors = listOf(
                        ErrorManifest("shop.cart.full", 409, "The cart is full."),
                        ErrorManifest("shop.cart.closed", 422),
                        ErrorManifest(FrameworkErrors.AGGREGATE_EXISTS, 409, "Not the context's."),
                    ),
                ),
            ),
            errors = FrameworkErrors.ALL,
            defs = mapOf(
                "sample.Cart\$State" to mapOf(
                    "type" to "object",
                    "properties" to mapOf("item" to mapOf("\$ref" to Manifest.schemaRef("sample.Item"))),
                ),
                "sample.Cart_State" to mapOf("type" to "object"),
                "sample.Item" to mapOf("type" to "object"),
                "wow.CommandResult" to mapOf("type" to "string", "minLength" to 1),
                "sample.CreateCart" to mapOf("type" to "object"),
                "sample.PutItem" to mapOf("type" to "object"),
                "sample.Open" to mapOf("type" to "object"),
                "sample.Import" to mapOf("type" to "object"),
            ),
        )

        private const val PROBLEM = """{"type":"urn:wow:error:wow.command.invalid","title":"Invalid","status":400,
            "detail":"d","code":"wow.command.invalid","violations":[{"path":"p","code":"c","message":"m"}]}"""
        private const val REF = """{"type":{"context":"c","name":"a"},"tenantId":"t","id":"i","isAssigned":true}"""
        private const val STATE = """{"ref":$REF,"version":1,"scope":{"ownerId":"o","spaceId":"s"},"deleted":false,
            "tags":{"k":["v"]},"state":{}}"""
        private const val TRACE = """{"ref":$REF,"commits":[{"version":1,"commitTime":2,"commandId":"c",
            "requestId":"r","commandType":"t","events":[{"name":"e","revision":1,"body":{}}],
            "scope":{"ownerId":null,"spaceId":null},"deleted":false,"tags":{},"state":{},
            "operator":{"id":"o","tenantId":"t","crossScope":false}}]}"""
    }
}
