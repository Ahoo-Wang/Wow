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

package me.ahoo.wow.query.state

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.read.Snapshot
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.mask.PayloadMasks
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/** [StateVisibility]: a state as the snapshot-shaped record the snapshot queries admit (review A3-11). */
class StateVisibilityTest {
    private val ref = AggregateType("shop", "cart").ref("acme", "c-1")
    private val state = Snapshot.of<JsonNode>(
        ref = ref,
        version = 3,
        scope = Scope("alice", "s-1"),
        deleted = true,
        tags = mapOf("region" to listOf("east", "west")),
        operator = null,
        firstEventTime = 0,
        lastEventTime = 0,
        state = WowJson.mapper.createObjectNode().put("items", 2),
    )

    @Test
    fun `the gateway admits the state as a snapshot record read by id of every deletion state`(): Unit = runBlocking {
        var selection: FilterExpression? = null
        var record: ObjectNode? = null
        val gateway = mockk<QueryGateway> {
            coEvery { admitRecord(any(), any(), any()) } answers {
                selection = firstArg()
                record = secondArg()
                secondArg<ObjectNode>().also { it.putObject("state").put("items", "masked") }
            }
        }
        StateVisibility.admitted(gateway, QueryCaller.IN_PROCESS, state)!!["items"].asString().assert()
            .isEqualTo("masked")
        StateVisibility.visible(gateway, QueryCaller.IN_PROCESS, state).assert().isTrue()
        selection.assert().isEqualTo(AndFilter(listOf(IdFilter("c-1"), DeletionFilter(DeletionState.ALL))))
        record!!.let {
            it["tenantId"].asString().assert().isEqualTo("acme")
            it["aggregateId"].asString().assert().isEqualTo("c-1")
            it["version"].asInt().assert().isEqualTo(3)
            it["ownerId"].asString().assert().isEqualTo("alice")
            it["spaceId"].asString().assert().isEqualTo("s-1")
            it["deleted"].asBoolean().assert().isTrue()
            it["tags"].toString().assert().isEqualTo("""{"region":["east","west"]}""")
        }
    }

    @Test
    fun `a state the gateway hides is not visible`(): Unit = runBlocking {
        val gateway = mockk<QueryGateway> { coEvery { admitRecord(any(), any(), any()) } returns null }
        StateVisibility.admitted(gateway, QueryCaller.IN_PROCESS, state).assert().isNull()
        StateVisibility.visible(gateway, QueryCaller.IN_PROCESS, state).assert().isFalse()
    }

    @Test
    fun `without a gateway every state is visible, masked by the Manifest's masks`(): Unit = runBlocking {
        StateVisibility.visible(null, QueryCaller.IN_PROCESS, state).assert().isTrue()
        StateVisibility.admitted(null, QueryCaller.IN_PROCESS, state, PayloadMasks.NONE)!!["items"].asInt().assert()
            .isEqualTo(2)
    }
}
