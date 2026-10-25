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

package me.ahoo.wow.benchmark.axon

import org.axonframework.commandhandling.CommandHandler
import org.axonframework.eventsourcing.EventSourcingHandler
import org.axonframework.modelling.command.AggregateCreationPolicy
import org.axonframework.modelling.command.AggregateIdentifier
import org.axonframework.modelling.command.AggregateLifecycle.apply
import org.axonframework.modelling.command.AggregateLifecycle.getVersion
import org.axonframework.modelling.command.CreationPolicy
import org.axonframework.spring.stereotype.Aggregate

/**
 * The account, event sourced, with the rules of the Wow benchmark's `Account` (test/benchmarks/server): a deposit
 * of a positive amount; a transfer out of a positive amount the balance covers, from an account not frozen; an entry
 * in that fails on a frozen account. Its repository caches it ([AxonConfiguration.ACCOUNT_CACHE]) and snapshots it
 * ([AxonConfiguration.ACCOUNT_SNAPSHOTS]), Axon's production configuration. A command handler answers the
 * aggregate's version after its events, as Wow's command result does.
 */
@Aggregate(cache = AxonConfiguration.ACCOUNT_CACHE, snapshotTriggerDefinition = AxonConfiguration.ACCOUNT_SNAPSHOTS)
class Account() {
    @AggregateIdentifier
    var id: String? = null
    var owner: String? = null
    var balance: Long = 0
    var locked: Long = 0
    var frozen: Boolean = false

    @CommandHandler
    @CreationPolicy(AggregateCreationPolicy.ALWAYS)
    fun handle(command: OpenAccount): Long? {
        if (command.balance < 0) throw AccountRejected(INVALID_AMOUNT, "${command.balance} is negative.")
        apply(AccountOpened(command.id, command.owner, command.balance))
        return getVersion()
    }

    @CommandHandler
    fun handle(command: Deposit): Long? {
        if (command.amount <= 0) throw AccountRejected(INVALID_AMOUNT, "${command.amount} is not positive.")
        apply(Deposited(command.amount))
        return getVersion()
    }

    @CommandHandler
    fun handle(command: Prepare): Long? {
        if (command.amount <= 0) throw AccountRejected(INVALID_AMOUNT, "${command.amount} is not positive.")
        if (frozen) throw AccountRejected(FROZEN, "The account is frozen.")
        if (command.amount > balance) throw AccountRejected(INSUFFICIENT_BALANCE, "The balance is $balance.")
        apply(AmountLocked(command.amount))
        apply(Prepared(command.id, command.to, command.amount, command.transferId))
        return getVersion()
    }

    @CommandHandler
    fun handle(command: Entry): Long? {
        if (frozen) {
            apply(EntryFailed(command.sourceId, command.amount, command.transferId))
        } else {
            apply(AmountEntered(command.sourceId, command.amount, command.transferId))
        }
        return getVersion()
    }

    @CommandHandler
    fun handle(command: Confirm): Long? {
        apply(Confirmed(command.amount))
        return getVersion()
    }

    @CommandHandler
    fun handle(command: UnlockAmount): Long? {
        apply(AmountUnlocked(command.amount))
        return getVersion()
    }

    @EventSourcingHandler
    fun on(event: AccountOpened) {
        id = event.id
        owner = event.owner
        balance = event.balance
    }

    @EventSourcingHandler
    fun on(event: Deposited) {
        balance += event.amount
    }

    @EventSourcingHandler
    fun on(event: AmountLocked) {
        balance -= event.amount
        locked += event.amount
    }

    @EventSourcingHandler
    fun on(event: AmountEntered) {
        balance += event.amount
    }

    @EventSourcingHandler
    fun on(event: Confirmed) {
        locked -= event.amount
    }

    @EventSourcingHandler
    fun on(event: AmountUnlocked) {
        locked -= event.amount
        balance += event.amount
    }

    companion object {
        const val INVALID_AMOUNT = "invalid-amount"
        const val INSUFFICIENT_BALANCE = "insufficient-balance"
        const val FROZEN = "frozen"
    }
}
