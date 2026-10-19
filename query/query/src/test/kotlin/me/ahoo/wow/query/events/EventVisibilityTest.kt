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
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.state.CommittedState
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

/** The events model's visibility (reviews A3-11, R2.3-6): the snapshot queries' rule on the latest committed state. */
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

    private fun state(items: Int) = CommittedState(
        ref = ref,
        version = 1,
        scope = Scope("alice", null),
        deleted = false,
        tags = mapOf("region" to listOf("east")),
        state = WowJson.mapper.createObjectNode().put("items", items),
    )

    @Test
    fun `every aggregate is visible to ALL`(): Unit = runBlocking {
        EventVisibility.ALL.visible(ref, alice).assert().isTrue()
    }

    @Test
    fun `the latest committed state decides as the snapshot queries do`(): Unit = runBlocking {
        val one = EventVisibility.of({ snapshots }) { state(1) }
        val two = EventVisibility.of({ snapshots }) { state(2) }
        one.visible(ref, alice).assert().isTrue()
        two.visible(ref, alice).assert().isFalse()
        two.visible(ref, admin).assert().isTrue()
    }

    @Test
    fun `an aggregate with no committed state shows nothing`(): Unit = runBlocking {
        EventVisibility.of({ snapshots }) { null }.visible(ref, admin).assert().isFalse()
    }

    @Test
    fun `a type without a snapshot query gateway reads no state and shows every aggregate`(): Unit = runBlocking {
        var read = false
        val visibility = EventVisibility.of({ null }) {
            read = true
            state(2)
        }
        visibility.visible(ref, alice).assert().isTrue()
        read.assert().isFalse()
    }
}
