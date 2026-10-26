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

package me.ahoo.wow.routing

import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.kernel.aggregate

/** The spike's aggregate: an account whose decide functions read its balance. */
class AccountState(val id: String) {
    var owner: String = ""
    var balance: Long = 0
    var operations: Int = 0

    /** The latest operations, at most the model's cap: what makes the "large state" workload large. */
    val recent: MutableList<Operation> = ArrayList()
}

data class Operation(val amount: Long, val memo: String)

data class OpenAccount(val owner: String, val initial: Long)

data class Deposit(val amount: Long, val memo: String)

data class Withdraw(val amount: Long, val memo: String)

data class AccountOpened(val owner: String, val initial: Long)

data class Deposited(val amount: Long, val memo: String)

data class Withdrawn(val amount: Long, val memo: String)

/** The account model of [context], keeping the latest [recentCap] operations in its state. */
fun accountModel(context: String, recentCap: Int): AggregateModel<AccountState> =
    aggregate<AccountState>(context, "account") {
        initial { AccountState(it.id) }
        decide<OpenAccount>(Creation.REQUIRED) { _, command ->
            Decision.events(AccountOpened(command.owner, command.initial))
        }
        decide<Deposit> { _, command ->
            if (command.amount <= 0) {
                Decision.reject("account.invalid-amount", "A deposit is positive.")
            } else {
                Decision.events(Deposited(command.amount, command.memo))
            }
        }
        decide<Withdraw> { state, command ->
            if (state.balance < command.amount) {
                Decision.reject("account.insufficient-balance", "${state.id} holds ${state.balance}.")
            } else {
                Decision.events(Withdrawn(command.amount, command.memo))
            }
        }
        evolve<AccountOpened> {
            owner = it.owner
            balance = it.initial
        }
        evolve<Deposited> {
            balance += it.amount
            remember(Operation(it.amount, it.memo), recentCap)
        }
        evolve<Withdrawn> {
            balance -= it.amount
            remember(Operation(-it.amount, it.memo), recentCap)
        }
    }

private fun AccountState.remember(operation: Operation, cap: Int) {
    operations++
    if (cap == 0) {
        return
    }
    recent += operation
    if (recent.size > cap) {
        recent.removeAt(0)
    }
}
