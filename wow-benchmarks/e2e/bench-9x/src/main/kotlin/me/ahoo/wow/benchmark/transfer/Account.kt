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
import me.ahoo.wow.api.annotation.StaticTenantId
import me.ahoo.wow.benchmark.transfer.AccountRejections.FROZEN
import me.ahoo.wow.benchmark.transfer.AccountRejections.INSUFFICIENT_BALANCE
import me.ahoo.wow.benchmark.transfer.AccountRejections.INVALID_AMOUNT
import me.ahoo.wow.benchmark.transfer.AccountRejections.reject

/**
 * The account. Each command function decides on the state alone; a rejection leaves the account as it was.
 *
 * 9.x: a function returns its events and rejects by throwing ([reject]). The account lives in the default tenant
 * ([StaticTenantId]), as V10's does when the caller names none, so its routes carry no tenant either.
 */
@StaticTenantId
@AggregateRoot
class Account(private val state: AccountState) {
    fun onCommand(command: OpenAccount): AccountOpened {
        if (command.balance < 0) reject(INVALID_AMOUNT, "${command.balance} is negative.")
        return AccountOpened(command.owner, command.balance)
    }

    fun onCommand(command: Deposit): Deposited {
        if (command.amount <= 0) reject(INVALID_AMOUNT, "${command.amount} is not positive.")
        return Deposited(command.amount)
    }

    fun onCommand(command: Withdraw): Withdrawn {
        if (command.amount <= 0) reject(INVALID_AMOUNT, "${command.amount} is not positive.")
        if (command.amount > state.balance) reject(INSUFFICIENT_BALANCE, "The balance is ${state.balance}.")
        return Withdrawn(command.amount)
    }

    @OnCommand(returns = [AmountLocked::class, Prepared::class])
    fun onCommand(command: Prepare): List<Any> {
        if (command.amount <= 0) reject(INVALID_AMOUNT, "${command.amount} is not positive.")
        if (state.frozen) reject(FROZEN, "The account is frozen.")
        if (command.amount > state.balance) reject(INSUFFICIENT_BALANCE, "The balance is ${state.balance}.")
        return listOf(AmountLocked(command.amount), Prepared(command.to, command.amount))
    }

    @OnCommand(returns = [AmountEntered::class, EntryFailed::class])
    fun onCommand(command: Entry): Any =
        if (state.frozen) {
            EntryFailed(command.sourceId, command.amount)
        } else {
            AmountEntered(command.sourceId, command.amount)
        }

    fun onCommand(command: Confirm): Confirmed = Confirmed(command.amount)

    fun onCommand(command: UnlockAmount): AmountUnlocked = AmountUnlocked(command.amount)

    fun onCommand(command: FreezeAccount): AccountFrozen {
        if (state.frozen) reject(FROZEN, "The account is frozen.")
        return AccountFrozen(command.reason)
    }
}
