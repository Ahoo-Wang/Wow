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

/** The account: its owner, the balance it may spend, the amount locked by transfers in flight, and whether frozen. */
class AccountState {
    var owner: String = ""
        private set
    var balance: Long = 0
        private set
    var locked: Long = 0
        private set
    var frozen: Boolean = false
        private set

    fun onSourcing(event: AccountOpened) {
        owner = event.owner
        balance = event.balance
    }

    fun onSourcing(event: Deposited) {
        balance += event.amount
    }

    fun onSourcing(event: Withdrawn) {
        balance -= event.amount
    }

    fun onSourcing(event: AmountLocked) {
        balance -= event.amount
        locked += event.amount
    }

    /** The transfer's request to the saga: the lock that came with it changed the balance. */
    @Suppress("UnusedParameter")
    fun onSourcing(event: Prepared) = Unit

    fun onSourcing(event: AmountEntered) {
        balance += event.amount
    }

    /** The target's refusal, for the saga: the entry changed nothing. */
    @Suppress("UnusedParameter")
    fun onSourcing(event: EntryFailed) = Unit

    fun onSourcing(event: Confirmed) {
        locked -= event.amount
    }

    fun onSourcing(event: AmountUnlocked) {
        locked -= event.amount
        balance += event.amount
    }

    @Suppress("UnusedParameter")
    fun onSourcing(event: AccountFrozen) {
        frozen = true
    }

    /** A note changes nothing the account decides on. */
    @Suppress("UnusedParameter")
    fun onSourcing(event: Annotated) = Unit
}
