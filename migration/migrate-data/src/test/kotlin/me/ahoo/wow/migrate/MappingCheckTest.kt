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
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.copyWith
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
                    EventMapping("order_tagged", to = Lifecycle.TAGS_APPLIED, toRevision = 2),
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
            "example-service.order -> example.order: order_tagged: the system event wow.lifecycle.TagsApplied has " +
                "revision 1 only, not 2",
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
}
