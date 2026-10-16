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

class ManifestMergerTest {
    private val shared = mapOf("sample.Money" to mapOf("type" to "string"))

    @Test
    fun `fragments of different contexts should merge into one manifest`() {
        val shop = ManifestFragment("shop.json", Manifests.fragment("shop", defs = shared + ("a" to 1)))
        val billing = ManifestFragment("billing.json", Manifests.fragment("billing", defs = shared + ("b" to 2)))
        val service = ServiceInfo("store", "1.0.0")

        val merged = ManifestMerger.merge(listOf(shop, billing), service)

        merged.service.assert().isEqualTo(service)
        merged.contexts.map { it.name }.assert().containsExactly("billing", "shop")
        merged.defs.keys.assert().containsExactly("a", "b", "sample.Money")
        merged.errors.assert().isEqualTo(FrameworkErrors.ALL)
        merged.contexts.first().errors.map { it.code }.assert().containsExactly("cart.empty")
        merged.contexts.flatMap { context -> context.aggregates.flatMap { it.commands.flatMap { it.routes } } }
            .map { it.path }.assert().containsExactly(
                "/billing/cart/{id}/create-cart",
                "/billing/cart/create-cart",
                "/billing/cart/{id}/put-item",
                "/shop/cart/{id}/create-cart",
                "/shop/cart/create-cart",
                "/shop/cart/{id}/put-item",
            )
    }

    @Test
    fun `a merged manifest should merge again into itself`() {
        val merged = ManifestMerger.merge(listOf(ManifestFragment("shop.json", Manifests.fragment())))
        ManifestMerger.merge(listOf(ManifestFragment("manifest.json", merged))).assert().isEqualTo(merged)
    }

    @Test
    fun `a route declared by two commands should conflict`() {
        val cart = Manifests.cart()
        val addItem = CommandManifest(
            name = "Put_Item",
            schema = Manifest.schemaRef("sample.PutItem2"),
            routes = CommandRoutes.of("shop", "cart", "Put_Item", Creation.NONE),
        )
        val shop = ManifestFragment(
            "shop.json",
            Manifests.fragment(aggregates = listOf(cart.copyWith(commands = cart.commands + addItem))),
        )
        // A hand-written fragment of another context claiming a path of shop.
        val billing = Manifests.fragment("billing").let { fragment ->
            val context = fragment.contexts.single()
            val account = context.aggregates.single().let { aggregate ->
                aggregate.copyWith(
                    commands = aggregate.commands.map {
                        if (it.name == "CreateCart") it.copyWith(routes = cart.commands[0].routes.take(1)) else it
                    },
                )
            }
            fragment.copyWith(contexts = listOf(context.copyWith(aggregates = listOf(account))))
        }
        val conflicts = assertThrows<ManifestConflictException> {
            ManifestMerger.merge(listOf(shop, ManifestFragment("billing.json", billing)))
        }.conflicts
        conflicts.assert().containsExactly(
            "route POST /shop/cart/{id}/create-cart is declared by shop.cart.CreateCart (shop.json) and " +
                "billing.cart.CreateCart (billing.json)",
            "route POST /shop/cart/{id}/put-item is declared by shop.cart.PutItem (shop.json) and " +
                "shop.cart.Put_Item (shop.json)",
        )
    }

    @Test
    fun `reserved names should be refused, every one at once`() {
        val cart = Manifests.cart(context = "wow")
        val reserved = listOf("Query", "State", "wow.lifecycle.RestoreAggregate").map { name ->
            CommandManifest(
                name = name,
                schema = Manifest.schemaRef("sample.$name"),
                creation = Creation.ALLOWED,
                routes = CommandRoutes.of("wow", "cart", name, Creation.ALLOWED),
            )
        }
        val fragment = Manifests.fragment("wow", listOf(cart.copyWith(commands = cart.commands + reserved)))
        val conflicts = assertThrows<ManifestConflictException> {
            ManifestMerger.merge(listOf(ManifestFragment("a.json", fragment)))
        }.conflicts
        conflicts.assert().containsExactly(
            "context wow of a.json takes a name the framework reserves",
            "route POST /wow/cart/{id}/query of wow.cart.Query (a.json) ends in a segment the framework reserves",
            "route POST /wow/cart/query of wow.cart.Query (a.json) ends in a segment the framework reserves",
            "route POST /wow/cart/{id}/state of wow.cart.State (a.json) ends in a segment the framework reserves",
            "route POST /wow/cart/state of wow.cart.State (a.json) ends in a segment the framework reserves",
            "route POST /wow/cart/{id}/wow.lifecycle.restore-aggregate of wow.cart.wow.lifecycle.RestoreAggregate " +
                "(a.json) ends in a segment the framework reserves",
            "route POST /wow/cart/wow.lifecycle.restore-aggregate of wow.cart.wow.lifecycle.RestoreAggregate " +
                "(a.json) ends in a segment the framework reserves",
        )
    }

    @Test
    fun `a context's errors should be its own and declared once`() {
        val fragment = Manifests.fragment().let { fragment ->
            val context = fragment.contexts.single()
            val errors = context.errors + context.errors + ErrorManifest(FrameworkErrors.COMMAND_BUSY, 429)
            fragment.copyWith(contexts = listOf(context.copyWith(errors = errors)))
        }
        val conflicts = assertThrows<ManifestConflictException> {
            ManifestMerger.merge(listOf(ManifestFragment("a.json", fragment)))
        }.conflicts
        conflicts.assert().containsExactly(
            "error cart.empty of context shop is declared twice in a.json",
            "error wow.command.busy of context shop in a.json takes the framework's prefix",
        )
    }

    @Test
    fun `a fragment's top-level errors should be the framework's`() {
        val fragment = Manifests.fragment().copyWith(
            errors = FrameworkErrors.ALL.take(1) + ErrorManifest("cart.empty", 422) +
                ErrorManifest(FrameworkErrors.COMMAND_BUSY, 503),
        )
        val conflicts = assertThrows<ManifestConflictException> {
            ManifestMerger.merge(listOf(ManifestFragment("a.json", fragment)))
        }.conflicts
        conflicts.assert().containsExactly(
            "error cart.empty of a.json is not the framework's; a context lists its domain's errors",
            "error wow.command.busy of a.json is not the framework's; a context lists its domain's errors",
        )
    }

    @Test
    fun `merging should be the same whatever the order of the fragments`() {
        val shop = ManifestFragment("shop.json", Manifests.fragment("shop", defs = mapOf("z" to 1, "a" to 2)))
        val billing = ManifestFragment("billing.json", Manifests.fragment("billing", defs = mapOf("m" to 3)))
        ManifestMerger.merge(listOf(shop, billing)).assert().isEqualTo(ManifestMerger.merge(listOf(billing, shop)))
    }

    @Test
    fun `aggregates listing the same lifecycle commands should not conflict and keep their lists`() {
        val basket = Manifests.cart(aggregate = "basket").let { basket ->
            basket.copyWith(
                commands = emptyList(),
                events = emptyList(),
                lifecycle = Manifests.LIFECYCLE.take(1),
            )
        }
        val shop = ManifestFragment("shop.json", Manifests.fragment("shop", listOf(Manifests.cart(), basket)))
        val billing = ManifestFragment("billing.json", Manifests.fragment("billing"))

        val merged = ManifestMerger.merge(listOf(shop, billing))

        merged.contexts.flatMap { context -> context.aggregates.map { "${context.name}.${it.name}" to it.lifecycle } }
            .assert().containsExactly(
                "billing.cart" to Manifests.LIFECYCLE,
                "shop.cart" to Manifests.LIFECYCLE,
                "shop.basket" to Manifests.LIFECYCLE.take(1),
            )
    }

    @Test
    fun `a context declared by two fragments should conflict`() {
        val exception = assertThrows<ManifestConflictException> {
            ManifestMerger.merge(
                listOf(
                    ManifestFragment("a.json", Manifests.fragment("shop")),
                    ManifestFragment("b.json", Manifests.fragment("shop")),
                ),
            )
        }
        exception.conflicts.assert().contains("context shop is declared by a.json and b.json")
    }

    @Test
    fun `an aggregate declared twice should conflict`() {
        val fragment = Manifests.fragment(aggregates = listOf(Manifests.cart(), Manifests.cart()))
        assertThrows<ManifestConflictException> {
            ManifestMerger.merge(listOf(ManifestFragment("a.json", fragment)))
        }.conflicts.assert().contains("aggregate shop.cart is declared twice in a.json")
    }

    @Test
    fun `a command name taken by another command's alias should conflict`() {
        val cart = Manifests.cart()
        val addItem = cart.commands[0].copyWith(name = "AddItem", aliases = emptyList(), routes = emptyList())
        val clash = cart.copyWith(commands = cart.commands + addItem)
        val conflicts = assertThrows<ManifestConflictException> {
            ManifestMerger.merge(listOf(ManifestFragment("a.json", Manifests.fragment(aggregates = listOf(clash)))))
        }.conflicts
        conflicts.assert().containsExactly(
            "command type shop.cart.AddItem is declared by shop.cart.PutItem (a.json) and shop.cart.AddItem (a.json)",
        )
    }

    @Test
    fun `an event's full former name taken by an event of another aggregate should conflict`() {
        val basket = Manifests.cart(aggregate = "basket").let { basket ->
            val events = basket.events.map { it.copyWith(aliases = emptyList()) }
            basket.copyWith(events = events + EventManifest("ItemAdded", schema = "#/\$defs/x"))
        }
        val conflicts = assertThrows<ManifestConflictException> {
            ManifestMerger.merge(
                listOf(ManifestFragment("a.json", Manifests.fragment(aggregates = listOf(Manifests.cart(), basket)))),
            )
        }.conflicts
        conflicts.assert().containsExactly(
            "event type shop.basket.ItemAdded is declared by shop.cart.ItemPut (a.json) and shop.basket.ItemAdded (a.json)",
        )
    }

    @Test
    fun `a subscription declared twice in a context should conflict`() {
        val fragment = Manifests.fragment().let { fragment ->
            val context = fragment.contexts.single()
            fragment.copyWith(
                contexts = listOf(context.copyWith(subscriptions = context.subscriptions + context.subscriptions.first()))
            )
        }
        assertThrows<ManifestConflictException> {
            ManifestMerger.merge(listOf(ManifestFragment("a.json", fragment)))
        }.conflicts.assert().containsExactly(
            "subscription CartSummary.onEvent of context shop is declared twice in a.json"
        )
    }

    @Test
    fun `a query model declared twice, or on a route another declares, should conflict`() {
        val cart = Manifests.cart()
        val twice = cart.copyWith(queries = cart.queries + cart.queries.last())
        val clash = Manifests.cart(aggregate = "basket").let { basket ->
            basket.copyWith(
                events = emptyList(),
                queries = basket.queries.map { it.copyWith(routes = QueryRoutes.of("shop", "cart", it.model)) },
            )
        }
        val conflicts = assertThrows<ManifestConflictException> {
            ManifestMerger.merge(
                listOf(ManifestFragment("a.json", Manifests.fragment(aggregates = listOf(twice, clash)))),
            )
        }.conflicts
        conflicts.assert().containsExactly(
            "query model events of aggregate shop.cart is declared twice in a.json",
            "route POST /shop/cart/snapshot/query is declared by query shop.cart.snapshot (a.json) and " +
                "query shop.basket.snapshot (a.json)",
            "route GET /shop/cart/snapshot/query is declared by query shop.cart.snapshot (a.json) and " +
                "query shop.basket.snapshot (a.json)",
            "route POST /shop/cart/events/query is declared by query shop.cart.events (a.json) and " +
                "query shop.cart.events (a.json) and query shop.basket.events (a.json)",
            "route GET /shop/cart/events/query is declared by query shop.cart.events (a.json) and " +
                "query shop.cart.events (a.json) and query shop.basket.events (a.json)",
        )
    }

    @Test
    fun `a published stream should be of an aggregate of its context, once, on a topic of its own`() {
        val shop = Manifests.fragment().let { fragment ->
            val context = fragment.contexts.single()
            val published = context.published + PublishedManifest("cart", "wow.shop.cart.v2") +
                PublishedManifest("order", "wow.billing.cart.state")
            fragment.copyWith(contexts = listOf(context.copyWith(published = published)))
        }
        val conflicts = assertThrows<ManifestConflictException> {
            ManifestMerger.merge(
                listOf(ManifestFragment("a.json", shop), ManifestFragment("b.json", Manifests.fragment("billing"))),
            )
        }.conflicts
        conflicts.assert().containsExactly(
            "context shop in a.json publishes order, which is not one of its aggregates",
            "aggregate shop.cart is published twice in a.json",
            "topic wow.billing.cart.state is published by shop.order (a.json) and billing.cart (b.json)",
        )
    }

    @Test
    fun `only a saga should handle rejections`() {
        val fragment = Manifests.fragment().let { fragment ->
            val context = fragment.contexts.single()
            val projection = context.subscriptions.first().copyWith(rejected = emptyList())
            fragment.copyWith(contexts = listOf(context.copyWith(subscriptions = listOf(projection))))
        }
        assertThrows<ManifestConflictException> {
            ManifestMerger.merge(listOf(ManifestFragment("a.json", fragment)))
        }.conflicts.assert().containsExactly(
            "subscription CartSummary.onEvent of context shop in a.json handles receipts but is a PROJECTION",
        )
    }

    @Test
    fun `a job declared twice differently, or beside an aggregate named schedule, should conflict`() {
        val fragment = Manifests.fragment().let { fragment ->
            val context = fragment.contexts.single()
            val saga = context.subscriptions.first().copyWith(kind = SubscriptionKind.SAGA)
            val nightly = saga.copyWith(name = "Jobs.nightly", job = JobManifest("nightly", "0 0 3 * * *"))
            val again = nightly.copyWith(name = "Jobs.again")
            val other = nightly.copyWith(name = "Jobs.other", job = JobManifest("nightly", "0 0 4 * * *"))
            val schedule = context.aggregates.first().copyWith(name = "schedule")
            fragment.copyWith(
                contexts = listOf(
                    context.copyWith(
                        aggregates = context.aggregates + schedule,
                        subscriptions = listOf(nightly, again, other),
                    ),
                ),
            )
        }
        assertThrows<ManifestConflictException> {
            ManifestMerger.merge(listOf(ManifestFragment("a.json", fragment)))
        }.conflicts.assert().contains(
            "job nightly of context shop is declared twice, differently, in a.json",
            "context shop in a.json has jobs and an aggregate named schedule, the name of its built-in job aggregate",
        )
    }

    @Test
    fun `a schema that differs between fragments should conflict`() {
        val conflicts = assertThrows<ManifestConflictException> {
            ManifestMerger.merge(
                listOf(
                    ManifestFragment("a.json", Manifests.fragment("shop", defs = shared)),
                    ManifestFragment(
                        "b.json",
                        Manifests.fragment("billing", defs = mapOf("sample.Money" to mapOf("type" to "number")))
                    ),
                ),
            )
        }.conflicts
        conflicts.assert().containsExactly("schema sample.Money differs between a.json and b.json")
    }

    @Test
    fun `a fragment of another format should be refused`() {
        val exception = assertThrows<ManifestConflictException> {
            ManifestMerger.merge(listOf(ManifestFragment("a.json", Manifests.fragment().copyWith(format = 2))))
        }
        exception.conflicts.assert().containsExactly("a.json is of format 2, not 1")
        exception.message.assert().contains("- a.json is of format 2, not 1")
    }
}
