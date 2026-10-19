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

import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.query.AbacQueryOptions
import me.ahoo.wow.query.AbacQueryPolicy
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.QueryErrors
import me.ahoo.wow.query.QueryExecutionException
import me.ahoo.wow.query.QueryPolicy
import me.ahoo.wow.query.events.EventVisibility
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** [SnapshotPolicyGateway]: the snapshot query policies of a type whose snapshots are not queried (review R3.3-3). */
class SnapshotPolicyGatewayTest {
    private val type = AggregateType("shop", "cart")
    private val catalog = QuerySchemaCatalog()
    private val gateway = SnapshotPolicyGateway(
        type,
        catalog.policyProvider(type),
        listOf(AbacQueryPolicy(AbacQueryOptions(enabled = true, matchMissingTagKey = false))),
    )
    private val state = CommittedState(
        ref = type.ref("acme", "c-1"),
        version = 1,
        scope = Scope("alice", null),
        deleted = false,
        tags = mapOf("region" to listOf("east")),
        state = WowJson.mapper.createObjectNode().put("items", 2),
    )

    private fun tagged(region: String) = QueryCaller(Caller(attributes = mapOf("abac.region" to listOf(region))))

    @Test
    fun `the policies decide on the state alone, as a snapshot query would`(): Unit = runBlocking {
        StateVisibility.visible(gateway, tagged("east"), state).assert().isTrue()
        StateVisibility.visible(gateway, tagged("west"), state).assert().isFalse()
        StateVisibility.admitted(gateway, tagged("east"), state)!!["items"].asInt().assert().isEqualTo(2)
    }

    @Test
    fun `an application policy that denies hides the state from everyone it denies`(): Unit = runBlocking {
        val denying = SnapshotPolicyGateway(
            type,
            catalog.policyProvider(type),
            listOf(QueryPolicy { caller, _ -> if (caller.subject == "admin") MatchAllFilter else MatchNoneFilter }),
        )
        StateVisibility.visible(denying, QueryCaller(Caller(subject = "alice")), state).assert().isFalse()
        StateVisibility.visible(denying, QueryCaller(Caller(subject = "admin")), state).assert().isTrue()
    }

    @Test
    fun `a state without the caller's tag, or with another value, is hidden`(): Unit = runBlocking {
        StateVisibility.visible(gateway, tagged("west"), state.copy(tags = emptyMap())).assert().isFalse()
        StateVisibility.visible(gateway, tagged("east"), state.copy(tags = mapOf("region" to listOf("north"))))
            .assert().isFalse()
    }

    @Test
    fun `the events of an aggregate are hidden under it, and an aggregate with no state shows nothing`(): Unit =
        runBlocking {
            val visible = EventVisibility.of({ gateway }) { state }
            visible.visible(state.ref, tagged("east")).assert().isTrue()
            visible.visible(state.ref, tagged("west")).assert().isFalse()
            visible.seesAll(type, tagged("east")).assert().isFalse()
            EventVisibility.of({ gateway }) { null }.visible(state.ref, tagged("east")).assert().isFalse()
        }

    @Test
    fun `the policy schema is compiled once per type, over no storage`(): Unit = runBlocking {
        catalog.policyProvider(type).assert().isSameAs(catalog.policyProvider(type))
        catalog.policyProvider(type).schema().assert().isNotNull()
        catalog.versions().assert().isEmpty()
    }

    @Test
    fun `it answers no query`(): Unit = runBlocking {
        val caller = QueryCaller.IN_PROCESS
        gateway.aggregateType.assert().isEqualTo(type)
        val unqueried = listOf<suspend () -> Any?>(
            { gateway.single(SingleQuery(MatchAllFilter), caller) },
            { gateway.list(ListQuery(MatchAllFilter), caller).toList() },
            { gateway.paged(PagedQuery(MatchAllFilter), caller) },
            { gateway.cursor(CursorQuery(MatchAllFilter), caller) },
            { gateway.count(CountQuery(MatchAllFilter), caller) },
            { gateway.aggregate(mockk<AggregationQuery>(), caller).toList() },
            { gateway.describe(QueryEntry.HTTP) },
        )
        unqueried.forEach { query ->
            val refused = assertThrows<QueryExecutionException> { runBlocking { query() } }
            refused.errorCode.assert().isEqualTo(QueryErrors.FAILED)
            refused.errorMsg.assert().isEqualTo("The snapshots of $type are not queried.")
        }
    }
}
