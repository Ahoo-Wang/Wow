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
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.migrate.example.NineXCompensationExample
import me.ahoo.wow.migrate.example.NineXExample
import org.junit.jupiter.api.Test

class MappingCheckTest {
    private val order = NineXAggregate("example-service", "order")
    private val example = InMemoryNineXEventSource(NineXExample.streams()).inventory()

    private fun check(
        map: MigrationMap = NineXExample.map,
        inventories: List<NineXInventory> = example,
        manifest: Manifest = NineXExample.manifest,
    ): MappingReport = MappingCheck(MigrationPlan(map, manifest)).check(inventories)

    private fun MigrationMap.order(change: AggregateMapping.() -> AggregateMapping): MigrationMap =
        copy(aggregates = aggregates.map { if (it.source() == order) it.change() else it })

    @Test
    fun `the example's map passes against its store and Manifest`() {
        val report = check()
        report.passed.assert().isTrue()
        report.warnings.assert().containsExactly(
            "example-service.order: no stream holds the 9.x event order_received@*",
            "example-service.order: no stream records the 9.x command receipt_order",
        )
        report.render().assert().contains("example-service.order: 4 streams, 4 events of 4 types")
            .contains("Mapping check passed.")
    }

    @Test
    fun `an unmapped 9x aggregate, event or command fails it`() {
        val unmapped = example + NineXInventory(NineXAggregate("example-service", "payment"), 3, emptyMap(), emptyMap())
        val withEvent = unmapped.map {
            if (it.aggregate == order) {
                it.copy(
                    events = it.events + (NineXEventType("order_archived", "0.0.1") to 2L),
                    commands = it.commands + ("archive_order" to 2L),
                    streamsWithoutCommand = 1,
                )
            } else {
                it
            }
        }
        val report = check(inventories = withEvent)
        report.passed.assert().isFalse()
        report.problems.assert().containsExactlyInAnyOrder(
            "the 9.x aggregate example-service.payment (3 streams) is not mapped",
            "example-service.order: the 9.x event order_archived@0.0.1 (2 events) has no mapping",
            "example-service.order: the 9.x command archive_order (2 streams) has no mapping",
            "example-service.order: 1 streams record no command and there is no defaultCommand",
        )
        report.render().assert().contains("PROBLEM").contains("Mapping check failed: 4 problems.")
    }

    @Test
    fun `a default command covers streams without one, and an empty store warns`() {
        val inventories = example.map { if (it.aggregate == order) it.copy(streamsWithoutCommand = 1) else it }
        check(NineXExample.map.order { copy(defaultCommand = "PayOrder") }, inventories).passed.assert().isTrue()
        val empty = check(inventories = emptyList())
        empty.warnings.assert().contains("the 9.x store holds no stream of example-service.order")
        empty.render().assert().contains("no event streams")
    }

    @Test
    fun `a revision-specific mapping is found before one of any revision`() {
        val map = NineXExample.map.order {
            copy(events = events + EventMapping("order_created", "1.0.0", "OrderCreated", toRevision = 2))
        }
        val inventories = example.map {
            if (it.aggregate == order) {
                it.copy(
                    events = it.events + (NineXEventType("order_created", "1.0.0") to 1L)
                )
            } else {
                it
            }
        }
        check(map, inventories).passed.assert().isTrue()
        val plan = MigrationPlan(map, NineXExample.manifest).of(order)
        plan.eventOf("order_created", "1.0.0")!!.toRevision.assert().isEqualTo(2)
        plan.eventOf("order_created", "0.0.1")!!.toRevision.assert().isEqualTo(1)
        plan.eventOf("order_paid", "9.9.9")!!.to.assert().isEqualTo("OrderPaid")
        plan.eventOf("unknown", "0.0.1").assert().isNull()
    }

    @Test
    fun `every target must be in the Manifest`() {
        val map = NineXExample.map.order {
            copy(
                commands = commands + CommandMapping("cancel_order", "CancelOrder") +
                    CommandMapping("delete_order", Lifecycle.DELETE_AGGREGATE) +
                    CommandMapping("recover_order", Lifecycle.RESTORE_AGGREGATE),
                events = events + EventMapping("order_cancelled", to = "OrderCancelled") +
                    EventMapping("order_paid", "2.0.0", "OrderPaid", toRevision = 2) +
                    EventMapping("order_deleted", to = Lifecycle.AGGREGATE_DELETED) +
                    EventMapping("order_moved", to = "wow.lifecycle.Moved") +
                    EventMapping("order_transferred", to = Lifecycle.OWNER_TRANSFERRED, toRevision = 2),
                defaultCommand = "Missing",
            )
        }
        check(map).problems.assert().containsExactlyInAnyOrder(
            "example-service.order -> example.order: the V10 command example.order.CancelOrder is not in the Manifest",
            "example-service.order -> example.order: the lifecycle command wow.lifecycle.RestoreAggregate is not " +
                "enabled on example.order",
            "example-service.order -> example.order: the V10 command example.order.Missing is not in the Manifest",
            "example-service.order -> example.order: order_cancelled: the V10 event example.order.OrderCancelled is " +
                "not in the Manifest",
            "example-service.order -> example.order: order_paid: the V10 event example.order.OrderPaid is at " +
                "revision 1 in the Manifest, below the mapped revision 2",
            "example-service.order -> example.order: order_moved: the V10 event wow.lifecycle.Moved is not a system " +
                "event",
            "example-service.order -> example.order: order_transferred: the system event " +
                "wow.lifecycle.OwnerTransferred has revision 1 only, not 2",
        )
    }

    @Test
    fun `a lifecycle event an aggregate of its tenant cannot hold fails it`() {
        val handedOver = LifecycleMapping(Lifecycle.OWNER_TRANSFERRED, mapOf("toOwnerId" to "ownerId"))
        val map = NineXExample.map.order {
            copy(
                events = events + EventMapping("order_handed_over", to = "OrderCreated", lifecycle = handedOver) +
                    EventMapping("order_cancelled", to = "OrderCreated", lifecycle = LifecycleMapping(Lifecycle.AGGREGATE_DELETED)),
            )
        }
        check(map).problems.assert().isEmpty()
        val tenantOwned = NineXExample.manifest.let { manifest ->
            manifest.copyWith(
                contexts = manifest.contexts.map { context ->
                    context.copyWith(aggregates = context.aggregates.map { it.copyWith(ownership = Ownership.TENANT) })
                },
            )
        }
        check(map, manifest = tenantOwned).problems.assert().containsExactly(
            "example-service.order -> example.order: order_handed_over: writes wow.lifecycle.OwnerTransferred, but the " +
                "V10 aggregate belongs to its tenant",
        )
    }

    @Test
    fun `aliases in the Manifest resolve`() {
        val manifest = NineXExample.manifest.let { manifest ->
            manifest.copyWith(
                contexts = manifest.contexts.map { context ->
                    context.copyWith(
                        aggregates = context.aggregates.map { aggregate ->
                            aggregate.copyWith(
                                commands = aggregate.commands.map { it.copyWith(aliases = listOf("Old${it.name}")) },
                                events = aggregate.events.map {
                                    it.copyWith(
                                        aliases = listOf("example.order.Old${it.name}")
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }
        val map = NineXExample.map.order {
            copy(
                commands = commands + CommandMapping("old_pay", "OldPayOrder"),
                events = events + EventMapping("old_paid", to = "OldOrderPaid"),
            )
        }
        check(map, manifest = manifest).passed.assert().isTrue()
    }

    @Test
    fun `a mapping to an event the aggregate retired resolves`() {
        val manifest = NineXExample.manifest.let { manifest ->
            manifest.copyWith(
                contexts = manifest.contexts.map { context ->
                    context.copyWith(
                        aggregates = context.aggregates.map {
                            it.copyWith(retired = listOf("OrderArchived", "legacy.order.Closed"))
                        },
                    )
                },
            )
        }
        val map = NineXExample.map.order {
            copy(
                events = events + EventMapping("order_archived", to = "OrderArchived") +
                    EventMapping("order_closed", to = "legacy.order.Closed") +
                    EventMapping("order_lost", to = "OrderLost"),
            )
        }
        check(map, manifest = manifest).problems.assert().containsExactly(
            "example-service.order -> example.order: order_lost: the V10 event example.order.OrderLost is not in " +
                "the Manifest",
        )
    }

    private val archiving: List<NineXInventory>
        get() = InMemoryNineXEventSource(
            NineXExample.streams() +
                NineXStream.parse(NineXExample.archived(NineXExample.resource("order-stream-v4.json"))),
        ).inventory()

    @Test
    fun `a stream of retired events only passes when the aggregate retired the name`() {
        val retire = MigrationMap.read(NineXExample.archivingMapText(""""to": "OrderArchived", "retire": true"""))
        check(retire, archiving, NineXExample.archivingManifest()).problems.assert().isEmpty()
        check(retire, archiving, NineXExample.archivingManifest(retired = false)).problems.assert().containsExactly(
            "example-service.order -> example.order: order_archived: example.order.OrderArchived is retired by the " +
                "map but not by example.order: add it to @AggregateRoot(retiredEvents)",
        )
    }

    @Test
    fun `streams that hold only dropped events fail it before the import`() {
        val drop = MigrationMap.read(NineXExample.archivingMapText(""""drop": true"""))
        val report = check(drop, archiving, NineXExample.archivingManifest())
        report.problems.assert().containsExactly(
            "example-service.order: 1 streams hold only the dropped events [order_archived@0.0.1], so their " +
                "versions would be empty: map them with retire to a name in the aggregate's retiredEvents",
        )
        // A dropped event beside one that is kept leaves the stream its version.
        val mixed = archiving.map { inventory ->
            inventory.copy(
                streamEventTypes = inventory.streamEventTypes.mapKeys { (types, _) ->
                    if (NineXEventType("order_archived", "0.0.1") in types) {
                        types + NineXEventType("order_shipped", "0.0.1")
                    } else {
                        types
                    }
                },
            )
        }
        check(drop, mixed, NineXExample.archivingManifest()).passed.assert().isTrue()
    }

    @Test
    fun `every 9x PrepareKey the store holds is mapped once`() {
        val mapping = ReservationMapping("order_no", "order.no", ownerId = "orderId")
        val map = NineXExample.map.order { copy(reservations = listOf(mapping, mapping.copy(from = "order_code"))) }
        val report = MappingCheck(MigrationPlan(map, NineXExample.manifest))
            .check(example, mapOf("order_no" to 3L, "user_name" to 2L))
        report.problems.assert().containsExactly(
            "the 9.x PrepareKey user_name (2 keys) has no reservation mapping: its keys would be free in V10",
        )
        report.warnings.assert().contains("the 9.x store holds no PrepareKey order_code")
        report.render().assert().contains("  PrepareKey order_no: 3 keys")
        val twice = NineXExample.map.order { copy(reservations = listOf(mapping, mapping)) }
        MappingCheck(MigrationPlan(twice, NineXExample.manifest)).check(example, mapOf("order_no" to 3L))
            .problems.assert().containsExactly("the 9.x PrepareKey order_no is mapped twice")
    }

    @Test
    fun `deleted 9x instances of an aggregate whose map deletes none should warn, and not when it does`() {
        val counted = mutableListOf<NineXAggregate>()
        val deleted = { aggregate: NineXAggregate ->
            counted += aggregate
            if (aggregate == order) 2L else 0L
        }
        val report = MappingCheck(NineXExample.plan).check(example, deleted = deleted)
        report.passed.assert().isTrue()
        report.warnings.filter { "mark their instance deleted" in it }.assert().containsExactly(
            "example-service.order: 2 9.x snapshots mark their instance deleted, but no event of the map deletes in " +
                "V10, so V10 holds them alive: map the 9.x event that deleted them with \"lifecycle\": " +
                "{ \"to\": \"wow.lifecycle.AggregateDeleted\" }, or to wow.lifecycle.AggregateDeleted when its " +
                "class is gone",
        )
        counted.assert().containsExactlyInAnyOrder(order, NineXAggregate("example-service", "cart"))
        // A map that deletes the order does not count its deleted snapshots.
        counted.clear()
        listOf(
            EventMapping("order_deleted", to = Lifecycle.AGGREGATE_DELETED),
            EventMapping(
                "order_removed",
                to = "OrderRemoved",
                lifecycle = LifecycleMapping(Lifecycle.AGGREGATE_DELETED),
            ),
        ).forEach { deletion ->
            val deleting = NineXExample.map.order { copy(events = events + deletion) }
            MappingCheck(MigrationPlan(deleting, NineXExample.manifest)).check(example, deleted = deleted).warnings
                .filter { "mark their instance deleted" in it }.assert().isEmpty()
        }
        counted.assert().doesNotContain(order)
    }

    @Test
    fun `a collection the tail has no index on should warn with the command that creates it, not fail`() {
        val report = MappingCheck(NineXExample.plan).check(
            example,
            commitTimeIndexes = mapOf("cart_event_stream" to "db.getCollection(\"cart_event_stream\").createIndex(…)"),
        )
        report.passed.assert().isTrue()
        report.warnings.filter { "(createTime, _id)" in it }.assert().containsExactly(
            "the 9.x collection cart_event_stream has no (createTime, _id) index, which the tail reads it by and " +
                "refuses to start without: create it on the 9.x side before the cut-over, " +
                "db.getCollection(\"cart_event_stream\").createIndex(…), or set \"source\": { \"createCommitTimeIndex\": " +
                "true } to let the tail create it, a write to the 9.x store",
        )
    }

    @Test
    fun `a collection without the index the import walks it in should fail with the command that creates it`() {
        val report = MappingCheck(NineXExample.plan).check(
            example,
            walkIndexes = mapOf("cart_event_stream" to "db.getCollection(\"cart_event_stream\").createIndex(…)"),
        )
        report.passed.assert().isFalse()
        report.problems.assert().containsExactly(
            "the 9.x collection cart_event_stream has no (aggregateId, version) index, which 9.x creates and the " +
                "import walks it in: create it on the 9.x side, db.getCollection(\"cart_event_stream\").createIndex(…)",
        )
    }

    @Test
    fun `an excluded 9x aggregate or PrepareKey should warn with its reason instead of failing`() {
        val payment = NineXInventory(NineXAggregate("example-service", "payment"), 3, emptyMap(), emptyMap())
        val map = NineXExample.map.copy(
            excluded = listOf(
                Exclusion(aggregate = "example-service.payment", reason = "Another V10 store takes it"),
                Exclusion(prepareKey = "payment_no", reason = "The payment's, with it"),
            ),
        )
        val report = MappingCheck(MigrationPlan(map, NineXExample.manifest))
            .check(example + payment, mapOf("payment_no" to 2L))
        report.passed.assert().isTrue()
        report.warnings.assert().contains(
            "the 9.x aggregate example-service.payment (3 streams) is excluded: Another V10 store takes it",
            "the 9.x PrepareKey payment_no (2 keys) is excluded: The payment's, with it",
        )
    }

    @Test
    fun `an entry both mapped and excluded, or excluded twice, should fail, and one the store lacks warn`() {
        val mapping = ReservationMapping("order_no", "order.no", ownerId = "orderId")
        val map = NineXExample.map.order { copy(reservations = listOf(mapping)) }.copy(
            excluded = listOf(
                Exclusion(aggregate = "example-service.order", reason = "Mapped too"),
                Exclusion(prepareKey = "order_no", reason = "Mapped too"),
                Exclusion(aggregate = "example-service.archive", reason = "Gone"),
                Exclusion(aggregate = "example-service.archive", reason = "Gone, twice"),
                Exclusion(prepareKey = "archive_no", reason = "Gone"),
            ),
        )
        val report = MappingCheck(MigrationPlan(map, NineXExample.manifest)).check(example, mapOf("order_no" to 3L))
        report.problems.assert().containsExactly(
            "the 9.x aggregate example-service.archive is excluded twice",
            "the 9.x aggregate example-service.order is both mapped and excluded",
            "the 9.x PrepareKey order_no is both mapped and excluded",
        )
        report.warnings.assert().contains(
            "the 9.x store holds no aggregate example-service.archive, which the map excludes",
            "the 9.x store holds no PrepareKey archive_no, which the map excludes",
        )
    }

    @Test
    fun `an instance whose id V10 refuses should fail it, each listed by the name the map excludes it by`() {
        val refused = mapOf(
            NineXAggregateId("", "t2") to 1L,
            NineXAggregateId("", "t1") to 2L,
            NineXAggregateId(" ", "t1") to 1L,
            NineXAggregateId(AggregateRef.UNASSIGNED, "t1") to 1L,
        )
        val inventories = example.map { if (it.aggregate == order) it.copy(refusedIds = refused) else it }
        check(inventories = inventories).problems.assert().containsExactly(
            "example-service.order: V10 refuses these 9.x aggregate ids, so the streams of their instances cannot " +
                "be imported; " +
                "exclude each with {\"aggregate\": \"example-service.order\", \"stream\": <the id as listed>, " +
                "\"reason\": \"…\"}: \"(empty)\" (empty: 3 streams of the tenants [t1, t2]), \" \" (blank: 1 streams " +
                "of the tenants [t1]), \"(none)\" (the id V10 gives a ref admission has not given one yet: 1 streams of " +
                "the tenants [t1])",
        )
        // Each excluded, by the name the problem lists it by, warns with its reason instead.
        val map = NineXExample.map.copy(
            excluded = listOf(RefusedIds.EMPTY, " ", AggregateRef.UNASSIGNED).map {
                Exclusion(aggregate = "example-service.order", reason = "A 9.x defect", stream = it)
            },
        )
        val report = check(map, inventories)
        report.passed.assert().isTrue()
        report.warnings.assert().contains(
            "the 9.x stream example-service.order \"(empty)\" of the tenants [t1, t2] is excluded: A 9.x defect",
            "the 9.x stream example-service.order \" \" of the tenants [t1] is excluded: A 9.x defect",
            "the 9.x stream example-service.order \"(none)\" of the tenants [t1] is excluded: A 9.x defect",
        )
    }

    @Test
    fun `an instance whose streams record no tenant should fail it, and warn once excluded`() {
        val inventories = example.map {
            if (it.aggregate == order) it.copy(tenantless = mapOf("o-2" to 1L, "o-1" to 2L)) else it
        }
        check(inventories = inventories).problems.assert().containsExactly(
            "example-service.order: streams of these 9.x aggregate ids record no tenant, which V10 refuses, so they " +
                "cannot be imported; exclude each with {\"aggregate\": \"example-service.order\", \"stream\": <the id " +
                "as listed>, \"reason\": \"…\"}: \"o-1\" (2 streams), \"o-2\" (1 streams)",
        )
        val map = NineXExample.map.copy(
            excluded = listOf("o-1", "o-2").map {
                Exclusion(aggregate = "example-service.order", reason = "A 9.x defect", stream = it)
            },
        )
        val report = MappingCheck(MigrationPlan(map, NineXExample.manifest))
            .check(inventories, tenantsOf = { _, _ -> listOf("") })
        report.passed.assert().isTrue()
        report.warnings.assert().contains(
            "the 9.x stream example-service.order \"o-1\" of the tenants [(blank)] is excluded: A 9.x defect",
        )
    }

    @Test
    fun `an excluded stream should warn, whether the store holds it or not, and one excluded twice fail`() {
        val map = NineXExample.map.copy(
            excluded = listOf(
                Exclusion(aggregate = "example-service.order", reason = "Corrupt", stream = "o-1"),
                Exclusion(aggregate = "example-service.order", reason = "Corrupt, twice", stream = "o-1"),
                Exclusion(aggregate = "example-service.order", reason = "Gone", stream = "o-9"),
                Exclusion(aggregate = "example-service.order", reason = "No id", stream = RefusedIds.EMPTY),
                Exclusion(aggregate = "example-service.payment", reason = "Not imported", stream = "p-1"),
            ),
        )
        val held = mapOf((order to "o-1") to listOf("t2", "t1", "t2"))
        val report = MappingCheck(MigrationPlan(map, NineXExample.manifest))
            .check(example, tenantsOf = { aggregate, id -> held[aggregate to id].orEmpty() })
        report.problems.assert().containsExactly("the 9.x stream example-service.order \"o-1\" is excluded twice")
        report.warnings.assert().contains(
            "the 9.x stream example-service.order \"o-1\" of the tenants [t1, t2] is excluded: Corrupt",
            "the 9.x store holds no stream example-service.order \"o-9\", which the map excludes",
            "the 9.x store holds no stream example-service.order \"(empty)\", which the map excludes",
            "the 9.x stream example-service.payment \"p-1\" is excluded, but the map does not import " +
                "example-service.payment",
        )
    }

    @Test
    fun `a target aggregate missing from the Manifest, duplicates and shared targets fail it`() {
        val map = NineXExample.map.order {
            copy(events = events + events.first(), commands = commands + commands.first())
        }.let { map ->
            map.copy(
                aggregates = map.aggregates + AggregateMapping("example-service.legacy-order", "example.order") +
                    AggregateMapping("example-service.payment", "example.payment"),
            )
        }
        check(map).problems.assert().containsExactlyInAnyOrder(
            "[example-service.order, example-service.legacy-order] all map to example.order: their ids may collide",
            "example-service.order -> example.order: the 9.x event order_created@0.0.1 is mapped twice",
            "example-service.order -> example.order: the 9.x command create_order is mapped twice",
            "example-service.payment -> example.payment: the V10 aggregate example.payment is not in the Manifest",
        )
    }

    @Test
    fun `the plan names its contexts and refuses an unmapped aggregate`() {
        NineXExample.plan.contexts.assert().containsExactly("example")
        org.junit.jupiter.api.assertThrows<MigrationException> {
            NineXExample.plan.of(NineXAggregate("example-service", "payment"))
        }
        NineXExample.plan.of(order).ownership.assert().isEqualTo(me.ahoo.wow.api.aggregate.Ownership.OWNER)
    }

    private val compensation = InMemoryNineXCompensationSource(NineXCompensationExample.executions()).inventory()

    private fun checkCompensation(
        map: MigrationMap = NineXCompensationExample.map,
        inventory: NineXCompensationInventory? = compensation,
    ): MappingReport =
        MappingCheck(MigrationPlan(map, NineXCompensationExample.manifest)).check(example, compensation = inventory)

    @Test
    fun `every 9x processor function with unresolved executions maps to a V10 subscription`() {
        val report = checkCompensation()
        report.passed.assert().isTrue()
        report.warnings.assert().contains(
            "1 unresolved executions of example-service.OrderProjector.onOrderPaid on example-service.order will " +
                "not be imported: the V10 subscription OrderProjector.onOrderPaid of reporting does not read the log " +
                "of example",
        )
        report.render().assert()
            .contains("  9.x compensation: 5 unresolved executions of 4 functions, 2 resolved (history, not imported)")

        // Without a mapping, a function's failures would be lost: the check fails.
        val unmapped = MigrationMap.read(NineXCompensationExample.mapText("""{ "from": "example-service.CartSaga" }"""))
        checkCompensation(unmapped).problems.assert().containsExactlyInAnyOrder(
            "the 9.x processor function example-service.OrderProjector.onOrderPaid (1 unresolved executions) has no " +
                "subscription mapping: its failures would be lost",
            "the 9.x processor function example-service.OrderSaga.onEvent (1 unresolved executions) has no " +
                "subscription mapping: its failures would be lost",
            "the 9.x processor function example-service.OrderSaga.onStateEvent (1 unresolved executions) has no " +
                "subscription mapping: its failures would be lost",
        )
    }

    @Test
    fun `a subscription missing from the Manifest or a processor mapped twice fails it, an unused mapping warns`() {
        val map = MigrationMap.read(
            NineXCompensationExample.mapText(
                NineXCompensationExample.SUBSCRIPTIONS + """,
                { "from": "example-service.CartSaga", "to": "BasketSaga" },
                { "from": "example-service.PaymentSaga", "functions": { "onPaid": "onOrderPaid" } },
                { "from": "example-service.ShippingSaga" }""",
            ),
        )
        val report = checkCompensation(map)
        report.problems.assert().containsExactlyInAnyOrder(
            "the 9.x processor example-service.CartSaga is mapped twice",
            "example-service.PaymentSaga: the V10 subscription PaymentSaga.onOrderPaid is not in the Manifest",
            "the 9.x processor function example-service.CartSaga.onOrderCreated (2 unresolved executions) maps to " +
                "the V10 subscription BasketSaga.onOrderCreated, which is not in the Manifest",
        )
        report.warnings.assert()
            .contains("no unresolved 9.x execution is of the processor example-service.PaymentSaga")
            .contains("no unresolved 9.x execution is of the processor example-service.ShippingSaga")

        // A mapped function without unresolved executions warns; another service's executions are not this run's.
        val functions = MigrationMap.read(
            NineXCompensationExample.mapText(
                NineXCompensationExample.SUBSCRIPTIONS.replace(
                    """"onStateEvent": "onOrderState"""",
                    """"onStateEvent": "onOrderState", "onOrderPaid": "onOrderCreated"""",
                ),
            ),
        )
        val other = compensation.copy(
            unresolved = compensation.unresolved + NineXFailureGroup(
                NineXFunction("payment-service", "PaymentSaga", "onPaid"),
                NineXAggregate("payment-service", "payment"),
                4,
            ),
        )
        checkCompensation(functions, other).also {
            it.passed.assert().isTrue()
            it.warnings.assert().contains(
                "no unresolved 9.x execution is of the processor function example-service.OrderSaga.onOrderPaid",
            )
        }
    }

    @Test
    fun `executions on an aggregate the map does not import warn, and mappings without a database are unused`() {
        val payment = compensation.copy(
            unresolved = listOf(
                NineXFailureGroup(
                    NineXFunction("example-service", "CartSaga", "onOrderCreated"),
                    NineXAggregate("example-service", "payment"),
                    2,
                ),
            ),
        )
        checkCompensation(inventory = payment).warnings.assert().contains(
            "2 unresolved executions of example-service.CartSaga.onOrderCreated on example-service.payment will not " +
                "be imported: this map does not import the 9.x aggregate example-service.payment",
        )
        checkCompensation(inventory = null).also {
            it.passed.assert().isTrue()
            it.warnings.assert()
                .contains("the configuration names no 9.x compensation database: the subscription mappings are unused")
            it.render().assert().doesNotContain("9.x compensation:")
        }
        check().warnings.assert().noneMatch { it.contains("compensation") }
    }
}
