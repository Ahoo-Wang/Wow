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
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.log.EventRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StreamReverterTest {
    private val order = NineXAggregate("example-service", "order")
    private val orderType = AggregateType("example", "order")

    private fun plan(map: MigrationMap = NineXExample.map) = MigrationPlan(map, NineXExample.manifest)

    private fun reverter(map: MigrationMap = NineXExample.map, revisions: List<String> = emptyList()) =
        StreamReverter(plan(map)) { _, _ -> revisions }

    private fun MigrationMap.order(change: AggregateMapping.() -> AggregateMapping): MigrationMap =
        copy(aggregates = aggregates.map { if (it.source() == order) it.change() else it })

    private val paid = StreamConverter(plan()).convert(NineXExample.stream("order-stream-v3.json"))

    @Test
    fun `every stream of the 9x example should go back to the 9x stream it was imported from`() {
        val converter = StreamConverter(plan())
        NineXExample.streams().forEach { stream ->
            val record = converter.convert(stream)
            val back = reverter().revert(record)
            converter.convert(back).assert().isEqualTo(record)
            back.aggregate.assert().isEqualTo(stream.aggregate)
            back.aggregateId.assert().isEqualTo(stream.aggregateId)
            back.ownerId.assert().isEqualTo(stream.ownerId)
            back.spaceId.assert().isEqualTo(stream.spaceId)
            back.commandId.assert().isEqualTo(stream.commandId)
            back.requestId.assert().isEqualTo(stream.requestId)
            back.createTime.assert().isEqualTo(stream.createTime)
            back.header.assert().isEqualTo(stream.header)
            back.events.map { it.name to it.revision }.assert().isEqualTo(stream.events.map { it.name to it.revision })
            back.events.map { it.body }.assert().isEqualTo(stream.events.map { it.body })
        }
    }

    @Test
    fun `the stream and event ids should be derived from the ref and version, the same on every run`() {
        val back = reverter().revert(paid)
        back.id.assert().isEqualTo(StreamReverter.streamIdOf(paid)).isEqualTo(reverter().revert(paid).id)
        back.events.map { it.id }.assert().containsExactly("${back.id}-1")
        StreamReverter.streamIdOf(paid.copyWith(version = 4)).assert().isNotEqualTo(back.id)
    }

    @Test
    fun `an event V10 moved to a later revision should be a V10-only type 9x cannot read`() {
        val created = paid.copyWith(events = listOf(EventRecord("example.order.OrderCreated", 2, "{}")))
        reverter().problemsOf(created).assert()
            .containsExactly("the V10 event example.order.OrderCreated@2 of example.order has no 9.x event in the map")
        assertThrows<MigrationException> { reverter().revert(created) }
            .message.assert().contains("example.order.OrderCreated@2")
    }

    @Test
    fun `an event the map does not name and an aggregate no 9x aggregate maps to should have no way back`() {
        val noted = paid.copyWith(
            events = listOf(
                EventRecord("example.order.OrderNoted", 1, "{}"),
                EventRecord("example.order.OrderNoted", 1, "{}")
            ),
        )
        reverter().problemsOf(noted).assert()
            .containsExactly("the V10 event example.order.OrderNoted@1 of example.order has no 9.x event in the map")
        val invoice = paid.copyWith(ref = AggregateType("example", "invoice").ref("(0)", "i-1"))
        reverter().problemsOf(invoice).assert()
            .containsExactly("the V10 aggregate example.invoice has no 9.x aggregate in the map")
        reverter().aggregateOf(invoice.ref.type).assert().isNull()
    }

    @Test
    fun `a V10 aggregate two 9x aggregates map to should have no way back`() {
        val map = NineXExample.map.let { map ->
            map.copy(
                aggregates = map.aggregates + map.aggregates.first { it.source() == order }.copy(from = "legacy-service.order")
            )
        }
        reverter(map).aggregateOf(orderType).assert().isNull()
        reverter(map).problemsOf(paid).single().assert().contains("is mapped from several 9.x aggregates")
    }

    @Test
    fun `renamed fields and kept headers should get their 9x names back`() {
        val map = NineXExample.map.copy(headers = mapOf("app_id" to "app")).order {
            copy(
                events = events + EventMapping(
                    from = "owner_transferred",
                    to = Lifecycle.OWNER_TRANSFERRED,
                    fields = mapOf("toOwnerId" to "ownerId"),
                ),
            )
        }
        val transferred = paid.copyWith(
            events = listOf(EventRecord(Lifecycle.OWNER_TRANSFERRED, 1, """{"ownerId":"customer-2","note":"n"}""")),
            headers = mapOf("app" to "shop"),
            scope = Scope("customer-2", null),
        )
        val back = reverter(map).revert(transferred)
        back.events.single().name.assert().isEqualTo("owner_transferred")
        back.events.single().body.toString().assert().isEqualTo("""{"toOwnerId":"customer-2","note":"n"}""")
        back.header["app_id"].assert().isEqualTo("shop")
        back.ownerId.assert().isEqualTo("customer-2")
        back.spaceId.assert().isEmpty()

        // A header the map does not keep stays behind; a space goes back; a payload that is no object is as stored.
        val other = reverter(map).revert(
            transferred.copyWith(
                events = listOf(EventRecord(Lifecycle.OWNER_TRANSFERRED, 1, "[1]")),
                headers = mapOf("app" to "shop", "trace" to "t"),
                scope = Scope("customer-2", "space-1"),
            ),
        )
        other.header.keys.assert().doesNotContain("trace")
        other.spaceId.assert().isEqualTo("space-1")
        other.events.single().body.toString().assert().isEqualTo("[1]")
    }

    @Test
    fun `an event mapped from any revision should go back at the latest revision the 9x store holds`() {
        reverter(revisions = listOf("0.0.1", "0.0.2")).revert(paid).events.single().revision.assert().isEqualTo("0.0.2")
        reverter().revert(paid).events.single().revision.assert().isEqualTo(NineXStream.DEFAULT_REVISION)
    }

    @Test
    fun `a command the map does not name should keep its V10 name, and a stream without causation or operator none`() {
        val bare = paid.copyWith(commandType = "example.order.RefundOrder", causation = null, operator = null)
        val back = reverter().revert(bare)
        back.header.assert().isEqualTo(
            mapOf(
                NineXHeaders.UPSTREAM_ID to bare.commandId,
                NineXHeaders.UPSTREAM_NAME to "example.order.RefundOrder"
            ),
        )
    }
}
