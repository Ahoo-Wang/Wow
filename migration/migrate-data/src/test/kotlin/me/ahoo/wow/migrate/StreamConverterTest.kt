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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.manifest.Ownership
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.EventRecord
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
        return StreamConverter(MigrationPlan(map, manifest))
    }

    private fun MigrationMap.order(change: AggregateMapping.() -> AggregateMapping): MigrationMap =
        copy(aggregates = aggregates.map { if (it.source() == order) it.change() else it })

    private fun event(name: String, body: String, revision: String = "0.0.1") =
        NineXEvent("e-$name", name, revision, WowJson.mapper.readTree(body))

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
        record.headers.assert().isEmpty()
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
            .convert(stream).headers.assert().isEqualTo(mapOf("app" to "web"))
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
    fun `a 9x event that transferred the owner moves the stream's owner by its mapping's owner field`() {
        val map = NineXExample.map.order {
            copy(events = events + EventMapping("order_handed_over", to = "OrderHandedOver", owner = "toOwnerId"))
        }
        val handed = created.copy(events = listOf(event("order_handed_over", """{"toOwnerId":"customer-9"}""")))
        val record = converter(map).convert(handed)
        record.scope.ownerId.assert().isEqualTo("customer-9")
        record.events.single().payload.assert().isEqualTo("""{"toOwnerId":"customer-9"}""")
        // A blank or missing owner leaves the stream's own.
        converter(map).convert(handed.copy(events = listOf(event("order_handed_over", """{"toOwnerId":" "}"""))))
            .scope.ownerId.assert().isEqualTo("customer-1")
        converter(map).convert(handed.copy(events = listOf(event("order_handed_over", "{}"))))
            .scope.ownerId.assert().isEqualTo("customer-1")
        // A tenant-owned aggregate still records no owner.
        converter(map, Ownership.TENANT).convert(handed).scope.ownerId.assert().isNull()
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
}
