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

package me.ahoo.wow.example.quickstart

import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.ErrorCode
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.decide.Decision

class AccountState {
    var owner: String = ""
        private set
    var balance: Long = 0
        private set

    fun onSourcing(event: AccountOpened) {
        owner = event.owner
    }

    fun onSourcing(event: Deposited) {
        balance += event.amount
    }

    fun onSourcing(event: Withdrawn) {
        balance -= event.amount
    }
}

@AggregateRoot
class Account(private val state: AccountState) {
    fun onCommand(command: OpenAccount): AccountOpened = AccountOpened(command.owner)

    @OnCommand(produces = [Deposited::class])
    fun onCommand(command: Deposit): Decision =
        if (command.amount <= 0) {
            Decision.reject(Rejections.INVALID_AMOUNT, "${command.amount} is not positive.")
        } else {
            Decision.events(Deposited(command.amount))
        }

    // region withdraw
    @OnCommand(produces = [Withdrawn::class])
    fun onCommand(command: Withdraw): Decision = when {
        command.amount <= 0 ->
            Decision.reject(Rejections.INVALID_AMOUNT, "${command.amount} is not positive.")

        command.amount > state.balance ->
            Decision.reject(Rejections.INSUFFICIENT_BALANCE, "The balance is ${state.balance}.")

        else -> Decision.events(Withdrawn(command.amount))
    }
    // endregion

    /** The codes it rejects with, by local name: the Manifest and its answers say `bank.account.invalid-amount`. */
    object Rejections {
        @ErrorCode(description = "An amount is always positive.")
        const val INVALID_AMOUNT = "invalid-amount"

        @ErrorCode(description = "The balance does not cover the withdrawal.", status = 409)
        const val INSUFFICIENT_BALANCE = "insufficient-balance"
    }
}
