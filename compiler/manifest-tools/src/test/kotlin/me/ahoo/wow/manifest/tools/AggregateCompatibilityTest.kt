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

package me.ahoo.wow.manifest.tools

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.AccessAttributeManifest
import me.ahoo.wow.manifest.AggregateIdSource
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.PublishedManifest
import me.ahoo.wow.manifest.QueryManifest
import me.ahoo.wow.manifest.RouteManifest
import me.ahoo.wow.manifest.SnapshotManifest
import me.ahoo.wow.manifest.SystemCommands
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.BASELINE
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.CART
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.CONTEXT
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.INTEGER
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.NARROWED_EVENT
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.ROUTE
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.STRING
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.addItem
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.cart
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.context
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.def
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.itemAdded
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.schema
import org.junit.jupiter.api.Test

/** An aggregate's changes ([compareAggregate]) the classification table of [ManifestCompatibilityTest] leaves out. */
class AggregateCompatibilityTest {
    private val baseline = BASELINE

    private fun changes(baseline: Manifest, current: Manifest): List<String> =
        ManifestCompatibility.compare(baseline, current).changes.map { it.toString() }

    /** The baseline with `AddItem` routed by [routes] and its aggregate's id assigned by [idSource]. */
    private fun addItem(idSource: AggregateIdSource, vararg routes: RouteManifest): Manifest = baseline.cart { cart ->
        cart.copyWith(
            idSource = idSource,
            commands = cart.commands.map { if (it.system) it else it.copyWith(routes = routes.toList()) },
        )
    }

    private val byId = RouteManifest("POST", "/shop/cart/{id}/add-item")
    private val byNoId = RouteManifest("POST", "/shop/cart/add-item")

    @Test
    fun `a route by id removed beside an id source change should be reported, whichever way the change goes`() {
        val removed = "BREAKING contexts[shop].aggregates[cart].commands[AddItem].routes: " +
            "route POST /shop/cart/{id}/add-item removed"
        changes(addItem(AggregateIdSource.OWNER, byId, byNoId), addItem(AggregateIdSource.ASSIGNED, byNoId))
            .assert().containsExactlyInAnyOrder(
                "BREAKING contexts[shop].aggregates[cart].idSource: id source changed from OWNER to ASSIGNED",
                removed,
            )
        changes(addItem(AggregateIdSource.ASSIGNED, byId, byNoId), addItem(AggregateIdSource.OWNER, byNoId))
            .assert().containsExactlyInAnyOrder(
                "BREAKING contexts[shop].aggregates[cart].idSource: id source changed from ASSIGNED to OWNER",
                removed,
            )
    }

    @Test
    fun `a route by no id the owned side alone has should go with the id source change`() {
        changes(addItem(AggregateIdSource.OWNER, byId, byNoId), addItem(AggregateIdSource.ASSIGNED, byId))
            .assert().containsExactly(
                "BREAKING contexts[shop].aggregates[cart].idSource: id source changed from OWNER to ASSIGNED",
            )
    }

    @Test
    fun `a readers' break should take the place only of the same change at its path, not of another`() {
        // The event's productId made optional, which its readers break on, and bounded, which they do not.
        val current = baseline.copyWith(
            defs = baseline.defs + (
                "shop.ItemAdded" to mapOf(
                    "type" to "object",
                    "properties" to mapOf("productId" to mapOf("type" to "string", "maxLength" to 3)),
                    "required" to emptyList<String>(),
                )
                ),
        )
        changes(baseline, current).assert().containsExactlyInAnyOrder(
            "ADDITIVE contexts[shop].aggregates[cart].events[ItemAdded].schema.productId: maxLength 3 imposed",
            "BREAKING contexts[shop].aggregates[cart].events[ItemAdded].schema.productId: field made optional: " +
                "the subscribers of its published stream read it",
        )
    }

    @Test
    fun `an event should be retired by each of its names, full or simple, whichever the other is`() {
        // Its name is simple and retired by its full name; its alias is full and retired by its simple name.
        val aliased = baseline.cart { cart ->
            cart.copyWith(events = cart.events.map { it.copyWith(aliases = listOf("shop.cart.ItemPut")) })
        }
        val retired = aliased.cart {
            it.copyWith(
                events = emptyList(),
                retired = listOf("shop.cart.ItemAdded", "ItemPut")
            )
        }
        changes(aliased, retired).assert().containsExactlyInAnyOrder(
            "ADDITIVE contexts[shop].aggregates[cart].events[ItemAdded]: event retired",
            "ADDITIVE contexts[shop].aggregates[cart].retired: retired event shop.cart.ItemAdded added",
            "ADDITIVE contexts[shop].aggregates[cart].retired: retired event ItemPut added",
        )
    }

    @Test
    fun `a revision should exempt a schema break only of an aggregate no one else reads`() {
        fun changes(current: Manifest) = ManifestCompatibility.compare(UNEXPOSED, current).changes.map { it.toString() }

        changes(
            UNEXPOSED.def("shop.CartState", schema())
                .cart { it.copyWith(snapshot = SnapshotManifest(stateRevision = 2)) },
        )
            .assert().containsExactlyInAnyOrder(
                "ADDITIVE $CART.snapshot.stateRevision: state revision raised from 1 to 2",
                "ADDITIVE $CART.state.items: field removed (snapshots rebuilt at state revision 2)",
            )
        changes(UNEXPOSED.def("shop.ItemAdded", schema()).itemAdded { it.copyWith(revision = 2) })
            .assert().containsExactlyInAnyOrder(
                "ADDITIVE $CART.events[ItemAdded].revision: revision raised from 1 to 2",
                "ADDITIVE $CART.events[ItemAdded].schema.productId: field removed (upgraded from revision 1)",
            )
        // A stream published without the state exposes its events, not its state.
        val withoutState = PublishedManifest("cart", "wow.shop.cart.state", includeState = false)
        changes(
            UNEXPOSED.def("shop.CartState", schema()).def("shop.ItemAdded", schema())
                .cart { it.copyWith(snapshot = SnapshotManifest(stateRevision = 2)) }
                .itemAdded { it.copyWith(revision = 2) }
                .context { it.copyWith(published = listOf(withoutState)) },
        ).assert().containsExactlyInAnyOrder(
            "ADDITIVE $CONTEXT.published[cart]: published stream added",
            "ADDITIVE $CART.snapshot.stateRevision: state revision raised from 1 to 2",
            "ADDITIVE $CART.state.items: field removed (snapshots rebuilt at state revision 2)",
            "ADDITIVE $CART.events[ItemAdded].revision: revision raised from 1 to 2",
            "BREAKING $CART.events[ItemAdded].schema.productId: field removed: the service upgrades its records " +
                "from revision 1, but the subscribers of its published stream read the event at its current revision",
        )
        // A query model added now has clients built against the new schema only, but one in the baseline had others.
        changes(
            UNEXPOSED.def("shop.ItemAdded", schema()).itemAdded { it.copyWith(revision = 2) }
                .cart { it.copyWith(queries = listOf(QueryManifest("events"))) },
        ).assert().containsExactlyInAnyOrder(
            "ADDITIVE $CART.queries[events]: query model added",
            "ADDITIVE $CART.events[ItemAdded].revision: revision raised from 1 to 2",
            "BREAKING $CART.events[ItemAdded].schema.productId: field removed: the service upgrades its records " +
                "from revision 1, but the events query's clients read the event at its current revision",
        )
        changes(
            UNEXPOSED.def("shop.CartState", schema("items" to STRING))
                .cart { it.copyWith(snapshot = SnapshotManifest(stateRevision = 2)) }
                .context { it.copyWith(published = listOf(PublishedManifest("cart", "wow.shop.cart.state"))) },
        ).assert().containsExactlyInAnyOrder(
            "ADDITIVE $CONTEXT.published[cart]: published stream added",
            "ADDITIVE $CART.snapshot.stateRevision: state revision raised from 1 to 2",
            "BREAKING $CART.state.items: type narrowed from [array] to [string]: snapshots are rebuilt at state " +
                "revision 2, but the subscribers of its published stream read the state",
            "ADDITIVE $CART.state.items[]: type widened from [string] to any",
        )
    }

    @Test
    fun `an access attribute removed, renamed, retyped or changed in multiplicity should break, one added should not`() {
        fun changes(baseline: Manifest, current: Manifest) =
            ManifestCompatibility.compare(baseline, current).changes.map { it.toString() }

        fun access(name: String, type: AccessAttributeManifest.Type, multiple: Boolean = false) =
            BASELINE.cart { it.copyWith(access = listOf(AccessAttributeManifest(name, type, multiple))) }

        val region = access("region", AccessAttributeManifest.Type.STRING)
        val multiple = access("region", AccessAttributeManifest.Type.STRING, multiple = true)
        val removed = "BREAKING $CART.access[region]: access attribute removed"

        changes(BASELINE, region).assert().containsExactly("ADDITIVE $CART.access[region]: access attribute added")
        changes(region, BASELINE).assert().containsExactly(removed)
        changes(region, region).assert().isEmpty()
        changes(region, access("area", AccessAttributeManifest.Type.STRING))
            .assert().containsExactly(removed, "ADDITIVE $CART.access[area]: access attribute added")
        changes(region, access("region", AccessAttributeManifest.Type.INTEGER)).assert()
            .containsExactly("BREAKING $CART.access[region]: access attribute type changed from STRING to INTEGER")
        changes(region, multiple).assert()
            .containsExactly("BREAKING $CART.access[region]: access attribute made multiple")
        changes(multiple, region).assert()
            .containsExactly("BREAKING $CART.access[region]: access attribute made single")
    }

    @Test
    fun `an id source changed either way should break, the routes by no id it adds or removes part of it`() {
        fun changes(baseline: Manifest, current: Manifest) =
            ManifestCompatibility.compare(baseline, current).changes.map { it.toString() }

        // The cart, its id its owner's: every command, its delete too, also routed by no id.
        val owned = BASELINE.cart { cart ->
            cart.copyWith(
                idSource = AggregateIdSource.OWNER,
                commands = cart.commands.map { command ->
                    if (command.system) {
                        SystemCommands.lifecycle("shop", "cart", command.name, owned = true)
                    } else {
                        command.copyWith(
                            routes = CommandRoutes.of("shop", "cart", command.name, command.creation, true)
                        )
                    }
                },
            )
        }
        owned.contexts.single().aggregates.single().commands.flatMap { it.routes }
            .map { "${it.method} ${it.path}" }.assert()
            .contains("POST /shop/cart/add-item", "DELETE /shop/cart")
        val idSource = "BREAKING $CART.idSource: id source changed from ASSIGNED to OWNER"
        changes(BASELINE, owned).assert().containsExactly(idSource)
        changes(owned, BASELINE).assert()
            .containsExactly("BREAKING $CART.idSource: id source changed from OWNER to ASSIGNED")
        changes(owned, owned).assert().isEmpty()

        // Outside the change, routes follow the rules: a route by id removed breaks.
        val byIdRemoved = owned.addItem { it.copyWith(routes = it.routes.filterNot { route -> route == ROUTE }) }
        changes(BASELINE, byIdRemoved).assert().containsExactlyInAnyOrder(
            idSource,
            "BREAKING $CART.commands[AddItem].routes: route POST /shop/cart/{id}/add-item removed",
        )
        // Without it, a route by no id of an aggregate whose id is its owner's follows them too.
        changes(owned, owned.addItem { it.copyWith(routes = listOf(ROUTE)) }).assert()
            .containsExactly("BREAKING $CART.commands[AddItem].routes: route POST /shop/cart/add-item removed")
        // A route by no id the assigned side has too is no part of the change.
        val unaddressed = BASELINE.addItem {
            it.copyWith(routes = CommandRoutes.of("shop", "cart", it.name, it.creation, true))
        }
        changes(unaddressed, owned.addItem { it.copyWith(routes = listOf(ROUTE)) }).assert().containsExactlyInAnyOrder(
            idSource,
            "BREAKING $CART.commands[AddItem].routes: route POST /shop/cart/add-item removed",
        )
        // Back to assigned ids, a route by no id the assigned side keeps is no part of the change either.
        changes(owned, unaddressed).assert()
            .containsExactly("BREAKING $CART.idSource: id source changed from OWNER to ASSIGNED")
    }

    @Test
    fun `a structural change of an unread state or event should ask for a new revision`() {
        fun changes(current: Manifest) = ManifestCompatibility.compare(UNEXPOSED, current).changes.map { it.toString() }

        changes(UNEXPOSED.def("shop.ItemAdded", NARROWED_EVENT)).assert().containsExactly(
            "BREAKING $CART.events[ItemAdded].schema.quantity: required field added without a new revision and its " +
                "@Upgrade",
        )
        changes(UNEXPOSED.def("shop.CartState", schema())).assert()
            .containsExactly("BREAKING $CART.state.items: field removed without a new state revision")
        // The events query, which every aggregate lists, is a reader that a new revision does not keep.
        val queried = UNEXPOSED.cart { it.copyWith(queries = listOf(QueryManifest("events"))) }
        ManifestCompatibility.compare(queried, queried.def("shop.ItemAdded", NARROWED_EVENT)).changes
            .map { it.toString() }.assert().containsExactly(
                "BREAKING $CART.events[ItemAdded].schema.quantity: required field added: the events query's " +
                    "clients read the event, which a new revision does not change",
            )
    }

    @Test
    fun `a constraint of a state or an event should count for its readers alone`() {
        val bounded = { max: Int ->
            schema(
                "productId" to STRING + ("maxLength" to max),
                required = listOf("productId")
            )
        }
        val published = BASELINE.def("shop.ItemAdded", bounded(16))
        fun changes(baseline: Manifest, max: Int) =
            ManifestCompatibility.compare(baseline, baseline.def("shop.ItemAdded", bounded(max))).changes
                .map { it.toString() }

        // Tightened: the readers read less than before, and no stored record is checked against it.
        changes(published, 8).assert()
            .containsExactly("ADDITIVE $CART.events[ItemAdded].schema.productId: maxLength lowered from 16 to 8")
        // Loosened: the readers may read a value they were not built for.
        changes(published, 32).assert().containsExactly(
            "BREAKING $CART.events[ItemAdded].schema.productId: maxLength raised from 16 to 32: the subscribers " +
                "of its published stream read it",
        )
        // Without readers, neither way breaks.
        val unexposed = UNEXPOSED.def("shop.ItemAdded", bounded(16))
        changes(unexposed, 8).assert()
            .containsExactly("ADDITIVE $CART.events[ItemAdded].schema.productId: maxLength lowered from 16 to 8")
        changes(unexposed, 32).assert()
            .containsExactly("ADDITIVE $CART.events[ItemAdded].schema.productId: maxLength raised from 16 to 32")
        // A state's constraint the same way.
        val state = BASELINE.def("shop.CartState", schema("items" to mapOf("type" to "array", "items" to STRING)))
        ManifestCompatibility.compare(
            state,
            state.def(
                "shop.CartState",
                schema("items" to mapOf("type" to "array", "items" to STRING, "maxItems" to 4))
            ),
        ).changes.map { it.toString() }.assert()
            .containsExactly("ADDITIVE $CART.state.items: maxItems 4 imposed")
    }

    @Test
    fun `a state or an event that its readers would no longer read should break whatever its revision`() {
        // The baseline's cart has a published stream: its events have readers.
        val optional = schema("productId" to STRING)
        ManifestCompatibility.compare(BASELINE, BASELINE.def("shop.ItemAdded", optional)).changes.map { it.toString() }
            .assert().containsExactly(
                "BREAKING $CART.events[ItemAdded].schema.productId: field made optional: the subscribers of its " +
                    "published stream read it",
            )
        val revised = BASELINE.def("shop.ItemAdded", optional).cart {
            it.copyWith(events = it.events.map { event -> event.copyWith(revision = 2) })
        }
        ManifestCompatibility.compare(BASELINE, revised).changes.filter { it.kind == ChangeKind.BREAKING }
            .map { it.path }.assert().containsExactly("$CART.events[ItemAdded].schema.productId")
        // No one reads an unexposed aggregate's events: a field made optional is its own records' concern alone.
        ManifestCompatibility.compare(UNEXPOSED, UNEXPOSED.def("shop.ItemAdded", optional)).changes
            .map { it.toString() }.assert()
            .containsExactly("ADDITIVE $CART.events[ItemAdded].schema.productId: field made optional")
    }

    private companion object {
        /** The baseline of an aggregate no one but the service reads: no query model, no published stream. */
        private val UNEXPOSED: Manifest
            get() = BASELINE.cart { it.copyWith(queries = emptyList()) }
                .context { it.copyWith(published = emptyList()) }
    }
}
