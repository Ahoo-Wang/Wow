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

import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.benchmark.transfer.AccountRejections.FROZEN
import me.ahoo.wow.benchmark.transfer.AccountRejections.INSUFFICIENT_BALANCE
import me.ahoo.wow.benchmark.transfer.AccountRejections.INVALID_AMOUNT
import me.ahoo.wow.benchmark.transfer.AccountRejections.reject

/**
 * The account. Each command function decides on the state alone; a rejection leaves the account as it was.
 *
 * V10: a function that may reject returns a [Decision], its events or its rejection ([reject]).
 */
@AggregateRoot
class Account(private val state: AccountState) {
    @OnCommand(produces = [AccountOpened::class])
    fun onCommand(command: OpenAccount): Decision {
        if (command.balance < 0) return reject(INVALID_AMOUNT, "${command.balance} is negative.")
        return Decision.events(AccountOpened(command.owner, command.balance))
    }

    @OnCommand(produces = [Deposited::class])
    fun onCommand(command: Deposit): Decision {
        if (command.amount <= 0) return reject(INVALID_AMOUNT, "${command.amount} is not positive.")
        return Decision.events(Deposited(command.amount))
    }

    @OnCommand(produces = [Withdrawn::class])
    fun onCommand(command: Withdraw): Decision {
        if (command.amount <= 0) return reject(INVALID_AMOUNT, "${command.amount} is not positive.")
        if (command.amount > state.balance) return reject(INSUFFICIENT_BALANCE, "The balance is ${state.balance}.")
        return Decision.events(Withdrawn(command.amount))
    }

    @OnCommand(produces = [AmountLocked::class, Prepared::class])
    fun onCommand(command: Prepare): Decision {
        if (command.amount <= 0) return reject(INVALID_AMOUNT, "${command.amount} is not positive.")
        if (state.frozen) return reject(FROZEN, "The account is frozen.")
        if (command.amount > state.balance) return reject(INSUFFICIENT_BALANCE, "The balance is ${state.balance}.")
        return Decision.events(AmountLocked(command.amount), Prepared(command.to, command.amount))
    }

    @OnCommand(produces = [AmountEntered::class, EntryFailed::class])
    fun onCommand(command: Entry): Decision =
        if (state.frozen) {
            Decision.events(EntryFailed(command.sourceId, command.amount))
        } else {
            Decision.events(AmountEntered(command.sourceId, command.amount))
        }

    fun onCommand(command: Confirm): Confirmed = Confirmed(command.amount)

    fun onCommand(command: UnlockAmount): AmountUnlocked = AmountUnlocked(command.amount)

    @OnCommand(produces = [Annotated::class])
    fun onCommand(command: Annotate): Decision {
        if (command.notes.isEmpty()) return reject(INVALID_AMOUNT, "No notes.")
        return Decision.events(*command.notes.map(::Annotated).toTypedArray())
    }

    @OnCommand(produces = [AccountFrozen::class])
    fun onCommand(command: FreezeAccount): Decision {
        if (state.frozen) return reject(FROZEN, "The account is frozen.")
        return Decision.events(AccountFrozen(command.reason))
    }
}
