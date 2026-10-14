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

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.kernel.test.aggregateSpec
import org.junit.jupiter.api.Test

/**
 * Decision D203: a state need not hold the aggregate's id, which lives in its ref and stream. A state built without
 * arguments decides, evolves, snapshots and is identified by its snapshot record's ref.
 */
class NoIdStateTest {
    data class Open(val limit: Int)
    data class Deposit(val amount: Int)
    data class Opened(val limit: Int)
    data class Deposited(val amount: Int)

    class Wallet {
        var limit = 0
        var balance = 0
    }

    private val model = aggregate<Wallet>("bank", "wallet") {
        initial { Wallet() }
        decide<Open>(Creation.REQUIRED) { _, body -> Decision.events(Opened(body.limit)) }
        decide<Deposit> { state, body ->
            if (state.balance + body.amount > state.limit) {
                Decision.reject("wallet.over-limit", "The limit is ${state.limit}.")
            } else {
                Decision.events(Deposited(body.amount))
            }
        }
        evolve<Opened> { limit = it.limit }
        evolve<Deposited> { balance += it.amount }
    }

    @Test
    fun `a state without an id decides and evolves in the test DSL`() = aggregateSpec(model) {
        whenCommand(Open(10)) {
            expectEvents(Opened(10))
            expectState { limit.assert().isEqualTo(10) }
        }
        whenCommand(Deposit(4)) { expectState { balance.assert().isEqualTo(4) } }
        whenCommand(Deposit(7)) { expectRejected("wallet.over-limit") }
    }

    @Test
    fun `a given state without an id is where the commands start`() = aggregateSpec(model) {
        givenState(version = 3) {
            limit = 10
            balance = 9
        }
        whenCommand(Deposit(1), expectedVersion = 3) { expectState { balance.assert().isEqualTo(10) } }
    }

    @Test
    fun `a state without an id round trips through a snapshot, which carries the aggregate's id`(): Unit = runTest {
        val codec = EventCodec()
        val log = InMemoryEventLog()
        val snapshots = InMemorySnapshotStore()
        val ref = model.type.ref("tenant", "wallet-1")
        val replaying = Kernel(log, codec)
        replaying.execute(model, Command("c1", "r1", ref, Open(10)))
        val second = replaying.execute(model, Command("c2", "r2", ref, Deposit(3))) as CommandOutcome.Committed
        snapshots.save(codec.snapshotOf(model, ref, second.state as Wallet, 2, second.stream.scope, 0, 0))

        val stored = snapshots.load(ref)!!
        stored.state.assert().isEqualTo("""{"balance":3,"limit":10}""")
        // The query side's aggregateId and tenantId come from the record's ref, not from the state.
        stored.ref.id.assert().isEqualTo("wallet-1")
        stored.ref.tenantId.assert().isEqualTo("tenant")

        val loaded = Kernel(log, codec, snapshots = snapshots)
            .execute(model, Command("c3", "r3", ref, Deposit(2))) as CommandOutcome.Committed
        (loaded.state as Wallet).balance.assert().isEqualTo(5)
        loaded.stream.version.assert().isEqualTo(3)
    }
}
