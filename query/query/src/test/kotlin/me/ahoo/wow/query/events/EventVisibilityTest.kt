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

package me.ahoo.wow.query.events

import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.read.Snapshot
import me.ahoo.wow.query.AdmittedAggregates
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.observe.QueryObserver
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * The events model's visibility (reviews A3-11, R2.3-6, decisions D254, D259): the snapshot queries' rule, checked by
 * one query of the type's snapshots, or on the latest committed state of each aggregate.
 */
class EventVisibilityTest {
    private val type = AggregateType("shop", "cart")
    private val ref = type.ref("acme", "c-1")
    private val alice = QueryCaller(Caller(tenantId = "acme", subject = "alice"))
    private val admin = QueryCaller(Caller(tenantId = "acme", subject = "admin"))

    /** Hides a cart of two items from every caller but the admin, as a query policy would. */
    private val snapshots = mockk<QueryGateway> {
        coEvery { admitRecord(any(), any(), any()) } answers {
            val record = secondArg<ObjectNode>()
            val caller = thirdArg<QueryCaller>()
            record.takeIf { caller.subject == "admin" || it["state"]["items"].asInt() != 2 }
        }
    }

    private fun state(items: Int, id: String = ref.id) = Snapshot.of<JsonNode>(
        ref = type.ref("acme", id),
        version = 1,
        scope = Scope("alice", null),
        deleted = false,
        tags = mapOf("region" to listOf("east")),
        operator = null,
        firstEventTime = 0,
        lastEventTime = 0,
        state = WowJson.mapper.createObjectNode().put("items", items),
    )

    /** One by one, as of a type whose snapshots are not queried. */
    private fun pointReads(
        gateway: QueryGateway? = snapshots,
        hides: Boolean = true,
        latest: suspend (me.ahoo.wow.api.aggregate.AggregateRef) -> Snapshot<JsonNode>?,
    ) = EventVisibility.of({ gateway }, hides, batched = { false }, latest = latest)

    private suspend fun EventVisibility.visible(id: String, caller: QueryCaller): Boolean =
        visible(type, "acme", listOf(id), caller) == listOf(id)

    @Test
    fun `every aggregate is visible to ALL, which pins nothing`(): Unit = runBlocking {
        EventVisibility.ALL.visible(type, "acme", listOf("c-1", "c-2"), alice).assert().containsExactly("c-1", "c-2")
        EventVisibility.ALL.pins(type).assert().isFalse()
    }

    @Test
    fun `the latest committed state decides as the snapshot queries do`(): Unit = runBlocking {
        val one = pointReads { state(1) }
        val two = pointReads { state(2) }
        one.visible(ref.id, alice).assert().isTrue()
        two.visible(ref.id, alice).assert().isFalse()
        two.visible(ref.id, admin).assert().isTrue()
    }

    @Test
    fun `an aggregate with no committed state shows nothing`(): Unit = runBlocking {
        pointReads { null }.visible(ref.id, admin).assert().isFalse()
    }

    @Test
    fun `a type without a snapshot query gateway fails closed while policies may hide one`(): Unit = runBlocking {
        var read = false
        val visibility = pointReads(gateway = null) {
            read = true
            state(1)
        }
        visibility.visible(ref.id, admin).assert().isFalse()
        read.assert().isFalse()
    }

    @Test
    fun `a type without a snapshot query gateway shows every aggregate when nothing could hide one`(): Unit =
        runBlocking {
            pointReads(gateway = null, hides = false) { null }.visible(ref.id, alice).assert().isTrue()
        }

    @Test
    fun `a type is pinned only while a pinned policy applies, and batched as it is told`() {
        EventVisibility.of({ snapshots }) { state(1) }.pins(type).assert().isTrue()
        EventVisibility.of({ snapshots }, hides = false) { state(1) }.pins(type).assert().isFalse()
        EventVisibility.of({ snapshots }) { state(1) }.batches(type).assert().isTrue()
        pointReads { state(1) }.batches(type).assert().isFalse()
        EventVisibility { _, _, ids, _ -> ids }.pins(type).assert().isTrue()
        EventVisibility { _, _, ids, _ -> ids }.batches(type).assert().isTrue()
    }

    @Test
    fun `one snapshot query decides the aggregates it finds, and only the others are read one by one`(): Unit =
        runBlocking {
            val gateway = mockk<QueryGateway> {
                coEvery { admitAggregates("acme", listOf("c-1", "c-2", "c-3", "c-4"), alice) } returns
                    AdmittedAggregates(stored = setOf("c-1", "c-2"), visible = setOf("c-2"))
                coEvery { admitRecord(any(), any(), any()) } answers { secondArg() }
            }
            val read = mutableListOf<String>()
            val observer = mockk<QueryObserver>(relaxed = true)
            val visibility = EventVisibility.of({ gateway }, observer = observer) { ref ->
                read += ref.id
                // c-3's first snapshot lags; c-4 does not exist.
                state(1, ref.id).takeIf { ref.id == "c-3" }
            }
            visibility.visible(type, "acme", listOf("c-1", "c-2", "c-3", "c-4"), alice)
                .assert().containsExactly("c-2", "c-3")
            read.assert().containsExactly("c-3", "c-4")
            verify { observer.onEventsPinned(type, 2, 2) }
        }

    @Test
    fun `aggregates without a snapshot are read one by one only up to the point-read limit`(): Unit = runBlocking {
        val ids = (1..EventVisibility.POINT_READS + 5).map { "c-$it" }
        val gateway = mockk<QueryGateway> {
            coEvery { admitAggregates("acme", ids, alice) } returns AdmittedAggregates.NONE
            coEvery { admitRecord(any(), any(), any()) } answers { secondArg() }
        }
        var reads = 0
        val visible = EventVisibility.of({ gateway }) { ref ->
            reads++
            state(1, ref.id)
        }.visible(type, "acme", ids, alice)
        reads.assert().isEqualTo(EventVisibility.POINT_READS)
        visible.assert().isEqualTo(ids.take(EventVisibility.POINT_READS))
    }

    @Test
    fun `a gateway that queries no backend is read one by one`(): Unit = runBlocking {
        val gateway = object : QueryGateway by snapshots {
            override suspend fun admitAggregates(tenantId: String, ids: Collection<String>, caller: QueryCaller) =
                super<QueryGateway>.admitAggregates(tenantId, ids, caller)
        }
        EventVisibility.of({ gateway }) { state(1) }.visible(ref.id, alice).assert().isTrue()
    }
}
