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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import org.junit.jupiter.api.Test

/**
 * D377: a sourcing function may read the commit its event belongs to (time, operator, version), and a decision reads
 * the node's clock as `now`, the time its commit records. Both are in the log, so a rebuild reads the same: from the
 * log, from a snapshot and its tail, and as the decision itself evolved.
 */
class KernelSourcingFactsTest {
    data class OpenAccount(val holder: String)
    data class Deposit(val amount: Int)
    data class AccountOpened(val holder: String, val dueAt: Long)
    data class Deposited(val amount: Int)

    class AccountState(val id: String) {
        var holder: String? = null
        var openedAt: Long = 0
        var openedBy: String? = null
        var dueAt: Long = 0
        var balance: Int = 0
        var lastDepositAt: Long = 0
        var lastDepositVersion: Int = 0
    }

    /** A mutable state reading its commits, beside a plain evolve function. */
    private val accountModel = aggregate<AccountState>("bank", "account") {
        initial { AccountState(it.id) }
        decide<OpenAccount>(Creation.REQUIRED) { _, command ->
            // A deadline from the decision's own time, not the system clock.
            Decision.events(AccountOpened(command.holder, now + DUE_IN))
        }
        decide<Deposit> { _, command -> Decision.events(Deposited(command.amount)) }
        evolve<AccountOpened> { event, commit ->
            holder = event.holder
            openedAt = commit.commitTime
            openedBy = commit.operator?.id
            dueAt = event.dueAt
        }
        evolve<Deposited> { event, commit ->
            balance += event.amount
            lastDepositAt = commit.commitTime
            lastDepositVersion = commit.version
        }
    }

    data class Ledger(val total: Int = 0, val lastAt: Long = 0, val lastBy: String? = null)

    /** An immutable state reading its commits. */
    private val ledgerModel = aggregate<Ledger>("bank", "ledger") {
        initial { Ledger() }
        decide<Deposit>(Creation.ALLOWED) { _, command -> Decision.events(Deposited(command.amount)) }
        evolveTo<Deposited> { state, event, commit ->
            state.copy(total = state.total + event.amount, lastAt = commit.commitTime, lastBy = commit.operator?.id)
        }
    }

    private val codec = EventCodec()
    private val log = InMemoryEventLog()
    private var time = 1_000L
    private val kernel = Kernel(log, codec, clock = { time })
    private val account = accountModel.type.ref("tenant", "acc-1")
    private var sequence = 0

    private suspend fun <S : Any> send(model: AggregateModel<S>, body: Any, by: String, at: Long) =
        run {
            time = at
            val ref = if (model === accountModel) account else ledgerModel.type.ref("tenant", "ledger-1")
            kernel.execute(
                model,
                Command("c-${++sequence}", "r-$sequence", ref, body, operator = Operator(by, "tenant")),
            ) as CommandOutcome.Committed
        }

    @Test
    fun `a sourcing function should read its commit, and a decision the time it commits at`(): Unit = runBlocking {
        val opened = send(accountModel, OpenAccount("alice"), by = "alice", at = 1_000)
        opened.stream.commitTime.assert().isEqualTo(1_000)
        val deposited = send(accountModel, Deposit(5), by = "bob", at = 2_000)
        val state = deposited.state as AccountState
        state.openedAt.assert().isEqualTo(1_000)
        state.openedBy.assert().isEqualTo("alice")
        state.dueAt.assert().isEqualTo(1_000 + DUE_IN)
        state.balance.assert().isEqualTo(5)
        state.lastDepositAt.assert().isEqualTo(2_000)
        state.lastDepositVersion.assert().isEqualTo(2)
    }

    @Test
    fun `a rebuild should read the commits as recorded, from the log and from a snapshot's tail`(): Unit =
        runBlocking {
            send(accountModel, OpenAccount("alice"), by = "alice", at = 1_000)
            val second = send(accountModel, Deposit(5), by = "bob", at = 2_000)
            send(accountModel, Deposit(7), by = "carol", at = 3_000)
            // Rebuilt later, on a clock far ahead: the state is the one the commits recorded.
            time = 9_999_000
            val replayed = Kernel(log, codec, clock = { time }).stateAt(accountModel, account, 3)!!.state
                as AccountState
            val again = Kernel(log, codec, clock = { time }).stateAt(accountModel, account, 3)!!.state
                as AccountState
            val snapshots = InMemorySnapshotStore()
            snapshots.save(codec.snapshotOf(accountModel, account, second.state as AccountState, 2, Scope.NONE, 0, 0))
            val fromSnapshot = Kernel(log, codec, clock = { time }, snapshots = snapshots)
                .stateAt(accountModel, account, 3, snapshots.load(account))!!.state as AccountState
            listOf(replayed, again, fromSnapshot).forEach { state ->
                state.openedAt.assert().isEqualTo(1_000)
                state.openedBy.assert().isEqualTo("alice")
                state.dueAt.assert().isEqualTo(1_000 + DUE_IN)
                state.balance.assert().isEqualTo(12)
                state.lastDepositAt.assert().isEqualTo(3_000)
                state.lastDepositVersion.assert().isEqualTo(3)
            }
            codec.stateText(replayed).assert().isEqualTo(codec.stateText(fromSnapshot))
        }

    @Test
    fun `an immutable state should read its commits, deciding and replaying alike`(): Unit = runBlocking {
        send(ledgerModel, Deposit(2), by = "alice", at = 1_000)
        val decided = send(ledgerModel, Deposit(3), by = "bob", at = 4_000).state as Ledger
        decided.assert().isEqualTo(Ledger(5, 4_000, "bob"))
        time = 9_999_000
        Kernel(log, codec, clock = { time }).stateAt(ledgerModel, ledgerModel.type.ref("tenant", "ledger-1"), 2)!!
            .state.assert().isEqualTo(decided)
    }

    @Test
    fun `an evolve function reading its commit should be given one`() {
        val failure = runCatching { accountModel.evolve(AccountState("x"), Deposited(1)) }.exceptionOrNull()
        failure!!.message.assert().contains("reads the commit of its event")
    }

    @Test
    fun `an event should have one evolve function, whether or not it reads its commit`() {
        val twice = listOf<AggregateModelBuilder<Ledger>.() -> Unit>(
            {
                evolveTo<Deposited> { state, _ -> state }
                evolveTo<Deposited> { state, _, _ -> state }
            },
            {
                evolveTo<Deposited> { state, _, _ -> state }
                evolveTo<Deposited> { state, _ -> state }
            },
            {
                evolve<Deposited> { _, _ -> }
                evolveTo<Deposited> { state, _, _ -> state }
            },
        )
        twice.forEach { declare ->
            val failure = runCatching {
                aggregate<Ledger>("bank", "ledger") {
                    initial { Ledger() }
                    declare()
                }
            }.exceptionOrNull()
            failure.assert().isInstanceOf(IllegalArgumentException::class.java)
            failure!!.message.assert()
                .isEqualTo("bank.ledger declares two evolve functions for ${Deposited::class.java.name}.")
        }
    }

    private companion object {
        const val DUE_IN = 30_000L
    }
}
