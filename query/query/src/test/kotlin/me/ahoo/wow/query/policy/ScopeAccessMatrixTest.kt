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

package me.ahoo.wow.query.policy

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.access.ScopeAccess
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.query.engine.QueryEngine
import me.ahoo.wow.query.memory.InMemoryEventQueryStorage
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.policy.AccessFixtures.LEDGER
import me.ahoo.wow.query.policy.AccessFixtures.MANIFEST
import me.ahoo.wow.query.policy.AccessFixtures.NOTE
import me.ahoo.wow.query.policy.AccessFixtures.caller
import me.ahoo.wow.query.policy.AccessFixtures.record
import me.ahoo.wow.query.policy.AccessFixtures.state
import me.ahoo.wow.query.policy.AccessFixtures.stream
import me.ahoo.wow.spi.json.query.EventStreamFields
import me.ahoo.wow.spi.json.query.SnapshotFields
import org.junit.jupiter.api.Test

/**
 * The default rule ([ScopeAccess]) is one rule (D390 E): what the kernel evaluates on the aggregate a command changes
 * is what the engine's compiled filter reads of its snapshots, its state and its events, for every caller scope and
 * every aggregate scope, of an aggregate of its owner and of one of its tenant.
 */
class ScopeAccessMatrixTest {
    private val owners = listOf("alice", "zed")
    private val spaces = listOf(null, "s1", "s2")
    private val aggregates = owners.flatMap { owner -> spaces.map { space -> Scope(owner, space) } }

    private fun idOf(scope: Scope) = "${scope.ownerId}-${scope.spaceId ?: "none"}"

    private val records = listOf(NOTE, LEDGER).flatMap { type ->
        aggregates.map { record(type, idOf(it), owner = it.ownerId!!, space = it.spaceId) }
    }
    private val storage = InMemoryQueryStorage().apply { records.forEach(::save) }
    private val streams = aggregates.map {
        stream(NOTE, idOf(it), emptyMap(), owner = it.ownerId!!, space = it.spaceId)
    }
    private val engine = QueryEngine.Builder(MANIFEST)
        .snapshots(storage)
        .events(InMemoryEventQueryStorage({ type -> streams.filter { it.ref.type == type } }))
        // The documents' policy, which the Manifest requires: notes and ledgers read by the default rule.
        .accessPolicies(listOf(AccessFixtures.documents), AccessFixtures::aggregatesOf)
        .build()

    private val callers = listOf(
        Scope.NONE,
        Scope("alice"),
        Scope("zed"),
        Scope(spaceId = "s1"),
        Scope("alice", "s1"),
        Scope("alice", "s2"),
        Scope("bob"),
    )

    /** The aggregates of [type] the kernel lets a caller in [scope] change, by the rule it evaluates. */
    private fun reached(type: AggregateType, scope: Scope): List<String> {
        val ownership = if (type == LEDGER) Ownership.TENANT else Ownership.OWNER
        val rule = ScopeAccess.of(ownership.scopeOf(scope))
        // An aggregate of its tenant records no owner.
        return aggregates.filter { ScopeAccess.allows(rule, ownership.scopeOf(it)) }.map(::idOf)
    }

    @Test
    fun `a caller should read the snapshots, states and events of the aggregates the kernel lets it change`(): Unit =
        runBlocking {
            for (scope in callers) {
                val caller = caller(scope.ownerId, scope = scope)
                for (type in listOf(NOTE, LEDGER)) {
                    val reached = reached(type, scope)
                    engine.snapshots(type)!!.list(ListQuery(MatchAllFilter, limit = 100), caller).toList()
                        .map { it[SnapshotFields.AGGREGATE_ID].stringValue() }
                        .assert().describedAs("$type snapshots in $scope").containsExactlyInAnyOrderElementsOf(reached)
                    records.filter { it.ref.type == type && engine.reads.admitted(caller, state(it)) != null }
                        .map { it.ref.id }
                        .assert().describedAs("$type states in $scope").containsExactlyInAnyOrderElementsOf(reached)
                }
                engine.events(NOTE)!!.list(ListQuery(MatchAllFilter, limit = 100), caller).toList()
                    .map { it[EventStreamFields.AGGREGATE_ID].stringValue() }
                    .assert().describedAs("events in $scope").containsExactlyInAnyOrderElementsOf(reached(NOTE, scope))
            }
        }
}
