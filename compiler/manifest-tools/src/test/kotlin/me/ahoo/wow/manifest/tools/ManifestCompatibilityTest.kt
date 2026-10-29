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
import me.ahoo.wow.manifest.AggregateIdSource
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Creation
import me.ahoo.wow.manifest.ErrorManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.FunctionKind
import me.ahoo.wow.manifest.FunctionManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.Ownership
import me.ahoo.wow.manifest.PublishedManifest
import me.ahoo.wow.manifest.QueryManifest
import me.ahoo.wow.manifest.RouteManifest
import me.ahoo.wow.manifest.RouteParameterManifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.ServiceRouteManifest
import me.ahoo.wow.manifest.SnapshotManifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.manifest.SubscriptionSource
import me.ahoo.wow.manifest.SubscriptionStart
import me.ahoo.wow.manifest.SystemCommands
import me.ahoo.wow.manifest.copyWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

/** The classification table of [ManifestCompatibility]: each structural change kind, additive or breaking. */
class ManifestCompatibilityTest {
    companion object {
        private const val CONTEXT = "contexts[shop]"
        private const val CART = "$CONTEXT.aggregates[cart]"
        private const val DELETE = "wow.lifecycle.DeleteAggregate"
        private const val APPLY_TAGS = "wow.lifecycle.ApplyTags"
        private val STRING = mapOf("type" to "string")
        private val ROUTE = RouteManifest("POST", "/shop/cart/{id}/add-item")
        private val ERROR = ErrorManifest("shop.cart.full", 409)

        private fun schema(vararg properties: Pair<String, Any?>, required: List<String> = emptyList()) =
            mapOf("type" to "object", "properties" to mapOf(*properties), "required" to required)

        private val ADD_ITEM = CommandManifest(
            name = "AddItem",
            schema = Manifest.schemaRef("shop.AddItem"),
            produces = listOf("ItemAdded"),
            routes = listOf(ROUTE),
        )
        private val ITEM_ADDED = EventManifest(name = "ItemAdded", schema = Manifest.schemaRef("shop.ItemAdded"))
        private val SUBSCRIPTION = SubscriptionManifest(
            name = "CartProjector.onItemAdded",
            kind = SubscriptionKind.PROJECTION,
            source = SubscriptionSource(context = "shop", aggregates = listOf("cart")),
            function = FunctionManifest(FunctionKind.PROJECT, "shop", "CartProjector", "onItemAdded"),
            derives = listOf("shop.cart.Checkout"),
        )

        private val COMMITTED = SubscriptionManifest(
            name = "CheckoutSaga.onCommitted",
            kind = SubscriptionKind.SAGA,
            source = SubscriptionSource(),
            function = FunctionManifest(FunctionKind.SAGA, "shop", "CheckoutSaga", "onCommitted"),
            committed = listOf("shop.cart.Checkout", "shop.cart.Clear"),
        )

        val BASELINE = Manifest(
            service = ServiceInfo("shop-service", "10.1.0"),
            contexts = listOf(
                ContextManifest(
                    name = "shop",
                    aggregates = listOf(
                        AggregateManifest(
                            name = "cart",
                            state = StateManifest(Manifest.schemaRef("shop.CartState"), fingerprint = "a"),
                            commands = listOf(ADD_ITEM, SystemCommands.lifecycle("shop", "cart", DELETE)),
                            events = listOf(ITEM_ADDED),
                            queries = listOf(
                                QueryManifest("snapshot", listOf(RouteManifest("POST", "/shop/cart/snapshot/query")))
                            ),
                        ),
                    ),
                    subscriptions = listOf(SUBSCRIPTION),
                    published = listOf(PublishedManifest("cart", "wow.shop.cart.state")),
                    errors = listOf(ERROR),
                ),
            ),
            errors = listOf(ErrorManifest("wow.command.invalid", 400)),
            defs = mapOf(
                "shop.CartState" to schema("items" to mapOf("type" to "array", "items" to STRING)),
                "shop.AddItem" to schema("productId" to STRING, required = listOf("productId")),
                "shop.ItemAdded" to schema("productId" to STRING, required = listOf("productId")),
                DELETE to mapOf("type" to "object"),
            ),
        )

        private fun Manifest.context(change: (ContextManifest) -> ContextManifest): Manifest =
            copyWith(contexts = contexts.map(change))

        private fun Manifest.cart(change: (AggregateManifest) -> AggregateManifest): Manifest =
            context { it.copyWith(aggregates = it.aggregates.map(change)) }

        private fun Manifest.addItem(change: (CommandManifest) -> CommandManifest): Manifest =
            cart {
                it.copyWith(
                    commands = it.commands.map { command -> if (command.system) command else change(command) }
                )
            }

        private fun Manifest.itemAdded(change: (EventManifest) -> EventManifest): Manifest =
            cart { it.copyWith(events = it.events.map(change)) }

        private fun Manifest.subscription(change: (SubscriptionManifest) -> SubscriptionManifest): Manifest =
            context { it.copyWith(subscriptions = it.subscriptions.map(change)) }

        private fun Manifest.published(change: (PublishedManifest) -> PublishedManifest): Manifest =
            context { it.copyWith(published = it.published.map(change)) }

        private fun Manifest.def(key: String, schema: Map<String, Any?>): Manifest =
            copyWith(defs = defs + (key to schema))

        private fun row(name: String, current: Manifest, vararg expected: String): Arguments =
            Arguments.of(name, current, expected.toList())

        private val INTEGER = mapOf("type" to "integer")

        /** The baseline of an aggregate no one but the service reads: no query model, no published stream. */
        private val UNEXPOSED: Manifest
            get() = BASELINE.cart { it.copyWith(queries = emptyList()) }
                .context { it.copyWith(published = emptyList()) }

        private val NARROWED_EVENT =
            schema("productId" to STRING, "quantity" to STRING, required = listOf("productId", "quantity"))

        @JvmStatic
        @Suppress("LongMethod") // The table.
        fun table(): List<Arguments> = listOf(
            row("no change", BASELINE),
            row(
                "descriptions, fingerprints, schedules and start positions are not contract",
                BASELINE.cart {
                    it.copyWith(
                        description = "a cart",
                        state = it.state.copyWith(fingerprint = "b"),
                        schedules = listOf("x")
                    )
                }.subscription { it.copyWith(start = SubscriptionStart.END) },
            ),
            row(
                "format changed",
                BASELINE.copyWith(format = 2),
                "BREAKING format: the Manifest format changed from 1 to 2"
            ),
            row(
                "service renamed",
                BASELINE.copyWith(service = ServiceInfo("store", "10.1.0")),
                "BREAKING service: the service was renamed from shop-service to store",
            ),
            row("context removed", BASELINE.copyWith(contexts = emptyList()), "BREAKING $CONTEXT: context removed"),
            row(
                "context added",
                BASELINE.copyWith(contexts = BASELINE.contexts + ContextManifest("billing")),
                "ADDITIVE contexts[billing]: context added",
            ),
            row(
                "context renamed",
                BASELINE.context { it.copyWith(name = "store") },
                "BREAKING $CONTEXT: context removed",
                "ADDITIVE contexts[store]: context added",
            ),
            row(
                "aggregate removed",
                BASELINE.context { it.copyWith(aggregates = emptyList()) },
                "BREAKING $CART: aggregate removed"
            ),
            row(
                "aggregate added",
                BASELINE.context {
                    it.copyWith(
                        aggregates = it.aggregates + AggregateManifest("order", state = StateManifest(Manifest.schemaRef("shop.CartState")))
                    )
                },
                "ADDITIVE $CONTEXT.aggregates[order]: aggregate added",
            ),
            row(
                "command removed",
                BASELINE.cart { it.copyWith(commands = it.commands - ADD_ITEM) },
                "BREAKING $CART.commands[AddItem]: command removed",
            ),
            row(
                "command added",
                BASELINE.cart {
                    it.copyWith(
                        commands = it.commands + ADD_ITEM.copyWith(name = "Clear", routes = emptyList())
                    )
                },
                "ADDITIVE $CART.commands[Clear]: command added",
            ),
            row(
                "command renamed with an alias",
                BASELINE.addItem { it.copyWith(name = "PutItem", aliases = listOf("AddItem")) },
                "ADDITIVE $CART.commands[AddItem]: command renamed to PutItem, which keeps AddItem as an alias",
                "ADDITIVE $CART.commands[PutItem].aliases: alias AddItem added",
            ),
            row(
                "command alias added",
                BASELINE.addItem { it.copyWith(aliases = listOf("Put")) },
                "ADDITIVE $CART.commands[AddItem].aliases: alias Put added",
            ),
            row(
                "command creation narrowed",
                BASELINE.addItem { it.copyWith(creation = Creation.REQUIRED) },
                "BREAKING $CART.commands[AddItem].creation: creation changed from NONE to REQUIRED",
            ),
            row(
                "command creation widened",
                BASELINE.addItem { it.copyWith(creation = Creation.ALLOWED) },
                "ADDITIVE $CART.commands[AddItem].creation: creation changed from NONE to ALLOWED",
            ),
            row(
                "produced event removed",
                BASELINE.addItem { it.copyWith(produces = emptyList()) },
                "BREAKING $CART.commands[AddItem].produces: produced event ItemAdded removed",
            ),
            row(
                "produced event added",
                BASELINE.addItem { it.copyWith(produces = listOf("ItemAdded", "CartFull")) },
                "ADDITIVE $CART.commands[AddItem].produces: produced event CartFull added",
            ),
            row(
                "produced events no longer declared",
                BASELINE.addItem { it.copyWith(produces = null) },
                "ADDITIVE $CART.commands[AddItem].produces: produced events no longer declared",
            ),
            row(
                "route removed",
                BASELINE.addItem { it.copyWith(routes = emptyList()) },
                "BREAKING $CART.commands[AddItem].routes: route POST /shop/cart/{id}/add-item removed",
            ),
            row(
                "route added",
                BASELINE.addItem { it.copyWith(routes = it.routes + RouteManifest("POST", "/shop/cart/add-item")) },
                "ADDITIVE $CART.commands[AddItem].routes: route POST /shop/cart/add-item added",
            ),
            row(
                "command field required",
                BASELINE.def(
                    "shop.AddItem",
                    schema("productId" to STRING, "quantity" to STRING, required = listOf("productId", "quantity"))
                ),
                "BREAKING $CART.commands[AddItem].schema.quantity: required field added",
            ),
            row(
                "command field optional",
                BASELINE.def(
                    "shop.AddItem",
                    schema("productId" to STRING, "quantity" to STRING, required = listOf("productId"))
                ),
                "ADDITIVE $CART.commands[AddItem].schema.quantity: optional field added",
            ),
            row(
                "event removed",
                BASELINE.cart { it.copyWith(events = emptyList()) },
                "BREAKING $CART.events[ItemAdded]: event removed: list it in @AggregateRoot(retiredEvents) to " +
                    "retire it, or its history no longer loads",
            ),
            row(
                "event retired",
                BASELINE.cart { it.copyWith(events = emptyList(), retired = listOf("ItemAdded")) },
                "ADDITIVE $CART.events[ItemAdded]: event retired",
                "ADDITIVE $CART.retired: retired event ItemAdded added",
            ),
            row(
                "event retired by its full name",
                BASELINE.cart { it.copyWith(events = emptyList(), retired = listOf("shop.cart.ItemAdded")) },
                "ADDITIVE $CART.events[ItemAdded]: event retired",
                "ADDITIVE $CART.retired: retired event shop.cart.ItemAdded added",
            ),

            row(
                "event renamed with an alias",
                BASELINE.itemAdded { it.copyWith(name = "ItemPut", aliases = listOf("ItemAdded")) },
                "ADDITIVE $CART.events[ItemAdded]: event renamed to ItemPut, which keeps ItemAdded as an alias",
                "ADDITIVE $CART.events[ItemPut].aliases: alias ItemAdded added",
            ),
            row(
                "event schema broken without a new revision",
                BASELINE.def("shop.ItemAdded", NARROWED_EVENT),
                "BREAKING $CART.events[ItemAdded].schema.quantity: required field added without a new revision and its @Upgrade",
            ),
            row(
                "event schema broken with a new revision, of a published aggregate",
                BASELINE.def("shop.ItemAdded", NARROWED_EVENT).itemAdded { it.copyWith(revision = 2) },
                "ADDITIVE $CART.events[ItemAdded].revision: revision raised from 1 to 2",
                "BREAKING $CART.events[ItemAdded].schema.quantity: required field added: the service upgrades its " +
                    "records from revision 1, but the subscribers of its published stream read the event at its " +
                    "current revision",
            ),
            row(
                "event field type changed with a new revision, of a published aggregate",
                BASELINE.def("shop.ItemAdded", schema("productId" to INTEGER, required = listOf("productId")))
                    .itemAdded { it.copyWith(revision = 2) },
                "ADDITIVE $CART.events[ItemAdded].revision: revision raised from 1 to 2",
                "BREAKING $CART.events[ItemAdded].schema.productId: type narrowed from [string] to [integer]: the " +
                    "service upgrades its records from revision 1, but the subscribers of its published stream read " +
                    "the event at its current revision",
            ),
            row(
                "event revised with an optional field",
                BASELINE.def(
                    "shop.ItemAdded",
                    schema("productId" to STRING, "note" to STRING, required = listOf("productId"))
                )
                    .itemAdded { it.copyWith(revision = 2) },
                "ADDITIVE $CART.events[ItemAdded].revision: revision raised from 1 to 2",
                "ADDITIVE $CART.events[ItemAdded].schema.note: optional field added",
            ),
            row(
                "subscription source context changed",
                BASELINE.subscription { it.copyWith(source = it.source.copyWith(context = "store")) },
                "BREAKING $CONTEXT.subscriptions[CartProjector.onItemAdded].source: source stream changed from " +
                    "shop[cart] to store[cart]: its checkpoint belongs to the old one",
            ),
            row(
                "event field added optional",
                BASELINE.def(
                    "shop.ItemAdded",
                    schema("productId" to STRING, "note" to STRING, required = listOf("productId"))
                ),
                "ADDITIVE $CART.events[ItemAdded].schema.note: optional field added",
            ),
            row(
                "event revision lowered",
                BASELINE.itemAdded { it.copyWith(revision = 0) }.def("shop.ItemAdded", NARROWED_EVENT),
                "BREAKING $CART.events[ItemAdded].revision: revision lowered from 1 to 0",
                "BREAKING $CART.events[ItemAdded].schema.quantity: required field added",
            ),
            row(
                "query field removed",
                BASELINE.def("shop.CartState", schema()),
                "BREAKING $CART.state.items: field removed without a new state revision",
            ),
            row(
                "state field removed with a new state revision, of a queried and published aggregate",
                BASELINE.def(
                    "shop.CartState",
                    schema()
                ).cart { it.copyWith(snapshot = SnapshotManifest(stateRevision = 2)) },
                "ADDITIVE $CART.snapshot.stateRevision: state revision raised from 1 to 2",
                "BREAKING $CART.state.items: field removed: snapshots are rebuilt at state revision 2, but the " +
                    "snapshot query's clients and the subscribers of its published stream read the state",
            ),
            row(
                "state revision raised alone",
                BASELINE.cart { it.copyWith(snapshot = SnapshotManifest(stateRevision = 3)) },
                "ADDITIVE $CART.snapshot.stateRevision: state revision raised from 1 to 3",
            ),
            row(
                "state revision raised with an optional field",
                BASELINE.def(
                    "shop.CartState",
                    schema("items" to mapOf("type" to "array", "items" to STRING), "total" to STRING)
                ).cart { it.copyWith(snapshot = SnapshotManifest(stateRevision = 2)) },
                "ADDITIVE $CART.snapshot.stateRevision: state revision raised from 1 to 2",
                "ADDITIVE $CART.state.total: optional field added",
            ),

            row(
                "query field added",
                BASELINE.def(
                    "shop.CartState",
                    schema("items" to mapOf("type" to "array", "items" to STRING), "total" to STRING)
                ),
                "ADDITIVE $CART.state.total: optional field added",
            ),
            row(
                "snapshots disabled",
                BASELINE.cart { it.copyWith(snapshot = SnapshotManifest(false)) },
                "BREAKING $CART.snapshot: snapshots disabled"
            ),
            row(
                "ownership changed",
                BASELINE.cart { it.copyWith(ownership = Ownership.TENANT) },
                "BREAKING $CART.ownership: ownership changed from OWNER to TENANT",
            ),
            row(
                "lifecycle command removed",
                BASELINE.cart { it.copyWith(commands = it.domainCommands) },
                "BREAKING $CART.commands[$DELETE]: command removed",
            ),
            row(
                "lifecycle command added",
                BASELINE.cart {
                    it.copyWith(
                        commands = it.commands + SystemCommands.lifecycle("shop", "cart", APPLY_TAGS)
                    )
                },
                "ADDITIVE $CART.commands[$APPLY_TAGS]: command added",
            ),
            row(
                "query model removed",
                BASELINE.cart { it.copyWith(queries = emptyList()) },
                "BREAKING $CART.queries[snapshot]: query model removed"
            ),
            row(
                "query route removed",
                BASELINE.cart { it.copyWith(queries = listOf(QueryManifest("snapshot"))) },
                "BREAKING $CART.queries[snapshot].routes: route POST /shop/cart/snapshot/query removed",
            ),
            row(
                "subscription removed",
                BASELINE.context { it.copyWith(subscriptions = emptyList()) },
                "BREAKING $CONTEXT.subscriptions[CartProjector.onItemAdded]: subscription removed",
            ),
            row(
                "subscription handler renamed",
                BASELINE.subscription {
                    it.copyWith(
                        name = "CartProjector.onItemsAdded",
                        function = it.function.copyWith(name = "onItemsAdded")
                    )
                },
                "BREAKING $CONTEXT.subscriptions[CartProjector.onItemAdded]: subscription removed: if " +
                    "CartProjector.onItemsAdded is its handler renamed, annotate it @Name(\"onItemAdded\") to keep the " +
                    "subscription, its checkpoint and its executions",
                "ADDITIVE $CONTEXT.subscriptions[CartProjector.onItemsAdded]: subscription added",
            ),
            row(
                "subscription kind changed",
                BASELINE.subscription { it.copyWith(kind = SubscriptionKind.REACTOR) },
                "BREAKING $CONTEXT.subscriptions[CartProjector.onItemAdded].kind: kind changed from PROJECTION to REACTOR",
            ),
            row(
                "subscription function changed",
                BASELINE.subscription { it.copyWith(function = it.function.copyWith(name = "on")) },
                "BREAKING $CONTEXT.subscriptions[CartProjector.onItemAdded].function: function changed from " +
                    "FunctionManifest(kind=PROJECT, context=shop, processor=CartProjector, name=onItemAdded) to " +
                    "FunctionManifest(kind=PROJECT, context=shop, processor=CartProjector, name=on)",
            ),
            row(
                "subscription source changed",
                BASELINE.subscription { it.copyWith(source = it.source.copyWith(aggregates = listOf("order"))) },
                "BREAKING $CONTEXT.subscriptions[CartProjector.onItemAdded].source: source stream changed from " +
                    "shop[cart] to shop[order]: its checkpoint belongs to the old one",
            ),
            row(
                "subscription events changed",
                BASELINE.subscription { it.copyWith(source = it.source.copyWith(events = listOf("ItemAdded"))) },
                "ADDITIVE $CONTEXT.subscriptions[CartProjector.onItemAdded].source.events: events changed from [] to [ItemAdded]",
            ),
            row(
                "derived command removed",
                BASELINE.subscription { it.copyWith(derives = emptyList()) },
                "BREAKING $CONTEXT.subscriptions[CartProjector.onItemAdded].derives: derived command shop.cart.Checkout removed",
            ),
            row(
                "derived commands no longer declared",
                BASELINE.subscription { it.copyWith(derives = null) },
                "ADDITIVE $CONTEXT.subscriptions[CartProjector.onItemAdded].derives: derived commands no longer declared",
            ),
            row(
                "committed handler added",
                BASELINE.context {
                    it.copyWith(subscriptions = it.subscriptions + COMMITTED)
                },
                "ADDITIVE $CONTEXT.subscriptions[CheckoutSaga.onCommitted]: subscription added",
            ),
            row(
                "commits handled",
                BASELINE.subscription { it.copyWith(committed = listOf("shop.cart.Checkout")) },
                "ADDITIVE $CONTEXT.subscriptions[CartProjector.onItemAdded].committed: handles commits",
            ),
            row(
                "call declared",
                BASELINE.subscription { it.copyWith(calls = listOf("ordering.order.SubmitOrder")) },
                "ADDITIVE $CONTEXT.subscriptions[CartProjector.onItemAdded].calls: called command " +
                    "ordering.order.SubmitOrder added",
            ),
            row(
                "rejections handled",
                BASELINE.subscription { it.copyWith(rejected = emptyList()) },
                "ADDITIVE $CONTEXT.subscriptions[CartProjector.onItemAdded].rejected: handles rejections",
            ),
            row(
                "stream topic changed",
                BASELINE.published { it.copyWith(topic = "cart") },
                "BREAKING $CONTEXT.published[cart].topic: topic changed from wow.shop.cart.state to cart",
            ),
            row(
                "stream state dropped",
                BASELINE.published { it.copyWith(includeState = false) },
                "BREAKING $CONTEXT.published[cart].includeState: the state is no longer included",
            ),
            row(
                "stream removed",
                BASELINE.context { it.copyWith(published = emptyList()) },
                "BREAKING $CONTEXT.published[cart]: published stream removed",
            ),
            row(
                "error code removed",
                BASELINE.context { it.copyWith(errors = emptyList()) },
                "BREAKING $CONTEXT.errors[shop.cart.full]: error code removed"
            ),
            row(
                "error status changed",
                BASELINE.context { it.copyWith(errors = listOf(ERROR.copyWith(status = 422))) },
                "BREAKING $CONTEXT.errors[shop.cart.full]: status changed from 409 to 422",
            ),
            row(
                "framework error code added",
                BASELINE.copyWith(errors = BASELINE.errors + ErrorManifest("wow.command.busy", 429)),
                "ADDITIVE errors[wow.command.busy]: error code added",
            ),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("table")
    fun `each structural change should be classified`(name: String, current: Manifest, expected: List<String>) {
        ManifestCompatibility.compare(BASELINE, current).changes.map { it.toString() }
            .assert().describedAs(name).containsExactlyInAnyOrderElementsOf(expected)
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
    fun `the reverse changes should be classified too`() {
        fun changes(baseline: Manifest, current: Manifest) =
            ManifestCompatibility.compare(baseline, current).changes.map { it.toString() }

        changes(BASELINE.cart { it.copyWith(retired = listOf("ItemRemoved")) }, BASELINE)
            .assert().containsExactly("BREAKING $CART.retired: retired event ItemRemoved removed")
        val retiring = BASELINE.itemAdded { it.copyWith(aliases = listOf("ItemPut")) }
        changes(retiring, BASELINE.cart { it.copyWith(events = emptyList(), retired = listOf("ItemAdded")) })
            .assert().containsExactlyInAnyOrder(
                "BREAKING $CART.events[ItemAdded]: event retired without its former names ItemPut: their records " +
                    "no longer load",
                "ADDITIVE $CART.retired: retired event ItemAdded added",
            )
        changes(retiring, BASELINE.cart { it.copyWith(events = emptyList(), retired = listOf("ItemAdded", "ItemPut")) })
            .assert().containsExactlyInAnyOrder(
                "ADDITIVE $CART.events[ItemAdded]: event retired",
                "ADDITIVE $CART.retired: retired event ItemAdded added",
                "ADDITIVE $CART.retired: retired event ItemPut added",
            )

        val aliased = BASELINE.addItem { it.copyWith(aliases = listOf("Put")) }
        changes(
            aliased,
            BASELINE
        ).assert().containsExactly("BREAKING $CART.commands[AddItem].aliases: alias Put removed")
        changes(BASELINE.cart { it.copyWith(snapshot = SnapshotManifest(false)) }, BASELINE)
            .assert().containsExactly("ADDITIVE $CART.snapshot: snapshots enabled")
        changes(BASELINE.addItem { it.copyWith(produces = null) }, BASELINE)
            .assert().containsExactly("ADDITIVE $CART.commands[AddItem].produces: produced events declared")
        changes(BASELINE.subscription { it.copyWith(derives = null) }, BASELINE)
            .assert().containsExactly(
                "ADDITIVE $CONTEXT.subscriptions[CartProjector.onItemAdded].derives: derived commands declared"
            )
        changes(BASELINE.published { it.copyWith(includeState = false) }, BASELINE)
            .assert().containsExactly("ADDITIVE $CONTEXT.published[cart].includeState: the state is now included")
        changes(BASELINE.context { it.copyWith(subscriptions = emptyList()) }, BASELINE)
            .assert().containsExactly("ADDITIVE $CONTEXT.subscriptions[CartProjector.onItemAdded]: subscription added")
        changes(BASELINE.copyWith(service = null), BASELINE).assert().isEmpty()
        val revised = BASELINE.cart { it.copyWith(snapshot = SnapshotManifest(stateRevision = 2)) }
        changes(revised, BASELINE.def("shop.CartState", schema())).assert().containsExactlyInAnyOrder(
            "BREAKING $CART.snapshot.stateRevision: state revision lowered from 2 to 1",
            "BREAKING $CART.state.items: field removed",
        )
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
    fun `a committed handler's receipts should be classified`() {
        val saga = "$CONTEXT.subscriptions[CheckoutSaga.onCommitted]"
        val baseline = BASELINE.context { it.copyWith(subscriptions = it.subscriptions + COMMITTED) }
        fun changes(committed: List<String>?) = ManifestCompatibility.compare(
            baseline,
            baseline.subscription { if (it.name == COMMITTED.name) it.copyWith(committed = committed) else it },
        ).changes.map { it.toString() }

        ManifestCompatibility.compare(baseline, BASELINE).changes.map { it.toString() }
            .assert().containsExactly("BREAKING $saga: subscription removed")
        changes(COMMITTED.committed).assert().isEmpty()
        changes(listOf("shop.cart.Checkout", "shop.cart.Pay")).assert().containsExactlyInAnyOrder(
            "BREAKING $saga.committed: command with handled commits shop.cart.Clear removed",
            "ADDITIVE $saga.committed: command with handled commits shop.cart.Pay added",
        )
        changes(
            emptyList()
        ).assert().containsExactly("ADDITIVE $saga.committed: handles the commits of any derived command")
        changes(null).assert().containsExactly("BREAKING $saga.committed: no longer handles commits")
        ManifestCompatibility.compare(
            baseline.subscription { if (it.name == COMMITTED.name) it.copyWith(committed = emptyList()) else it },
            baseline,
        ).changes.map { it.toString() }.assert().containsExactly(
            "BREAKING $saga.committed: handles the commits of [shop.cart.Checkout, shop.cart.Clear] only, not of " +
                "any derived command",
        )
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `descriptions added to the Manifest should be no change`() {
        val current = BASELINE.addItem { it.copyWith(description = "Adds an item.") }
            .itemAdded { it.copyWith(description = "An item was added.") }
            .def(
                "shop.AddItem",
                BASELINE.defs.getValue("shop.AddItem") as Map<String, Any?> + ("description" to "Adds.")
            )
            .def(DELETE, mapOf("type" to "object", "description" to "Marks it deleted."))
        ManifestCompatibility.compare(BASELINE, current).changes.assert().isEmpty()
    }

    @Test
    fun `a breaking change should fail the gate, whatever the version`() {
        val broken = BASELINE.cart { it.copyWith(commands = it.commands - ADD_ITEM) }
        ManifestCompatibility.compare(BASELINE, broken).failed.assert().isTrue()
        // A new major version breaks on purpose by recording a new baseline, never by its number.
        ManifestCompatibility.compare(BASELINE, broken.copyWith(service = ServiceInfo("shop-service", "11.0.0")))
            .failed.assert().isTrue()
        ManifestCompatibility.compare(BASELINE, BASELINE).failed.assert().isFalse()
    }

    @Test
    fun `the report should list the breaking changes first`() {
        val current = BASELINE.cart {
            it.copyWith(commands = it.domainCommands + SystemCommands.lifecycle("shop", "cart", APPLY_TAGS))
        }
        ManifestCompatibility.compare(BASELINE, current).render().assert().isEqualTo(
            "Manifest against its baseline: 1 breaking, 1 additive\n" +
                "BREAKING $CART.commands[$DELETE]: command removed\n" +
                "ADDITIVE $CART.commands[$APPLY_TAGS]: command added\n",
        )
    }

    @Test
    fun `a break should need a later major, or a pre-release of a major's first version`() {
        Baseline.allowsBreaking(10, "10.1.0").assert().isFalse()
        Baseline.allowsBreaking(10, "10.0.1").assert().isFalse()
        Baseline.allowsBreaking(10, "10.1.0-rc.1").assert().isFalse()
        Baseline.allowsBreaking(10, "11.0.0").assert().isTrue()
        // Before a major's first release nothing is promised (decision D306): Wow's own 10.0.0 pre-releases.
        Baseline.allowsBreaking(10, "10.0.0-alpha.6").assert().isTrue()
        Baseline.allowsBreaking(null, "10.0.0-beta.1").assert().isTrue()
        // No version, or none to compare with: no break.
        Baseline.allowsBreaking(10, null).assert().isFalse()
        Baseline.allowsBreaking(null, "11.0.0").assert().isFalse()
        Baseline.allowsBreaking(10, "unspecified").assert().isFalse()
    }

    @Test
    fun `the gate should fail on no baseline, a break within the line and a stale baseline, naming the update`(
        @TempDir directory: File,
    ) {
        val baseline = File(directory, "manifest/baseline.json")
        val current = File(directory, "build/manifest.json")
        val report = File(directory, "build/report/compatibility.txt")
        val recordReport = File(directory, "build/report/baseline.txt")
        val owner = "project ':shop-server'"
        fun gate() = ManifestTools.checkCompatibility(baseline, current, report, owner)
        fun record(allowBreaking: Boolean = false) =
            ManifestTools.recordBaseline(current, baseline, allowBreaking, recordReport, owner)
        fun build(manifest: Manifest, version: String) =
            ManifestJson.write(manifest.copyWith(service = ServiceInfo("shop-service", version)), current)
        val broken = BASELINE.cart { it.copyWith(commands = it.commands - ADD_ITEM) }
        val additive = BASELINE.cart {
            it.copyWith(commands = it.commands + SystemCommands.lifecycle("shop", "cart", APPLY_TAGS))
        }

        build(BASELINE, "10.1.0")
        assertThrows<IllegalStateException> { gate() }.message.assert().isEqualTo(
            "project ':shop-server' has no Manifest baseline (baseline.json): run updateWowManifestBaseline of " +
                "project ':shop-server' to record the Manifest this build produces, review it and commit it.",
        )
        record()
        Baseline.read(baseline).line.assert().isEqualTo(10)
        Baseline.read(baseline).manifest.service.assert().isEqualTo(ServiceInfo("shop-service"))
        gate().assert().isZero()

        // A break within the line fails the gate, and the baseline refuses to record it.
        build(broken, "10.2.0")
        assertThrows<IllegalStateException> { gate() }.message.assert().contains(
            "The Manifest of project ':shop-server' has 1 breaking change(s) within its compatibility line 10 " +
                "(version 10.2.0):\n  - $CART.commands[AddItem]: command removed\n",
            "a break needs the service's next major version, then run updateWowManifestBaseline of project ':shop-server'",
        ).doesNotContain(directory.path)
        assertThrows<IllegalStateException> { record() }.message.assert().contains(
            "project ':shop-server' refuses to record 1 breaking change(s) within its compatibility line 10",
            "for a pre-release, record it with -Pwow.manifest.allowBreaking",
        )

        // An additive change keeps the contract, but the baseline must record it.
        build(additive, "10.2.0")
        assertThrows<IllegalStateException> { gate() }.message.assert().contains(
            "The Manifest baseline of project ':shop-server' does not record the Manifest this build produces " +
                "(1 additive change(s), 0 breaking one(s) a new major or an open pre-release allows; recorded on " +
                "the line 10, built on 10;",
            "run updateWowManifestBaseline of project ':shop-server', review the diff and commit it.",
        )
        record()
        gate().assert().isZero()

        // The next major may break: the gate allows it, and the baseline records it on the new line.
        build(broken, "11.0.0")
        assertThrows<IllegalStateException> { gate() }.message.assert().contains("does not record the Manifest")
        report.readText().assert().contains("breaking changes allowed")
        record()
        Baseline.read(baseline).line.assert().isEqualTo(11)
        gate().assert().isZero()

        // A pre-release of a major's first version keeps the line open: a break passes and records.
        build(BASELINE, "11.0.0-beta.1")
        record()
        gate().assert().isZero()

        // Within a closed line, -Pwow.manifest.allowBreaking records a break, and the report says so.
        build(broken, "11.1.0")
        record(allowBreaking = true)
        recordReport.readText().assert().contains(
            "With -Pwow.manifest.allowBreaking: recorded 1 breaking change(s) within the line",
        )
    }

    @Test
    fun `a service with no version should record no line, and break nothing`(@TempDir directory: File) {
        val baseline = File(directory, "manifest/baseline.json")
        val current = File(directory, "build/manifest.json")
        val report = File(directory, "build/report/compatibility.txt")
        val recordReport = File(directory, "build/report/baseline.txt")
        val owner = "project ':shop-server'"
        fun gate() = ManifestTools.checkCompatibility(baseline, current, report, owner)
        fun record() = ManifestTools.recordBaseline(current, baseline, false, recordReport, owner)

        ManifestJson.write(BASELINE.copyWith(service = ServiceInfo("shop-service")), current)
        record()
        baseline.readText().assert().doesNotContain("\"${Baseline.LINE}\"")
        Baseline.read(baseline).line.assert().isNull()
        recordReport.readText().assert().startsWith("Recorded on the compatibility line (none) (version (none))")
        gate().assert().isZero()
        report.readText().assert().contains("Compatibility line (none), version (none): breaking changes fail")

        val broken = BASELINE.cart { it.copyWith(commands = it.commands - ADD_ITEM) }
        ManifestJson.write(broken.copyWith(service = ServiceInfo("shop-service")), current)
        assertThrows<IllegalStateException> { gate() }.message.assert()
            .contains("has 1 breaking change(s) within its compatibility line (none) (version (none))")
        assertThrows<IllegalStateException> { record() }.message.assert()
            .contains("refuses to record 1 breaking change(s) within its compatibility line (none) (version (none))")
    }

    private val summary = ServiceRouteManifest(
        name = "summary",
        route = RouteManifest("GET", "/shop/summaries/{day}"),
        parameters = listOf(
            RouteParameterManifest("day", RouteParameterManifest.Location.PATH, mapOf("type" to "string")),
            RouteParameterManifest("limit", RouteParameterManifest.Location.QUERY, INTEGER, required = false),
        ),
        request = Manifest.refSchema("shop.Filter"),
        response = Manifest.arraySchema(Manifest.refSchema("shop.Summary")),
        errors = listOf("shop.summary.closed"),
    )

    private val routeBaseline = BASELINE.context { it.copyWith(routes = listOf(summary)) }
        .def("shop.Filter", schema("status" to STRING))
        .def("shop.Summary", schema("total" to INTEGER, required = listOf("total")))

    private fun routeChanges(current: Manifest) =
        ManifestCompatibility.compare(routeBaseline, current).changes.map { it.toString() }

    private fun route(change: (ServiceRouteManifest) -> ServiceRouteManifest) =
        routeBaseline.context { it.copyWith(routes = it.routes.map(change)) }

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

    @Test
    fun `a service route's changes should be classified, its request an input and its response an output`() {
        routeChanges(routeBaseline.context { it.copyWith(routes = emptyList()) }).assert()
            .containsExactly("BREAKING contexts[shop].routes[summary]: service route removed")
        routeChanges(route { it.copyWith(route = RouteManifest("GET", "/shop/days/{day}")) }).assert().containsExactly(
            "BREAKING contexts[shop].routes[summary].route: route changed from GET /shop/summaries/{day} to " +
                "GET /shop/days/{day}",
        )
        routeChanges(
            route { it.copyWith(parameters = it.parameters.map { p -> p.copyWith(required = true) }) }
        ).assert()
            .containsExactly("BREAKING contexts[shop].routes[summary].parameters[limit]: parameter made required")
        routeChanges(
            route {
                it.copyWith(
                    parameters = it.parameters +
                        RouteParameterManifest("page", RouteParameterManifest.Location.QUERY, INTEGER, required = true),
                )
            },
        ).assert().contains("BREAKING contexts[shop].routes[summary].parameters[page]: required parameter added")
        // The request may accept more; the response may not produce more.
        routeChanges(routeBaseline.def("shop.Filter", schema("status" to STRING, "since" to STRING))).assert()
            .containsExactly("ADDITIVE contexts[shop].routes[summary].request.since: optional field added")
        routeChanges(routeBaseline.def("shop.Summary", schema("total" to INTEGER))).assert()
            .containsExactly("BREAKING contexts[shop].routes[summary].response[].total: field made optional")
        routeChanges(route { it.copyWith(response = null) }).assert()
            .containsExactly("BREAKING contexts[shop].routes[summary].response: body removed")
    }

    @Test
    fun `a service route's parameters, bodies and refusal codes should be classified`() {
        routeChanges(
            route {
                it.copyWith(
                    parameters = listOf(
                        RouteParameterManifest(
                            "day",
                            RouteParameterManifest.Location.QUERY,
                            mapOf("type" to "string"),
                            true
                        ),
                    ),
                )
            },
        ).assert().containsExactlyInAnyOrder(
            "BREAKING contexts[shop].routes[summary].parameters[day]: parameter moved from PATH to QUERY",
            "BREAKING contexts[shop].routes[summary].parameters[limit]: parameter removed",
        )
        routeChanges(
            route {
                it.copyWith(
                    parameters = it.parameters.map { p -> p.copyWith(schema = mapOf("type" to "string")) }
                )
            }
        )
            .assert().contains(
                "BREAKING contexts[shop].routes[summary].parameters[limit]: parameter schema changed from " +
                    "{type=integer} to {type=string}",
            )
        routeChanges(route { it.copyWith(request = null) }).assert()
            .containsExactly("BREAKING contexts[shop].routes[summary].request: body removed")
        routeChanges(route { it.copyWith(errors = it.errors + "shop.summary.late") }).assert()
            .containsExactly("ADDITIVE contexts[shop].routes[summary].errors: refusal code shop.summary.late added")
        routeChanges(
            routeBaseline.context { it.copyWith(routes = it.routes + it.routes.single().copyWith(name = "today")) }
        )
            .assert().containsExactly("ADDITIVE contexts[shop].routes[today]: service route added")
        routeChanges(route { it.copyWith(errors = emptyList()) }).assert().containsExactly(
            "ADDITIVE contexts[shop].routes[summary].errors: refusal code shop.summary.closed no longer listed",
        )
    }
}
