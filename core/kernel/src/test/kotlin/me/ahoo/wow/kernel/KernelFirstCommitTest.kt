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

package me.ahoo.wow.kernel

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.spi.observation.Observer
import org.junit.jupiter.api.Test

/**
 * Work package B.O-4: the kernel reports each state cache lookup, and a state it loads or creates carries what the
 * aggregate's first stream recorded, so the snapshot subscription need not read the stored snapshot for it.
 */
class KernelFirstCommitTest {
    private val ref = cartModel.type.ref("tenant", "cart-1")
    private val log = InMemoryEventLog()
    private val snapshots = InMemorySnapshotStore()
    private val creator = Operator("alice", "tenant")
    private var sequence = 0
    private var clock = 1_000L

    private class MapCache : StateCache {
        val states = HashMap<AggregateRef, CachedState>()

        override fun take(ref: AggregateRef): CachedState? = states.remove(ref)

        override fun put(ref: AggregateRef, state: CachedState) {
            states[ref] = state
        }
    }

    private suspend fun Kernel.send(body: Any, operator: Operator? = null): CommandOutcome.Committed =
        execute(cartModel, Command("c-${++sequence}", "r-$sequence", ref, body, operator = operator))
            as CommandOutcome.Committed

    @Test
    fun `a created, replayed or snapshot-loaded aggregate knows its first stream`(): Unit = runTest {
        val writer = Kernel(log, clock = { clock++ })
        val created = writer.send(CreateCart("ahoo"), creator)
        val first = FirstCommit(created.stream.commitTime, creator)
        created.first.assert().isEqualTo(first)
        // Replayed: the log's first stream.
        val replayed = writer.send(AddItem("apple", 1))
        replayed.first.assert().isEqualTo(first)
        // From a snapshot: what the snapshot kept of it.
        val codec = EventCodec()
        snapshots.save(
            codec.snapshotOf(
                cartModel,
                ref,
                replayed.state as CartState,
                2,
                Scope.NONE,
                firstEventTime = first.eventTime,
                lastEventTime = replayed.stream.commitTime,
                firstOperator = creator,
            ),
        )
        Kernel(log, codec, snapshots = snapshots).send(AddItem("apple", 1)).first.assert().isEqualTo(first)
    }

    @Test
    fun `each lookup of the state cache is reported as a hit or a miss`(): Unit = runTest {
        val outcomes = mutableListOf<Pair<String, String>>()
        val observer = object : Observer {
            override fun stateCache(context: String, outcome: String) {
                outcomes += context to outcome
            }
        }
        val kernel = Kernel(log, states = MapCache(), observer = observer)
        kernel.send(CreateCart("ahoo"))
        val cached = kernel.send(AddItem("apple", 1))
        cached.first.assert().isEqualTo(FirstCommit(log.read(ref, 1, 1).toList().single().commitTime, null))
        outcomes.assert().containsExactly(
            "example" to StateCacheOutcome.MISS,
            "example" to StateCacheOutcome.HIT,
        )
    }
}
