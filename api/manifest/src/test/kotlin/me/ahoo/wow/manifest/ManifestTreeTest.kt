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

    private val manifest = Manifests.fragment().copyWith(
        service = ServiceInfo("shop-service", "1.0.0"),
        errors = FrameworkErrors.ALL,
    )

    @Test
    fun `a manifest should come back the same through its JSON text`() {
        val json = mapper.writeValueAsString(ManifestTree.write(manifest))
        ManifestTree.read(mapper.readValue(json, Map::class.java)).assert().isEqualTo(manifest)
    }

    @Test
    fun `command and event descriptions should come back through the JSON text, and absent ones be left out`() {
        val aggregate = AggregateManifest(
            "cart",
            state = StateManifest(Manifest.schemaRef("sample.Cart")),
            commands = listOf(
                CommandManifest("AddItem", schema = Manifest.schemaRef("sample.AddItem"), description = "Adds."),
                CommandManifest("Clear", schema = Manifest.schemaRef("sample.Clear")),
            ),
            events = listOf(
                EventManifest("ItemAdded", schema = Manifest.schemaRef("sample.ItemAdded"), description = "Added."),
                EventManifest("Cleared", schema = Manifest.schemaRef("sample.Cleared")),
            ),
        )
        val fragment = Manifest(contexts = listOf(ContextManifest("shop", aggregates = listOf(aggregate))))
        val json = mapper.writeValueAsString(ManifestTree.write(fragment))
        json.assert().contains("\"description\":\"Adds.\"").contains("\"description\":\"Added.\"")
        ManifestTree.read(mapper.readValue(json, Map::class.java)).assert().isEqualTo(fragment)
        val tree = ManifestTree.write(fragment)
        val written = ((tree["contexts"] as List<*>).single() as Map<*, *>)["aggregates"] as List<*>
        val commands = (written.single() as Map<*, *>)["commands"] as List<*>
        val events = (written.single() as Map<*, *>)["events"] as List<*>
        (commands[1] as Map<*, *>).containsKey("description").assert().isFalse()
        (events[1] as Map<*, *>).containsKey("description").assert().isFalse()
    }

    @Test
    fun `a mounted command should come back through its JSON text, and a decided one leave mounted out`() {
        val mounted = CommandManifest(
            name = "ImportCustomer",
            schema = Manifest.schemaRef("sample.ImportCustomer"),
            routes = CommandRoutes.of("shop", "customer", "ImportCustomer", Creation.NONE, unaddressed = true),
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
        val fragment = Manifests.fragment().copyWith(
            contexts = Manifests.fragment().contexts.map { it.copyWith(description = null) },
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
            "queries",
        )
        // A system command is a command, flagged: its route is listed like any command's.
        val delete = (aggregate["commands"] as List<*>).map { it as Map<*, *> }
            .single { it["name"] == SystemCommands.DELETE_AGGREGATE }
        delete["system"].assert().isEqualTo(true)
        delete["routes"].assert().isEqualTo(listOf(mapOf("method" to "DELETE", "path" to "/shop/cart/{id}")))
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
        (commands[1] as Map<*, *>)["creation"].assert().isEqualTo("none")
    }

    @Test
    fun `the tree should name the published streams and the subscriptions' M6 fields`() {
        val context = (ManifestTree.write(Manifests.fragment())["contexts"] as List<*>).single() as Map<*, *>
        context["published"].assert().isEqualTo(
            listOf(mapOf("aggregate" to "cart", "topic" to "wow.shop.cart.state", "includeState" to true)),
        )
        val subscriptions = (context["subscriptions"] as List<*>).map { it as Map<*, *> }
        subscriptions[0].keys.assert().containsExactly("name", "kind", "source", "function", "start")
        subscriptions[0]["start"].assert().isEqualTo("beginning")
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
            it.committed.assert().isNull()
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
        val retry = RetryManifest(
            maxAttempts = 3,
            timeout = "PT2S",
            unrecoverable = listOf("sample.InvalidAddress"),
            multiplier = 1.5,
        )
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
                    aggregates = listOf(aggregate, aggregate.copyWith(name = "cart", ownership = Ownership.OWNER)),
                    subscriptions = listOf(subscription, subscription.copyWith(name = "Notifier.other", retry = null)),
                ),
            ),
        )
        val tree = ManifestTree.write(manifest)
        val context = (tree["contexts"] as List<*>).single() as Map<*, *>
        val aggregates = (context["aggregates"] as List<*>).map { it as Map<*, *> }
        aggregates[0]["ownership"].assert().isEqualTo("tenant")
        aggregates[1].containsKey("ownership").assert().isFalse()
        val subscriptions = (context["subscriptions"] as List<*>).map { it as Map<*, *> }
        subscriptions[0]["retry"].assert()
            .isEqualTo(
                mapOf(
                    "maxAttempts" to 3,
                    "timeout" to "PT2S",
                    "unrecoverable" to listOf("sample.InvalidAddress"),
                    "multiplier" to 1.5,
                )
            )
        ManifestTree.read(mapper.readValue(mapper.writeValueAsString(tree), Map::class.java)).contexts.single()
            .subscriptions[0].retry!!.copyWith(recoverable = listOf("sample.Busy")).recoverable.assert()
            .containsExactly("sample.Busy")
        subscriptions[1].containsKey("retry").assert().isFalse()
        val json = mapper.writeValueAsString(tree)
        ManifestTree.read(mapper.readValue(json, Map::class.java)).assert().isEqualTo(manifest)
    }

    @Test
    fun `a raised state revision should come back, and revision 1 be left out`() {
        val aggregate = AggregateManifest(
            "cart",
            state = StateManifest(Manifest.schemaRef("sample.Cart")),
            snapshot = SnapshotManifest(stateRevision = 2),
        )
        val manifest = Manifest(
            contexts = listOf(
                ContextManifest(
                    "shop",
                    aggregates = listOf(aggregate, aggregate.copyWith(name = "order", snapshot = SnapshotManifest()))
                ),
            ),
        )
        val tree = ManifestTree.write(manifest)
        val aggregates = ((tree["contexts"] as List<*>).single() as Map<*, *>)["aggregates"] as List<*>
        (aggregates[0] as Map<*, *>)["snapshot"].assert().isEqualTo(mapOf("enabled" to true, "stateRevision" to 2))
        (aggregates[1] as Map<*, *>)["snapshot"].assert().isEqualTo(mapOf("enabled" to true))
        val json = mapper.writeValueAsString(tree)
        ManifestTree.read(mapper.readValue(json, Map::class.java)).assert().isEqualTo(manifest)
        assertThrows<IllegalArgumentException> { SnapshotManifest(stateRevision = 0) }
    }

    @Test
    fun `a job should come back, its defaults written too, and an absent one left out`() {
        val job = JobManifest("nightly", "0 0 3 * * *", zone = "Asia/Shanghai", scope = "tenant", sharded = false)
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
                    subscriptions = listOf(subscription, subscription.copyWith(name = "Billing.other", job = null)),
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
                "scope" to "tenant",
                "sharded" to false,
                "misfire" to "coalesce",
            ),
        )
        subscriptions[1].containsKey("job").assert().isFalse()
        val json = mapper.writeValueAsString(tree)
        ManifestTree.read(mapper.readValue(json, Map::class.java)).assert().isEqualTo(manifest)
        // A job written with its name and expression only reads with the defaults.
        val minimal = mapOf(
            "name" to "Billing.nightly",
            "kind" to "saga",
            "source" to emptyMap<String, Any>(),
            "function" to mapOf("kind" to "saga", "context" to "shop", "processor" to "Billing", "name" to "nightly"),
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
    fun `an @OnCommitted handler should come back with the commands it takes, which count as schema references`() {
        val committed = SubscriptionManifest(
            "CheckoutSaga.onRefundCommitted",
            SubscriptionKind.SAGA,
            SubscriptionSource(),
            FunctionManifest(FunctionKind.SAGA, "shop", "CheckoutSaga", "onRefundCommitted"),
            derives = emptyList(),
            committed = listOf(Manifest.schemaRef("sales.Refund")),
        )
        committed.isReceiptHandler.assert().isTrue()
        committed.copyWith(committed = null).isReceiptHandler.assert().isFalse()
        committed.copyWith(committed = null, rejected = emptyList()).isReceiptHandler.assert().isTrue()
        val manifest = Manifest(contexts = listOf(ContextManifest("shop", subscriptions = listOf(committed))))
        manifest.schemaRefs.assert().containsExactly("#/\$defs/sales.Refund")
        val tree = ManifestTree.write(manifest)
        val subscription = ((tree["contexts"] as List<*>).single() as Map<*, *>)["subscriptions"] as List<*>
        (subscription.single() as Map<*, *>)["committed"].assert().isEqualTo(listOf("#/\$defs/sales.Refund"))
        val json = mapper.writeValueAsString(tree)
        ManifestTree.read(mapper.readValue(json, Map::class.java)).assert().isEqualTo(manifest)
    }

    @Test
    fun `a saga's calls should come back, and count as schema references`() {
        val calling = SubscriptionManifest(
            "CheckoutSaga.onCheckedOut",
            SubscriptionKind.SAGA,
            SubscriptionSource("shop", listOf("cart")),
            FunctionManifest(FunctionKind.SAGA, "shop", "CheckoutSaga", "onCheckedOut"),
            derives = emptyList(),
            calls = listOf(Manifest.schemaRef("ordering.SubmitOrder")),
        )
        val manifest = Manifest(contexts = listOf(ContextManifest("shop", subscriptions = listOf(calling))))
        manifest.schemaRefs.assert().containsExactly("#/\$defs/ordering.SubmitOrder")
        val json = mapper.writeValueAsString(ManifestTree.write(manifest))
        ManifestTree.read(mapper.readValue(json, Map::class.java)).assert().isEqualTo(manifest)
        calling.toString().assert().contains("calls=[#/\$defs/ordering.SubmitOrder]")
        calling.assert().isNotEqualTo(calling.copyWith(calls = null))
        PeerContracts.contractOf(listOf(manifest)).contexts.single().subscriptions.single().calls.assert()
            .isEqualTo(calling.calls)
    }

    @Test
    fun `an error without a description should leave it out`() {
        val tree = ManifestTree.write(Manifest(errors = listOf(ErrorManifest("wow.x", 400))))
        tree["errors"].assert().isEqualTo(listOf(mapOf("code" to "wow.x", "status" to 400)))
        ManifestTree.read(tree).errors.single().description.assert().isNull()
    }

    /** [manifest]'s tree as JSON text, with each of [replacements] made once, read back. */
    @Suppress("UNCHECKED_CAST")
    private fun treeWith(vararg replacements: Pair<String, String>): Map<*, *> {
        var json = mapper.writeValueAsString(ManifestTree.write(manifest))
        replacements.forEach { (old, new) ->
            check(old in json) { "$old is not in the tree" }
            json = json.replaceFirst(old, new)
        }
        return mapper.readValue(json, Map::class.java)
    }

    @Test
    fun `a value of an enumeration a newer writer added leaves out what it belongs to, and is told`() {
        val warnings = mutableListOf<String>()
        val cart = manifest.contexts.single().aggregates.first()
        val read = ManifestTree.read(
            treeWith(
                "\"creation\":\"${ManifestTree.wire(cart.commands.first().creation)}\"" to "\"creation\":\"SOMETIMES\"",
                "\"kind\":\"${ManifestTree.wire(manifest.contexts.single().subscriptions.first().kind)}\"" to "\"kind\":\"WORKFLOW\"",
            ),
        ) { warnings += it }
        val context = read.contexts.single()
        // The command of an unknown creation is left out, as is the subscription of an unknown kind; the rest is read.
        context.aggregates.first().commands.map { it.name }.assert().isEqualTo(cart.commands.drop(1).map { it.name })
        context.subscriptions.map { it.name }.assert()
            .isEqualTo(manifest.contexts.single().subscriptions.drop(1).map { it.name })
        warnings.assert().anyMatch { it.contains("creation: SOMETIMES is unknown to this reader") }
        warnings.assert().anyMatch { it.contains("(${cart.commands.first().name}) is left out") }
        // An aggregate of an unknown ownership is left out too, and a subscription of an unknown start.
        val unknown = ManifestTree.write(manifest).let { tree ->
            val contexts = (tree["contexts"] as List<*>).map { context ->
                val map = context as Map<*, *>
                map + ("aggregates" to (map["aggregates"] as List<*>).map { (it as Map<*, *>) + ("ownership" to "CLAN") }) +
                    ("subscriptions" to (map["subscriptions"] as List<*>).map { (it as Map<*, *>) + ("start" to "NEVER") })
            }
            tree + ("contexts" to contexts)
        }
        val left = ManifestTree.read(unknown) { warnings += it }.contexts.single()
        left.aggregates.assert().isEmpty()
        left.subscriptions.assert().isEmpty()
        warnings.assert().anyMatch { it.contains("(${cart.name}) is left out") }
    }

    @Test
    fun `a marker of requires this reader does not understand refuses the Manifest, and none is left out`() {
        ManifestTree.write(manifest).containsKey("requires").assert().isFalse()
        assertThrows<IllegalArgumentException> {
            ManifestTree.read(ManifestTree.write(manifest) + ("requires" to listOf("routes-v2")))
        }.message.assert().contains("[routes-v2]").contains("does not understand")
        // What a reader understands is read and written back, and a merged Manifest requires what any fragment does.
        val requiring = manifest.copyWith(requires = listOf("routes-v2"))
        ManifestTree.write(requiring)["requires"].assert().isEqualTo(listOf("routes-v2"))
        ManifestMerger.merge(listOf(ManifestFragment("a", requiring), ManifestFragment("b", Manifest())))
            .requires.assert().containsExactly("routes-v2")
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
        fragment.schemaRefs.assert().hasSize(9)
        // A system command's body is a schema too, keyed by its system name.
        fragment.unresolvedSchemaRefs.assert().containsExactly(
            "#/\$defs/sample.CreateCart",
            "#/\$defs/sample.AddItem",
            "#/\$defs/wow.lifecycle.ApplyTags",
            "#/\$defs/wow.lifecycle.DeleteAggregate",
            "#/\$defs/sample.CartCreated",
            "#/\$defs/sample.ItemAdded",
            "#/\$defs/sales.Refund",
        )
    }
}
