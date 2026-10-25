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

import org.axonframework.modelling.command.TargetAggregateIdentifier

// The commands and events of the Wow benchmark's account (test/benchmarks/server, `AccountCommands.kt` and
// `AccountEvents.kt`), as Axon carries them: a command names its account, an event of a transfer its transfer.

data class OpenAccount(@TargetAggregateIdentifier val id: String, val owner: String, val balance: Long)

data class Deposit(@TargetAggregateIdentifier val id: String, val amount: Long)

/** A transfer of [amount] from account [id] to [to]; [transferId] lets the HTTP request wait for its entry. */
data class Prepare(@TargetAggregateIdentifier val id: String, val to: String, val amount: Long, val transferId: String)

data class Entry(
    @TargetAggregateIdentifier val id: String,
    val sourceId: String,
    val amount: Long,
    val transferId: String,
)

data class Confirm(@TargetAggregateIdentifier val id: String, val amount: Long)

data class UnlockAmount(@TargetAggregateIdentifier val id: String, val amount: Long)

data class AccountOpened(val id: String, val owner: String, val balance: Long)

data class Deposited(val amount: Long)

data class AmountLocked(val amount: Long)

data class Prepared(val sourceId: String, val to: String, val amount: Long, val transferId: String)

data class AmountEntered(val sourceId: String, val amount: Long, val transferId: String)

data class EntryFailed(val sourceId: String, val amount: Long, val transferId: String)

data class Confirmed(val amount: Long)

data class AmountUnlocked(val amount: Long)

/** A command the account refuses, as the Wow benchmark's rejections ([code]: `invalid-amount`, ...). */
class AccountRejected(val code: String, message: String) : RuntimeException(message)
