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

package me.ahoo.wow.manifest

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.module.kotlin.jacksonObjectMapper

class ManifestTreeTest {
    private val mapper = jacksonObjectMapper()

    private val manifest = Manifests.fragment().copy(
        service = ServiceInfo("shop-service", "1.0.0"),
        errors = FrameworkErrors.ALL,
    )

    @Test
    fun `a manifest should come back the same through its JSON text`() {
        val json = mapper.writeValueAsString(ManifestTree.write(manifest))
        ManifestTree.read(mapper.readValue(json, Map::class.java)).assert().isEqualTo(manifest)
    }

    @Test
    fun `a mounted command should come back through its JSON text, and a decided one leave mounted out`() {
        val mounted = CommandManifest(
            name = "ImportCustomer",
            schema = Manifest.schemaRef("sample.ImportCustomer"),
            routes = CommandRoutes.of("shop", "customer", "ImportCustomer", Creation.NONE, mounted = true),
            mounted = true,
            transformsTo = listOf("shop.customer.Register"),
        )
        val tree = ManifestTree.write(
            Manifest(
                contexts = listOf(
                    ContextManifest(
                        "shop",
                        aggregates = listOf(
                            AggregateManifest(
                                "customer",
                                state = StateManifest(Manifest.schemaRef("sample.Customer")),
                                commands = listOf(mounted, CommandManifest("Register", schema = "#/\$defs/R")),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val json = mapper.writeValueAsString(tree)
        json.assert().contains("\"mounted\":true").contains("\"transformsTo\":[\"shop.customer.Register\"]")
        val commands = ManifestTree.read(mapper.readValue(json, Map::class.java)).contexts.single().aggregates.single()
            .commands
        commands.assert().containsExactly(mounted, CommandManifest("Register", schema = "#/\$defs/R"))
        json.indexOf("\"mounted\"").assert().isEqualTo(json.lastIndexOf("\"mounted\""))
    }

    @Test
    fun `the tree should name the fields as the design does and leave out absent values`() {
        val fragment = Manifests.fragment().copy(
            contexts = Manifests.fragment().contexts.map { it.copy(description = null) },
        )
        val tree = ManifestTree.write(fragment)
        tree.keys.assert().containsExactly("format", "contexts", "errors", "\$defs")
        tree["errors"].assert().isEqualTo(emptyList<Any>())
        val context = (tree["contexts"] as List<*>).single() as Map<*, *>
        context.keys.assert().containsExactly("name", "aggregates", "subscriptions", "published", "errors")
        context["errors"].assert().isEqualTo(
            listOf(mapOf("code" to "cart.empty", "status" to 422, "description" to "The cart has no items")),
        )
        val aggregate = (context["aggregates"] as List<*>).single() as Map<*, *>
        aggregate.keys.assert().containsExactly(
            "name",
            "description",
            "state",
            "snapshot",
            "commands",
            "events",
            "lifecycle",
            "queries",
        )
        aggregate["lifecycle"].assert().isEqualTo(Manifests.LIFECYCLE)
        aggregate["queries"].assert().isEqualTo(
            listOf(
                mapOf(
                    "model" to "snapshot",
                    "routes" to listOf(
                        mapOf("method" to "POST", "path" to "/shop/cart/snapshot/query"),
                        mapOf("method" to "GET", "path" to "/shop/cart/snapshot/query"),
                    ),
                ),
                mapOf(
                    "model" to "events",
                    "routes" to listOf(
                        mapOf("method" to "POST", "path" to "/shop/cart/events/query"),
                        mapOf("method" to "GET", "path" to "/shop/cart/events/query"),
                    ),
                ),
            ),
        )
        aggregate["state"].assert().isEqualTo(mapOf("schema" to "#/\$defs/sample.CartState", "fingerprint" to "abc123"))
        val commands = aggregate["commands"] as List<*>
        (commands[0] as Map<*, *>)["produces"].assert().isEqualTo(listOf("CartCreated"))
        (commands[0] as Map<*, *>)["routes"].assert().isEqualTo(
            listOf(
                mapOf("method" to "POST", "path" to "/shop/cart/{id}/create-cart"),
                mapOf("method" to "POST", "path" to "/shop/cart/create-cart"),
            ),
        )
        (commands[1] as Map<*, *>).containsKey("produces").assert().isFalse()
        (commands[1] as Map<*, *>)["creation"].assert().isEqualTo("NONE")
    }

    @Test
    fun `the tree should name the published streams and the subscriptions' M6 fields`() {
        val context = (ManifestTree.write(Manifests.fragment())["contexts"] as List<*>).single() as Map<*, *>
        context["published"].assert().isEqualTo(
            listOf(mapOf("aggregate" to "cart", "topic" to "wow.shop.cart.state", "includeState" to true)),
        )
        val subscriptions = (context["subscriptions"] as List<*>).map { it as Map<*, *> }
        subscriptions[0].keys.assert().containsExactly("name", "kind", "source", "function", "start")
        subscriptions[0]["start"].assert().isEqualTo("BEGINNING")
        subscriptions[1]["derives"].assert().isEqualTo(listOf("shop.cart.PutItem", "#/\$defs/sales.Refund"))
        subscriptions[1]["source"].assert().isEqualTo(
            mapOf(
                "context" to "sales",
                "aggregates" to listOf("order"),
                "events" to emptyList<String>(),
                "nullableState" to true,
            ),
        )
        // A state that is not nullable is left out.
        (subscriptions[0]["source"] as Map<*, *>).keys.assert().containsExactly("context", "aggregates", "events")
        subscriptions[2].keys.assert().containsExactly("name", "kind", "source", "function", "derives", "rejected")
        subscriptions[2]["rejected"].assert().isEqualTo(listOf("#/\$defs/sales.Refund"))
    }

    @Test
    fun `the M6 fields should default to nothing`() {
        PublishedManifest("cart", "t").includeState.assert().isTrue()
        QueryManifest("events").routes.assert().isEmpty()
        SubscriptionManifest(
            "CartSummary.onEvent",
            SubscriptionKind.PROJECTION,
            SubscriptionSource(),
            FunctionManifest(FunctionKind.PROJECT, "shop", "CartSummary", "onEvent"),
        ).let {
            it.derives.assert().isNull()
            it.rejected.assert().isNull()
            it.start.assert().isNull()
        }
    }

    @Test
    fun `a tenant-owned aggregate and a retry budget should come back, and the defaults be left out`() {
        val aggregate = AggregateManifest(
            "product",
            state = StateManifest(Manifest.schemaRef("sample.Product")),
            ownership = Ownership.TENANT,
        )
        val retry = RetryManifest(maxAttempts = 3, timeout = "PT2S")
        val subscription = SubscriptionManifest(
            "Notifier.onEvent",
            SubscriptionKind.REACTOR,
            SubscriptionSource(),
            FunctionManifest(FunctionKind.REACT, "shop", "Notifier", "onEvent"),
            retry = retry,
        )
        val manifest = Manifest(
            contexts = listOf(
                ContextManifest(
                    "shop",
                    aggregates = listOf(aggregate, aggregate.copy(name = "cart", ownership = Ownership.OWNER)),
                    subscriptions = listOf(subscription, subscription.copy(name = "Notifier.other", retry = null)),
                ),
            ),
        )
        val tree = ManifestTree.write(manifest)
        val context = (tree["contexts"] as List<*>).single() as Map<*, *>
        val aggregates = (context["aggregates"] as List<*>).map { it as Map<*, *> }
        aggregates[0]["ownership"].assert().isEqualTo("TENANT")
        aggregates[1].containsKey("ownership").assert().isFalse()
        val subscriptions = (context["subscriptions"] as List<*>).map { it as Map<*, *> }
        subscriptions[0]["retry"].assert().isEqualTo(mapOf("maxAttempts" to 3, "timeout" to "PT2S"))
        subscriptions[1].containsKey("retry").assert().isFalse()
        val json = mapper.writeValueAsString(tree)
        ManifestTree.read(mapper.readValue(json, Map::class.java)).assert().isEqualTo(manifest)
    }

    @Test
    fun `a job should come back, its defaults written too, and an absent one left out`() {
        val job = JobManifest("nightly", "0 0 3 * * *", zone = "Asia/Shanghai", scope = "TENANT", sharded = false)
        val subscription = SubscriptionManifest(
            "Billing.nightly",
            SubscriptionKind.SAGA,
            SubscriptionSource("shop", listOf("schedule")),
            FunctionManifest(FunctionKind.SAGA, "shop", "Billing", "nightly"),
            job = job,
        )
        val manifest = Manifest(
            contexts = listOf(
                ContextManifest(
                    "shop",
                    subscriptions = listOf(subscription, subscription.copy(name = "Billing.other", job = null)),
                ),
            ),
        )
        val tree = ManifestTree.write(manifest)
        val context = (tree["contexts"] as List<*>).single() as Map<*, *>
        val subscriptions = (context["subscriptions"] as List<*>).map { it as Map<*, *> }
        subscriptions[0]["job"].assert().isEqualTo(
            mapOf(
                "name" to "nightly",
                "cron" to "0 0 3 * * *",
                "zone" to "Asia/Shanghai",
                "scope" to "TENANT",
                "sharded" to false,
                "misfire" to "COALESCE",
            ),
        )
        subscriptions[1].containsKey("job").assert().isFalse()
        val json = mapper.writeValueAsString(tree)
        ManifestTree.read(mapper.readValue(json, Map::class.java)).assert().isEqualTo(manifest)
        // A job written with its name and expression only reads with the defaults.
        val minimal = mapOf(
            "name" to "Billing.nightly",
            "kind" to "SAGA",
            "source" to emptyMap<String, Any>(),
            "function" to mapOf("kind" to "SAGA", "context" to "shop", "processor" to "Billing", "name" to "nightly"),
            "job" to mapOf("name" to "nightly", "cron" to "0 0 3 * * *"),
        )
        val read = ManifestTree.read(
            mapOf(
                "format" to Manifest.FORMAT,
                "contexts" to listOf(mapOf("name" to "shop", "subscriptions" to listOf(minimal)))
            ),
        )
        read.contexts.single().subscriptions.single().job.assert().isEqualTo(JobManifest("nightly", "0 0 3 * * *"))
    }

    @Test
    fun `an error without a description should leave it out`() {
        val tree = ManifestTree.write(Manifest(errors = listOf(ErrorManifest("wow.x", 400))))
        tree["errors"].assert().isEqualTo(listOf(mapOf("code" to "wow.x", "status" to 400)))
        ManifestTree.read(tree).errors.single().description.assert().isNull()
    }

    @Test
    fun `reading should refuse another format`() {
        assertThrows<IllegalArgumentException> {
            ManifestTree.read(ManifestTree.write(manifest) + ("format" to 2))
        }.message.assert().contains("format 2")
    }

    @Test
    fun `reading should say which field is missing or mistyped`() {
        val tree = ManifestTree.write(manifest)
        val noName = tree + ("contexts" to listOf(mapOf("aggregates" to emptyList<Any>())))
        assertThrows<IllegalArgumentException> { ManifestTree.read(noName) }
            .message.assert().isEqualTo("manifest.contexts[0].name is missing.")
        val badAliases = tree + ("contexts" to listOf(mapOf("name" to "shop", "aggregates" to "cart")))
        assertThrows<IllegalArgumentException> { ManifestTree.read(badAliases) }
            .message.assert().contains("manifest.contexts[0].aggregates is not an array")
    }

    @Test
    fun `reading should refuse a context whose name breaks the rule`() {
        listOf("eshop.catalog", "Shop", "my_shop", "1shop", "-shop", "").forEach { name ->
            val tree = ManifestTree.write(manifest) + ("contexts" to listOf(mapOf("name" to name)))
            assertThrows<IllegalArgumentException> { ManifestTree.read(tree) }
                .message.assert().startsWith("manifest.contexts[0].name: context name \"$name\" is not valid")
        }
    }

    @Test
    fun `a minimal manifest should read with every default`() {
        val tree = mapOf(
            "format" to 1,
            "contexts" to listOf(
                mapOf(
                    "name" to "shop",
                    "aggregates" to listOf(mapOf("name" to "cart", "state" to mapOf("schema" to "#/\$defs/s"))),
                ),
            ),
        )
        val expected = Manifest(
            contexts = listOf(
                ContextManifest(
                    name = "shop",
                    aggregates = listOf(AggregateManifest(name = "cart", state = StateManifest("#/\$defs/s")))
                ),
            ),
        )
        ManifestTree.read(tree).assert().isEqualTo(expected)
        expected.service.assert().isNull()
        expected.errors.assert().isEmpty()
        expected.contexts.single().let { context ->
            context.description.assert().isNull()
            context.subscriptions.assert().isEmpty()
            context.published.assert().isEmpty()
            context.errors.assert().isEmpty()
            context.aggregates.single().let { aggregate ->
                aggregate.description.assert().isNull()
                aggregate.snapshot.enabled.assert().isTrue()
                aggregate.commands.assert().isEmpty()
                aggregate.events.assert().isEmpty()
                aggregate.lifecycle.assert().isEmpty()
                aggregate.queries.assert().isEmpty()
                aggregate.state.fingerprint.assert().isNull()
            }
        }
        Manifest().defs.assert().isEmpty()
        ServiceInfo("shop").version.assert().isNull()
        CommandManifest("Add", schema = "#/\$defs/a").let {
            it.aliases.assert().isEmpty()
            it.creation.assert().isEqualTo(Creation.NONE)
            it.produces.assert().isNull()
            it.routes.assert().isEmpty()
        }
        EventManifest("Added", schema = "#/\$defs/a").revision.assert().isEqualTo(1)
        SubscriptionSource().let {
            it.context.assert().isNull()
            it.aggregates.assert().isEmpty()
            it.events.assert().isEmpty()
            it.nullableState.assert().isFalse()
        }
    }

    @Test
    fun `reading should refuse an array element of the wrong type`() {
        val tree = ManifestTree.write(manifest)
        val context = (tree["contexts"] as List<*>).single() as Map<*, *>
        val aggregate = (context["aggregates"] as List<*>).single() as Map<*, *>
        val command = (aggregate["commands"] as List<*>).first() as Map<*, *>
        val badAlias = tree + (
            "contexts" to listOf(
                context + ("aggregates" to listOf(aggregate + ("commands" to listOf(command + ("aliases" to listOf(1)))))),
            )
            )
        assertThrows<IllegalArgumentException> { ManifestTree.read(badAlias) }
            .message.assert().isEqualTo("manifest.contexts[0].aggregates[0].commands[0].aliases[0] is not a string: 1")
        val badRoute = tree + (
            "contexts" to listOf(
                context + ("aggregates" to listOf(aggregate + ("commands" to listOf(command + ("routes" to listOf(mapOf("method" to "POST"))))))),
            )
            )
        assertThrows<IllegalArgumentException> { ManifestTree.read(badRoute) }
            .message.assert().isEqualTo("manifest.contexts[0].aggregates[0].commands[0].routes[0].path is missing.")
        val badError = tree + ("errors" to listOf(mapOf("code" to "wow.x", "status" to "400")))
        assertThrows<IllegalArgumentException> { ManifestTree.read(badError) }
            .message.assert().isEqualTo("manifest.errors[0].status is not a number: 400")
        val badContext = tree + ("contexts" to listOf("shop"))
        assertThrows<IllegalArgumentException> { ManifestTree.read(badContext) }
            .message.assert().isEqualTo("manifest.contexts[0] is not an object: shop")
    }

    @Test
    fun `a schema reference should point into the defs`() {
        Manifest.schemaRef("a.B\$C").assert().isEqualTo("#/\$defs/a.B\$C")
        Manifest.schemaKey("#/\$defs/a.B\$C").assert().isEqualTo("a.B\$C")
        assertThrows<IllegalArgumentException> { Manifest.schemaKey("a.B") }
    }

    @Test
    fun `unresolved references should be those without a schema`() {
        val fragment = Manifests.fragment()
        // A command a subscription derives or handles the rejection of, which the module does not decide, has its
        // schema in $defs too.
        fragment.schemaRefs.assert().hasSize(7)
        fragment.unresolvedSchemaRefs.assert().containsExactly(
            "#/\$defs/sample.CreateCart",
            "#/\$defs/sample.AddItem",
            "#/\$defs/sample.CartCreated",
            "#/\$defs/sample.ItemAdded",
            "#/\$defs/sales.Refund",
        )
    }
}
