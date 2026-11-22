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
import me.ahoo.wow.api.aggregate.AggregateIdSource
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.kernel.scopeOf
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StreamConverterTest {
    private val order = NineXAggregate("example-service", "order")
    private val created = NineXExample.stream("order-stream-v1.json")

    private fun converter(
        map: MigrationMap = NineXExample.map,
        ownership: Ownership = Ownership.OWNER,
    ): StreamConverter {
        val manifest = NineXExample.manifest.let { manifest ->
            manifest.copyWith(
                contexts = manifest.contexts.map { context ->
                    context.copyWith(aggregates = context.aggregates.map { it.copyWith(ownership = ownership) })
                },
            )
        }
        return StreamConverter(MigrationPlan(map, manifest)) { NineXExample.IMPORTED_AT }
    }

    private fun MigrationMap.order(change: AggregateMapping.() -> AggregateMapping): MigrationMap =
        copy(aggregates = aggregates.map { if (it.source() == order) it.change() else it })

    private fun event(name: String, body: String, revision: String = "0.0.1") =
        NineXEvent("e-$name", name, revision, WowJson.mapper.readTree(body))

    @Test
    fun `a stream that records no tenant should fail, naming the entry that excludes it`() {
        val tenantless = created.copy(aggregateId = created.aggregateId.copy(tenantId = ""))
        assertThrows<MigrationException> { converter().convert(tenantless) }.message.assert().endsWith(
            "it records no tenant, which V10 refuses; exclude it with {\"aggregate\": \"example-service.order\", " +
                "\"stream\": \"0VY3GwaK4OmV00E\", \"reason\": \"…\"}.",
        )
    }

    @Test
    fun `a stream of an aggregate whose id is its owner's fails unless it records its id as its owner (#6560)`() {
        val converter = StreamConverter(MigrationPlan(NineXExample.map, NineXExample.ownedCartManifest)) {
            NineXExample.IMPORTED_AT
        }
        val added = NineXExample.stream(NineXExample.CART_STREAMS.first())
        val id = added.aggregateId.id
        val exclusion = "set its ownerId to its aggregate id in the 9.x store, or exclude it with {\"aggregate\": " +
            "\"example-service.cart\", \"stream\": \"$id\", \"reason\": \"…\"}."
        assertThrows<MigrationException> { converter.convert(added) }.message.assert()
            .endsWith("example.cart's id is its owner's, but the stream records the owner \"customer-1\"; $exclusion")
        // A stream that records none may also take its id, by the map.
        assertThrows<MigrationException> { converter.convert(added.copy(ownerId = " ")) }.message.assert().endsWith(
            "example.cart's id is its owner's, but the stream records no owner; " +
                exclusion.replace("9.x store,", "9.x store,$MISSING_OWNER_WAY"),
        )
        converter.convert(added.copy(ownerId = id)).scope.assert().isEqualTo(Scope(ownerId = id))
    }

    @Test
    fun `a stream that records no owner takes its id as V10 records it, when the map fills a missing owner`() {
        val plan = MigrationPlan(MigrationMap.read(NineXExample.fillingMapText()), NineXExample.ownedCartManifest)
        val converter = StreamConverter(plan) { NineXExample.IMPORTED_AT }
        val unowned = NineXExample.cartAs("cart-n").first().copy(spaceId = "space-1")
        val record = converter.convert(unowned)
        // The kernel's own rule, never a copy of it: the id for the owner, the space as recorded.
        record.scope.assert().isEqualTo(AggregateIdSource.OWNER.scopeOf("cart-n", Scope(spaceId = "space-1")))
        record.scope.assert().isEqualTo(Scope("cart-n", "space-1"))
        record.headers[FILLED_OWNER_HEADER].assert().isEqualTo("aggregate-id")
        // A stream that records its id, or another owner, is not filled: the one is taken as it is, the other refused.
        converter.convert(unowned.copy(ownerId = "cart-n")).headers.assert().doesNotContainKey(FILLED_OWNER_HEADER)
        assertThrows<MigrationException> { converter.convert(unowned.copy(ownerId = "customer-2")) }.message.assert()
            .contains("but the stream records the owner \"customer-2\"").doesNotContain("missingOwner")
        // On an aggregate whose id is not its owner's, the key fills nothing (the check refuses it).
        val unfilled = StreamConverter(MigrationPlan(MigrationMap.read(NineXExample.fillingMapText()), NineXExample.manifest)) {
            NineXExample.IMPORTED_AT
        }.convert(unowned)
        unfilled.scope.assert().isEqualTo(Scope(spaceId = "space-1"))
        unfilled.headers.assert().doesNotContainKey(FILLED_OWNER_HEADER)
    }

    @Test
    fun `a stream of a type whose tenant is fixed fails unless it is in that tenant`() {
        val converter = StreamConverter(MigrationPlan(NineXExample.map, NineXExample.manifest, mapping("acme"))) {
            NineXExample.IMPORTED_AT
        }
        val added = NineXExample.stream(NineXExample.CART_STREAMS.first())
        assertThrows<MigrationException> { converter.convert(added) }.message.assert().endsWith(
            "the tenant of example.cart is fixed to \"acme\" (tenants), but the stream is in \"(0)\", where V10 serves " +
                "it to no caller; fix the tenant to the one it is in, in the host and in tenants, or exclude it with " +
                "{\"aggregate\": \"example-service.cart\", \"stream\": \"${added.aggregateId.id}\", \"reason\": \"…\"}.",
        )
        val inAcme = added.copy(aggregateId = added.aggregateId.copy(tenantId = "acme"))
        converter.convert(inAcme).ref.tenantId.assert().isEqualTo("acme")
        // A type it does not fix takes the stream's tenant.
        converter.convert(created).ref.tenantId.assert().isEqualTo("(0)")
    }

    private fun mapping(tenant: String) = mapOf("example.cart" to tenant)

    @Test
    fun `a 9x stream converts with its identity, scope, operator and causation`() {
        val record = converter().convert(created)
        record.ref.assert().isEqualTo(AggregateType("example", "order").ref("(0)", "0VY3GwaK4OmV00E"))
        record.version.assert().isEqualTo(1)
        record.commandId.assert().isEqualTo("0VY3Gwaw4OmV00F")
        record.requestId.assert().isEqualTo("0VY3Gwaw4OmV00F")
        record.commandType.assert().isEqualTo("example.order.CreateOrder")
        record.commitTime.assert().isEqualTo(1792004186903)
        record.scope.assert().isEqualTo(Scope("customer-1"))
        record.operator.assert().isEqualTo(Operator("user-1", "(0)"))
        record.causation.assert().isEqualTo(Causation("0VY3Gwaw4OmV00F", "0VY3Gwaw4OmV00F"))
        // When it occurred is 9.x's, and when it was written the import's.
        record.headers.assert().isEqualTo(mapOf(EventStreamRecord.WRITTEN_AT to "${NineXExample.IMPORTED_AT}"))
        record.writtenAt.assert().isEqualTo(NineXExample.IMPORTED_AT)
        val event = record.events.single()
        event.name.assert().isEqualTo("example.order.OrderCreated")
        event.revision.assert().isEqualTo(1)
        WowJson.mapper.readTree(event.payload).get("orderId").stringValue().assert().isEqualTo("0VY3GwaK4OmV00E")
        event.payload.assert().contains("\"price\":12.50")
    }

    @Test
    fun `a derived command's stream is correlated to its chain's root`() {
        val derived = created.copy(header = created.header + (NineXHeaders.TRACE_ID to "root"))
        converter().convert(derived).causation.assert().isEqualTo(Causation("root", "root"))
        val blank = created.copy(header = created.header + (NineXHeaders.TRACE_ID to " "), requestId = "")
        converter().convert(blank).also {
            it.causation.assert().isEqualTo(Causation(created.commandId, created.commandId))
            it.requestId.assert().isEqualTo(created.commandId)
        }
    }

    @Test
    fun `only the headers the map lists are kept, renamed`() {
        val stream = created.copy(
            header = created.header + mapOf("app_id" to "web", "remote_ip" to "10.0.0.1", "command_wait_stage" to "x"),
        )
        converter(NineXExample.map.copy(headers = mapOf("app_id" to "app", "device_id" to "device")))
            .convert(stream).headers.assert()
            .isEqualTo(mapOf("app" to "web", EventStreamRecord.WRITTEN_AT to "${NineXExample.IMPORTED_AT}"))
    }

    @Test
    fun `a stream written when it occurred keeps no written-at header`() {
        StreamConverter(NineXExample.plan) { created.createTime }.convert(created).headers.assert().isEmpty()
    }

    @Test
    fun `a tenant-owned aggregate records no owner, and a space is kept`() {
        val spaced = created.copy(spaceId = "space-1")
        converter(ownership = Ownership.TENANT).convert(spaced).scope.assert().isEqualTo(Scope(spaceId = "space-1"))
    }

    @Test
    fun `an owner or space transfer moves the stream's scope, with renamed fields`() {
        val map = NineXExample.map.order {
            copy(
                events = events +
                    EventMapping("owner_transferred", to = Lifecycle.OWNER_TRANSFERRED, fields = mapOf("toOwnerId" to "ownerId")) +
                    EventMapping("space_transferred", to = Lifecycle.SPACE_TRANSFERRED, fields = mapOf("toSpaceId" to "spaceId")),
            )
        }
        val transfer = created.copy(
            events = listOf(
                event("owner_transferred", """{"toOwnerId":"customer-2","note":1}"""),
                event("space_transferred", """{"toSpaceId":"space-2"}"""),
            ),
        )
        val record = converter(map).convert(transfer)
        record.scope.assert().isEqualTo(Scope("customer-2", "space-2"))
        record.events.first().assert().isEqualTo(
            EventRecord(Lifecycle.OWNER_TRANSFERRED, 1, """{"ownerId":"customer-2","note":1}"""),
        )
        val blank = transfer.copy(events = listOf(event("owner_transferred", """{"toOwnerId":""}""")))
        converter(map).convert(blank).scope.ownerId.assert().isEqualTo("customer-1")
        val notObject = transfer.copy(events = listOf(event("owner_transferred", "\"customer-3\"")))
        converter(map).convert(notObject).events.single().payload.assert().isEqualTo("\"customer-3\"")
    }

    @Test
    fun `a 9x event that implemented a lifecycle event is composed with it, which moves the stream's scope`() {
        val handedOver = LifecycleMapping(Lifecycle.OWNER_TRANSFERRED, mapOf("toOwnerId" to "ownerId"))
        val map = NineXExample.map.order {
            copy(
                events = events +
                    EventMapping("order_handed_over", to = "OrderHandedOver", lifecycle = handedOver) +
                    EventMapping("order_cancelled", to = "OrderCancelled", lifecycle = LifecycleMapping(Lifecycle.AGGREGATE_DELETED)),
            )
        }
        val handed = created.copy(
            events = listOf(event("order_handed_over", """{"toOwnerId":"customer-9","note":1}"""))
        )
        val record = converter(map).convert(handed)
        record.events.assert().containsExactly(
            EventRecord("example.order.OrderHandedOver", 1, """{"toOwnerId":"customer-9","note":1}"""),
            EventRecord(Lifecycle.OWNER_TRANSFERRED, 1, """{"ownerId":"customer-9"}"""),
        )
        // The scope is the written transfer's: what a state rebuilt from the events has.
        record.scope.ownerId.assert().isEqualTo("customer-9")
        val cancelled = created.copy(events = listOf(event("order_cancelled", """{"reason":"late"}""")))
        converter(map).convert(cancelled).events.map { it.name }.assert()
            .containsExactly("example.order.OrderCancelled", Lifecycle.AGGREGATE_DELETED)
        // The listed fields alone, renamed, whatever their values.
        val moved = NineXExample.map.order {
            copy(
                events = events + EventMapping(
                    "order_moved",
                    to = "OrderMoved",
                    lifecycle = LifecycleMapping(Lifecycle.SPACE_TRANSFERRED, mapOf("area" to "spaceId")),
                ),
            )
        }
        converter(
            moved
        ).convert(created.copy(events = listOf(event("order_moved", """{"area":"space-9","x":1}"""))))
            .events.last().assert().isEqualTo(EventRecord(Lifecycle.SPACE_TRANSFERRED, 1, """{"spaceId":"space-9"}"""))
        // A 9.x payload without a field the lifecycle event takes fails the stream, naming the event's position.
        val missing = created.copy(events = listOf(created.events.first(), event("order_handed_over", "{}")))
        assertThrows<MigrationException> { converter(map).convert(missing) }.message.assert()
            .contains("event 1 (order_handed_over)", "toOwnerId", Lifecycle.OWNER_TRANSFERRED)
    }

    @Test
    fun `a dropped event is left out, and a stream of dropped events only is refused`() {
        val map = NineXExample.map.order { copy(events = events + EventMapping("order_archived", drop = true)) }
        val mixed = created.copy(events = created.events + event("order_archived", "{}"))
        converter(map).convert(mixed).events.map { it.name }.assert().containsExactly("example.order.OrderCreated")
        val dropped = created.copy(events = listOf(event("order_archived", "{}")))
        assertThrows<MigrationException> { converter(map).convert(dropped) }.message!!.assert().contains("dropped")
            .contains("retire")
    }

    @Test
    fun `a retired event is written as stored under its retired name`() {
        val map = NineXExample.map.order {
            copy(events = events + EventMapping("order_archived", to = "OrderArchived", retire = true))
        }
        val archived = created.copy(events = listOf(event("order_archived", """{"reason":"old"}""")))
        converter(map).convert(archived).events.single().assert().isEqualTo(
            EventRecord(NineXExample.ORDER_ARCHIVED, 1, """{"reason":"old"}"""),
        )
    }

    @Test
    fun `a default command names a stream that records none`() {
        val anonymous = created.copy(header = created.header - NineXHeaders.UPSTREAM_NAME)
        assertThrows<MigrationException> { converter().convert(anonymous) }
        converter(NineXExample.map.order { copy(defaultCommand = "CreateOrder") }).convert(anonymous)
            .commandType.assert().isEqualTo("example.order.CreateOrder")
    }

    @Test
    fun `an event the map does not name fails the stream`() {
        val unknown = created.copy(events = listOf(event("order_archived", "{}")))
        assertThrows<MigrationException> { converter().convert(unknown) }.message!!.assert().contains("order_archived")
        assertThrows<MigrationException> {
            converter().convert(created.copy(aggregate = NineXAggregate("example-service", "payment")))
        }
    }

    @Test
    fun `a rename onto a field the 9x payload also holds fails the stream, in either order, and a swap converts`() {
        fun paid(fields: Map<String, String>) = NineXExample.map.order {
            copy(events = events.map { if (it.from == "order_paid") it.copy(fields = fields) else it })
        }
        val stream = NineXExample.stream("order-stream-v3.json")
        fun paying(body: String) =
            stream.copy(events = listOf(stream.events.single().copy(body = WowJson.mapper.readTree(body))))
        val renaming = converter(paid(mapOf("oldAmount" to "amount")))
        listOf("""{"oldAmount":10,"amount":25,"paid":true}""", """{"amount":25,"oldAmount":10,"paid":true}""").forEach {
            assertThrows<MigrationException> { renaming.convert(paying(it)) }.message.assert().isEqualTo(
                "${renaming.subjectOf(stream)}, event 0 (order_paid): the 9.x fields amount and oldAmount both become " +
                    "amount, and one would overwrite the other.",
            )
        }
        renaming.convert(paying("""{"oldAmount":10,"paid":true}""")).events.single().payload.assert()
            .isEqualTo("""{"amount":10,"paid":true}""")
        // A field named as 9.x's tags is an ordinary field of an event that is not the tags event.
        renaming.convert(paying("""{"oldAmount":10,"tags":{"a":"b"}}""")).events.single().payload.assert()
            .isEqualTo("""{"amount":10,"tags":{"a":"b"}}""")
        converter(paid(mapOf("oldAmount" to "amount", "amount" to "oldAmount")))
            .convert(paying("""{"oldAmount":10,"amount":25}""")).events.single().payload.assert()
            .isEqualTo("""{"amount":10,"oldAmount":25}""")
    }
}
