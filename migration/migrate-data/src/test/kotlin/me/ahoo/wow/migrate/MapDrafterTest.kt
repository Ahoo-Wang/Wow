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

package me.ahoo.wow.migrate

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.Ownership
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.SystemCommands
import me.ahoo.wow.migrate.example.NineXExample
import org.junit.jupiter.api.Test

/** The draft of `migration-map.json` from the 9.x store's names and the V10 Manifest (PA-4). */
class MapDrafterTest {
    private fun event(name: String, aliases: List<String> = emptyList(), schema: String = "example.$name") =
        EventManifest(name, aliases = aliases, schema = Manifest.schemaRef(schema))

    private fun command(name: String, mounted: Boolean = false, transformsTo: List<String>? = null) = CommandManifest(
        name,
        schema = Manifest.schemaRef("example.$name"),
        mounted = mounted,
        transformsTo = transformsTo,
    )

    @Suppress("LongParameterList") // An aggregate's parts.
    private fun aggregate(
        name: String,
        events: List<EventManifest> = emptyList(),
        commands: List<CommandManifest> = emptyList(),
        retired: List<String> = emptyList(),
        lifecycle: List<String> = emptyList(),
        ownership: Ownership = Ownership.OWNER,
        context: String = CONTEXT,
    ) = AggregateManifest(
        name,
        state = StateManifest(Manifest.schemaRef("example.${name}State")),
        commands = commands + lifecycle.map { SystemCommands.lifecycle(context, name, it) },
        events = events,
        retired = retired,
        ownership = ownership,
    )

    private fun manifest(vararg contexts: ContextManifest) = Manifest(contexts = contexts.toList())

    private fun context(vararg aggregates: AggregateManifest, name: String = CONTEXT) =
        ContextManifest(name, aggregates = aggregates.toList())

    private fun inventory(
        aggregate: String,
        events: List<String> = emptyList(),
        commands: List<String> = emptyList(),
        context: String = NINE_X_CONTEXT,
        revisions: Map<String, List<String>> = emptyMap(),
        withoutCommand: Long = 0,
    ) = NineXInventory(
        aggregate = NineXAggregate(context, aggregate),
        streams = 1,
        events = events.flatMap { name ->
            revisions[name].orEmpty().ifEmpty { listOf(NineXStream.DEFAULT_REVISION) }.map { NineXEventType(name, it) }
        }.associateWith { 1L },
        commands = commands.associateWith { 1L },
        streamsWithoutCommand = withoutCommand,
    )

    @Test
    fun `snake and kebab names should match the classes' names, the context its name without the service suffix`() {
        val draft = MapDrafter(
            manifest(
                context(
                    aggregate(
                        "order",
                        events = listOf(event("OrderCreated"), event("OrderPaid")),
                        commands = listOf(command("CreateOrder"), command("PayOrder")),
                    ),
                ),
            ),
        ).draft(
            listOf(
                inventory(
                    "order",
                    events = listOf("order_created", "order-paid"),
                    commands = listOf("create_order", "pay-order")
                ),
            ),
        )
        val order = draft.aggregates.single()
        order.from.assert().isEqualTo("example-service.order")
        order.to.assert().isEqualTo("example.order")
        order.events.map { it.from to it.to }.assert()
            .containsExactly("order-paid" to "OrderPaid", "order_created" to "OrderCreated")
        order.commands.map { it.from to it.to }.assert()
            .containsExactly("create_order" to "CreateOrder", "pay-order" to "PayOrder")
        draft.exceptions.assert().isEmpty()
    }

    @Test
    fun `a name should match exactly an alias, a full name or the class's qualified name before its words`() {
        val draft = MapDrafter(
            manifest(
                context(
                    aggregate(
                        "order",
                        events = listOf(
                            event("OrderPlaced", aliases = listOf("order_created")),
                            event("OrderCreated"),
                            event("Paid", schema = "com.example.order.OrderPaid"),
                            event("Shipped"),
                        ),
                    ),
                ),
            ),
        ).draft(
            listOf(
                inventory(
                    "order",
                    events = listOf(
                        "order_created",
                        "com.example.order.OrderPaid",
                        "example.order.Shipped",
                        "order_paid"
                    ),
                ),
            ),
        )
        // order_created is the alias of OrderPlaced, though its words are OrderCreated's; order_paid is the words
        // of the class Paid is declared as.
        draft.aggregates.single().events.associate { it.from to it.to }.assert().isEqualTo(
            mapOf(
                "com.example.order.OrderPaid" to "Paid",
                "example.order.Shipped" to "Shipped",
                "order_created" to "OrderPlaced",
                "order_paid" to "Paid",
            ),
        )
        draft.exceptions.assert()
            .containsExactly(
                "UNSOURCED example.order event OrderCreated: no 9.x event of example-service.order matches it"
            )
    }

    @Test
    fun `a name several V10 names match should be left to a person, as should one none matches`() {
        val draft = MapDrafter(
            manifest(
                context(
                    aggregate(
                        "order",
                        events = listOf(event("OrderPaid"), event("Order_Paid")),
                        commands = listOf(command("PayOrder")),
                    ),
                ),
            ),
        ).draft(
            listOf(inventory("order", events = listOf("order_paid", "order_noted"), commands = listOf("note_order")))
        )
        val order = draft.aggregates.single()
        order.events.assert().isEmpty()
        order.commands.assert().isEmpty()
        draft.exceptions.assert().containsExactly(
            "UNMATCHED example-service.order event order_noted: no V10 event of example.order matches it",
            "AMBIGUOUS example-service.order event order_paid: [OrderPaid, Order_Paid] all match it",
            "UNMATCHED example-service.order command note_order: no V10 command of example.order matches it",
            "UNSOURCED example.order event OrderPaid: no 9.x event of example-service.order matches it",
            "UNSOURCED example.order event Order_Paid: no 9.x event of example-service.order matches it",
            "UNSOURCED example.order command PayOrder: no 9.x command of example-service.order matches it",
        )
    }

    @Test
    fun `an aggregate renamed in V10 should match the one whose events hold most of its own`() {
        val manifest = manifest(
            context(
                aggregate("cart", events = listOf(event("ItemAdded"), event("ItemRemoved"), event("CartCleared"))),
                aggregate("wishlist", events = listOf(event("ItemAdded"))),
            ),
        )
        val draft = MapDrafter(manifest).draft(
            listOf(inventory("basket", events = listOf("item_added", "item_removed", "basket_emptied"))),
        )
        draft.aggregates.single().to.assert().isEqualTo("example.cart")
        draft.aggregates.single().events.map { it.to }.assert().containsExactly("ItemAdded", "ItemRemoved")
        draft.exceptions.assert().containsExactly(
            "RENAMED example-service.basket -> example.cart: matched by 2 of its 3 events; confirm the aggregate " +
                "was renamed",
            "UNMATCHED example-service.basket event basket_emptied: no V10 event of example.cart matches it",
            "UNSOURCED example.cart event CartCleared: no 9.x event of example-service.basket matches it",
            "UNSOURCED example.wishlist: no 9.x aggregate matches it",
        )
    }

    @Test
    fun `a rename two V10 aggregates match alike, or none well enough, should be left to a person`() {
        val manifest = manifest(
            context(
                aggregate("cart", events = listOf(event("ItemAdded"))),
                aggregate("wishlist", events = listOf(event("ItemAdded"))),
                aggregate("payment", events = listOf(event("PaymentMade"))),
            ),
        )
        val draft = MapDrafter(manifest).draft(
            listOf(
                inventory("basket", events = listOf("item_added")),
                inventory("invoice", events = listOf("invoice_issued", "invoice_paid", "payment_made")),
            ),
        )
        draft.aggregates.assert().isEmpty()
        draft.exceptions.assert().containsExactly(
            "AMBIGUOUS example-service.basket: its events match [example.cart, example.wishlist] alike",
            "UNMATCHED example-service.invoice: no V10 aggregate of example matches it",
            "UNSOURCED example.cart: no 9.x aggregate matches it",
            "UNSOURCED example.wishlist: no 9.x aggregate matches it",
            "UNSOURCED example.payment: no 9.x aggregate matches it",
        )
    }

    @Test
    fun `contexts should match by name, without the service suffix, or the Manifest's only one`() {
        val two = manifest(
            context(aggregate("order"), name = "ordering"),
            context(aggregate("payment"), name = "payment"),
        )
        val draft = MapDrafter(two).draft(
            listOf(
                inventory("order", context = "ordering"),
                inventory("payment", context = "payment-service"),
                inventory("stock", context = "inventory-service"),
            ),
        )
        draft.aggregates.map { it.from to it.to }.assert().containsExactly(
            "ordering.order" to "ordering.order",
            "payment-service.payment" to "payment.payment",
        )
        draft.exceptions.assert()
            .containsExactly(
                "UNMATCHED inventory-service.stock: no V10 context matches the 9.x context inventory-service"
            )
        val one = MapDrafter(manifest(context(aggregate("order"), name = "sales"))).draft(listOf(inventory("order")))
        one.aggregates.single().to.assert().isEqualTo("sales.order")
        one.exceptions.assert()
            .containsExactly("REVIEW the 9.x context example-service maps to sales, the Manifest's only context")
    }

    @Test
    fun `the 9x lifecycle and the V10 retired events should map as the check expects`() {
        val manifest = manifest(
            context(
                aggregate(
                    "order",
                    events = listOf(event("OrderCreated")),
                    retired = listOf("OrderArchived"),
                    lifecycle = listOf(Lifecycle.DELETE_AGGREGATE),
                ),
                aggregate("price", lifecycle = listOf(Lifecycle.DELETE_AGGREGATE), ownership = Ownership.TENANT),
            ),
        )
        val draft = MapDrafter(manifest).draft(
            listOf(
                inventory(
                    "order",
                    events = listOf("order_archived", "default_aggregate_deleted", "owner_transferred"),
                    commands = listOf("default_delete_aggregate", "default_recover_aggregate"),
                ),
                inventory("price", events = listOf("owner_transferred")),
            ),
        )
        val order = draft.aggregates.first { it.to == "example.order" }
        order.events.map { listOf(it.from, it.to, it.retire, it.fields) }.assert().containsExactly(
            listOf("default_aggregate_deleted", Lifecycle.AGGREGATE_DELETED, false, emptyMap<String, String>()),
            listOf("order_archived", "OrderArchived", true, emptyMap<String, String>()),
            listOf("owner_transferred", Lifecycle.OWNER_TRANSFERRED, false, mapOf("toOwnerId" to "ownerId")),
        )
        // The restore is not enabled on the V10 order, and a price belongs to its tenant: no owner moves.
        order.commands.map {
            it.from to it.to
        }.assert().containsExactly("default_delete_aggregate" to Lifecycle.DELETE_AGGREGATE)
        draft.exceptions.assert().contains(
            "UNMATCHED example-service.order command default_recover_aggregate: no V10 command of example.order " +
                "matches it",
            "UNMATCHED example-service.price event owner_transferred: no V10 event of example.price matches it",
        )
    }

    @Test
    fun `revisions, streams without a command, mounted commands and PrepareKeys should be left to a person`() {
        val manifest = manifest(
            context(
                aggregate(
                    "order",
                    events = listOf(event("OrderCreated")),
                    commands = listOf(
                        command("PlaceOrder", mounted = true, transformsTo = listOf("CreateOrder")),
                        command("CreateOrder"),
                    ),
                ),
            ),
        )
        val draft = MapDrafter(manifest).draft(
            listOf(
                inventory(
                    "order",
                    events = listOf("order_created"),
                    commands = listOf("create_order", "place_order"),
                    revisions = mapOf("order_created" to listOf("0.0.1", "0.0.2")),
                    withoutCommand = 3,
                ),
            ),
            prepareKeys = mapOf("order_no" to 12L),
        )
        draft.aggregates.single().events.single().from.assert().isEqualTo("order_created")
        draft.exceptions.assert().containsExactly(
            "REVIEW example-service.order event order_created: the store holds revisions [0.0.1, 0.0.2], which one " +
                "mapping covers; split it by fromRevision if their shapes differ",
            "UNMATCHED example-service.order command place_order: it is mounted in V10 as PlaceOrder; map it to the " +
                "command it turns into, one of [CreateOrder]",
            "REVIEW example-service.order: 3 streams record no command; set defaultCommand to the V10 command they " +
                "stand for",
            "UNMATCHED PrepareKey order_no (12 keys): write its reservation mapping (namespace, tenancy, ownerId) " +
                "under the aggregate whose instances its values name",
        )
    }

    @Test
    fun `the draft should read back as the map it holds, and the 9x example's draft should pass the check`() {
        val inventories = InMemoryNineXEventSource(NineXExample.streams()).inventory()
        val draft = MapDrafter(NineXExample.manifest).draft(inventories)
        MigrationMap.read(draft.render()).assert().isEqualTo(draft.map)
        draft.aggregates.map { it.from to it.to }.assert().containsExactly(
            "example-service.cart" to "example.cart",
            "example-service.order" to "example.order",
        )
        draft.exceptions.all { it.startsWith("UNSOURCED ") }.assert().describedAs(draft.exceptions.toString()).isTrue()
        val report = MappingCheck(MigrationPlan(draft.map, NineXExample.manifest)).check(inventories)
        report.problems.assert().isEmpty()
        report.warnings.filter { it.startsWith("the draft's exception") }.size.assert().isEqualTo(draft.exceptions.size)
    }

    private companion object {
        const val CONTEXT = "example"
        const val NINE_X_CONTEXT = "example-service"
    }
}
