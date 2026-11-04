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
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.read.Snapshot
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.error.QueryErrors
import me.ahoo.wow.query.error.QueryExecutionException
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.JsonNode

/** [SnapshotPolicyGateway]: the snapshot query policies of a type whose snapshots are not queried (review R3.3-3). */
class SnapshotPolicyGatewayTest {
    private val type = AggregateType("shop", "cart")
    private val catalog = QuerySchemaCatalog()
    private val gateway = SnapshotPolicyGateway(
        type,
        catalog.policyProvider(type),
        // Each caller reads the states it owns.
        listOf(QueryPolicy { caller, _ -> caller.subject?.let(::OwnerIdFilter) ?: MatchNoneFilter }),
    )
    private val state = stateOwnedBy("alice")

    private fun stateOwnedBy(owner: String?) = Snapshot.of<JsonNode>(
        ref = type.ref("acme", "c-1"),
        version = 1,
        scope = Scope(owner, null),
        deleted = false,
        access = emptyMap(),
        operator = null,
        firstEventTime = 0,
        lastEventTime = 0,
        state = WowJson.mapper.createObjectNode().put("items", 2),
    )

    private fun caller(subject: String) = QueryCaller(Caller(subject = subject))

    @Test
    fun `the policies decide on the state alone, as a snapshot query would`(): Unit = runBlocking {
        StateVisibility.visible(gateway, caller("alice"), state).assert().isTrue()
        StateVisibility.visible(gateway, caller("bob"), state).assert().isFalse()
        StateVisibility.admitted(gateway, caller("alice"), state)!!["items"].asInt().assert().isEqualTo(2)
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
    fun `a state of no owner, or of another, is hidden`(): Unit = runBlocking {
        StateVisibility.visible(gateway, caller("alice"), stateOwnedBy(null)).assert().isFalse()
        StateVisibility.visible(gateway, caller("alice"), stateOwnedBy("bob")).assert().isFalse()
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
