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

package me.ahoo.wow.test

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.aggregate
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * D377: a decision reads the specification's time as `now`, the given events carry the facts of their commit (time,
 * operator), and a sourcing function reads them, in an aggregate's specification and in a processor's.
 */
class SourcingFactsSpecTest {
    data class Open(val holder: String)
    data class Deposit(val amount: Int)
    data class Opened(val holder: String, val dueAt: Long)
    data class Deposited(val amount: Int)

    class Account(val id: String) {
        var openedAt = 0L
        var openedBy: String? = null
        var dueAt = 0L
        var balance = 0
        var lastDepositAt = 0L
        var lastDepositBy: String? = null
        var lastDepositVersion = 0
    }

    private val accountModel: AggregateModel<Account> = aggregate("bank", "account") {
        initial { Account(it.id) }
        decide<Open>(Creation.REQUIRED) { _, body -> Decision.events(Opened(body.holder, now + DUE_IN)) }
        decide<Deposit> { _, body -> Decision.events(Deposited(body.amount)) }
        evolve<Opened> { event, commit ->
            openedAt = commit.commitTime
            openedBy = commit.operator?.id
            dueAt = event.dueAt
        }
        evolve<Deposited> { event, commit ->
            balance += event.amount
            lastDepositAt = commit.commitTime
            lastDepositBy = commit.operator?.id
            lastDepositVersion = commit.version
        }
    }

    private val alice = Operator("alice", DEFAULT_TENANT)
    private val bob = Operator("bob", DEFAULT_TENANT)

    @Test
    fun `a decision should read the specification's time, and its commit be recorded at it`() =
        aggregateSpec(accountModel) {
            advanceTime(10.minutes)
            whenCommand(Open("alice"), operator = alice) {
                val opened = DEFAULT_START.toEpochMilli() + 10.minutes.inWholeMilliseconds
                expectEvents(Opened("alice", opened + DUE_IN))
                expectState {
                    openedAt.assert().isEqualTo(opened)
                    openedBy.assert().isEqualTo("alice")
                }
            }
        }

    @Test
    fun `a given event should carry the operator and the commit time of its commit`() =
        aggregateSpec(accountModel) {
            val opened = Instant.parse("2025-06-01T08:00:00Z")
            given(Opened("alice", 0), operator = alice, commitTime = opened)
            // By default a given commit is at the specification's time, in the name of no one.
            given(Deposited(2))
            val deposited = now.toEpochMilli()
            whenCommand(Deposit(3), operator = bob) {
                expectState {
                    openedAt.assert().isEqualTo(opened.toEpochMilli())
                    openedBy.assert().isEqualTo("alice")
                    balance.assert().isEqualTo(5)
                    lastDepositAt.assert().isEqualTo(deposited)
                    lastDepositBy.assert().isEqualTo("bob")
                    lastDepositVersion.assert().isEqualTo(3)
                }
            }
        }

    @Test
    fun `a given aggregate should read the commits it hands over`() {
        val seen = mutableListOf<Account>()
        val handlers = listOf(
            ProcessorBinding.saga("AccountSaga", "onAccount", Account::class.java) { event: StateEvent<Account>, _ ->
                seen += event.state
                emptyList()
            },
        )
        sagaSpec(handlers) {
            val account = givenAggregate(accountModel, "acc-1", Opened("alice", 0))
            account.state.openedAt.assert().isEqualTo(DEFAULT_START.toEpochMilli())
            account.state.openedBy.assert().isNull()
            whenEvent(Deposited(4), operator = bob)
        }
        seen.single().lastDepositBy.assert().isEqualTo("bob")
        seen.single().lastDepositVersion.assert().isEqualTo(2)
        seen.single().lastDepositAt.assert().isEqualTo(DEFAULT_START.toEpochMilli())
    }

    private companion object {
        const val DUE_IN = 30_000L
    }
}
