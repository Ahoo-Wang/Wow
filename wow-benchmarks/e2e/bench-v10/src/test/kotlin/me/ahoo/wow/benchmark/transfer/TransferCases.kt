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

package me.ahoo.wow.benchmark.transfer

// The shared table of cases (decision D224): this file is the same, byte for byte, in the 9.x and the V10 module
// (SharedCasesTest in :e2e-bench-v10 checks it), and each version's specs run every case through its own test DSL.

/** The account's fields as both versions' states hold them. */
data class Balances(val owner: String, val balance: Long, val locked: Long = 0, val frozen: Boolean = false)

/**
 * One command on account [ACCOUNT_ID] after the [given] events: it commits [events] and leaves [state], or it is
 * rejected with [rejection] and leaves the account as it was.
 */
data class AccountCase(
    val name: String,
    val given: List<Any>,
    val command: Any,
    val events: List<Any> = emptyList(),
    val rejection: String? = null,
    val state: Balances? = null,
) {
    override fun toString(): String = name
}

/** One event of account [source] handed to the transfer saga: it derives [command] to account [target], or none. */
data class SagaCase(
    val name: String,
    val source: String,
    val event: Any,
    val command: Any? = null,
    val target: String? = null,
) {
    override fun toString(): String = name
}

object TransferCases {
    const val ACCOUNT_ID = "a"
    const val TARGET_ID = "b"
    private val opened = AccountOpened("alice", 100)
    private val locked = listOf(opened, AmountLocked(40), Prepared(TARGET_ID, 40))

    val ACCOUNT: List<AccountCase> = listOf(
        AccountCase(
            "opening an account records its owner and balance",
            given = emptyList(),
            command = OpenAccount("alice", 100),
            events = listOf(opened),
            state = Balances("alice", 100),
        ),
        AccountCase(
            "opening an account with a negative balance is rejected",
            given = emptyList(),
            command = OpenAccount("alice", -1),
            rejection = AccountRejections.INVALID_AMOUNT,
        ),
        AccountCase(
            "a deposit adds to the balance",
            given = listOf(opened),
            command = Deposit(30),
            events = listOf(Deposited(30)),
            state = Balances("alice", 130),
        ),
        AccountCase(
            "a deposit of nothing is rejected",
            given = listOf(opened),
            command = Deposit(0),
            rejection = AccountRejections.INVALID_AMOUNT,
            state = Balances("alice", 100),
        ),
        AccountCase(
            "a withdrawal the balance covers takes from it",
            given = listOf(opened),
            command = Withdraw(30),
            events = listOf(Withdrawn(30)),
            state = Balances("alice", 70),
        ),
        AccountCase(
            "a withdrawal the balance does not cover is rejected",
            given = listOf(opened),
            command = Withdraw(150),
            rejection = AccountRejections.INSUFFICIENT_BALANCE,
            state = Balances("alice", 100),
        ),
        AccountCase(
            "a negative withdrawal is rejected",
            given = listOf(opened),
            command = Withdraw(-1),
            rejection = AccountRejections.INVALID_AMOUNT,
            state = Balances("alice", 100),
        ),
        AccountCase(
            "a transfer out locks its amount",
            given = listOf(opened),
            command = Prepare(TARGET_ID, 40),
            events = listOf(AmountLocked(40), Prepared(TARGET_ID, 40)),
            state = Balances("alice", 60, locked = 40),
        ),
        AccountCase(
            "a transfer out the balance does not cover is rejected",
            given = listOf(opened),
            command = Prepare(TARGET_ID, 150),
            rejection = AccountRejections.INSUFFICIENT_BALANCE,
            state = Balances("alice", 100),
        ),
        AccountCase(
            "a transfer out of a frozen account is rejected",
            given = listOf(opened, AccountFrozen("audit")),
            command = Prepare(TARGET_ID, 40),
            rejection = AccountRejections.FROZEN,
            state = Balances("alice", 100, frozen = true),
        ),
        AccountCase(
            "an entry adds to the balance",
            given = listOf(opened),
            command = Entry("z", 40),
            events = listOf(AmountEntered("z", 40)),
            state = Balances("alice", 140),
        ),
        AccountCase(
            "a frozen account refuses an entry with an event",
            given = listOf(opened, AccountFrozen("audit")),
            command = Entry("z", 40),
            events = listOf(EntryFailed("z", 40)),
            state = Balances("alice", 100, frozen = true),
        ),
        AccountCase(
            "a confirmation releases the locked amount",
            given = locked,
            command = Confirm(40),
            events = listOf(Confirmed(40)),
            state = Balances("alice", 60),
        ),
        AccountCase(
            "an unlock gives the locked amount back",
            given = locked,
            command = UnlockAmount(40),
            events = listOf(AmountUnlocked(40)),
            state = Balances("alice", 100),
        ),
        AccountCase(
            "freezing an account marks it frozen",
            given = listOf(opened),
            command = FreezeAccount("audit"),
            events = listOf(AccountFrozen("audit")),
            state = Balances("alice", 100, frozen = true),
        ),
        AccountCase(
            "freezing a frozen account is rejected",
            given = listOf(opened, AccountFrozen("audit")),
            command = FreezeAccount("again"),
            rejection = AccountRejections.FROZEN,
            state = Balances("alice", 100, frozen = true),
        ),
    )

    val SAGA: List<SagaCase> = listOf(
        SagaCase(
            "a prepared transfer enters its amount on the target",
            source = ACCOUNT_ID,
            event = Prepared(TARGET_ID, 40),
            command = Entry(ACCOUNT_ID, 40),
            target = TARGET_ID,
        ),
        SagaCase(
            "an entered amount confirms the transfer on the source",
            source = TARGET_ID,
            event = AmountEntered(ACCOUNT_ID, 40),
            command = Confirm(40),
            target = ACCOUNT_ID,
        ),
        SagaCase(
            "a refused entry unlocks the amount on the source",
            source = TARGET_ID,
            event = EntryFailed(ACCOUNT_ID, 40),
            command = UnlockAmount(40),
            target = ACCOUNT_ID,
        ),
        SagaCase(
            "an opened account derives nothing",
            source = ACCOUNT_ID,
            event = AccountOpened("alice", 100),
        ),
    )
}
