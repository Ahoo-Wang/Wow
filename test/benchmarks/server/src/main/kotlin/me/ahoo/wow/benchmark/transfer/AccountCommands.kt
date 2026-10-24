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

import me.ahoo.wow.api.annotation.CreateAggregate

// The route of each command is derived from the Manifest: `POST /transfer/account/{id}/<kebab name>`, the aggregate id
// the path's.

@CreateAggregate
data class OpenAccount(val owner: String, val balance: Long)

data class Deposit(val amount: Long)

data class Withdraw(val amount: Long)

/** Starts a transfer from this account to [to]: locks [amount] here; the [TransferSaga] does the rest. */
data class Prepare(val to: String, val amount: Long)

/** The transfer saga's entry of [amount] from [sourceId] on the target account. */
data class Entry(val sourceId: String, val amount: Long)

/** The transfer saga's confirmation on the source account: the locked [amount] has left. */
data class Confirm(val amount: Long)

/** The transfer saga's compensation on the source account: the target refused, the locked [amount] comes back. */
data class UnlockAmount(val amount: Long)

/** Freezes the account: it refuses transfers out and entries in, which drives the saga's compensation. */
data class FreezeAccount(val reason: String)

/**
 * Attaches [notes] to the account, one event each: the benchmark's large-payload variant (D224 note ⑥, five events of
 * about 2 KB per command).
 */
data class Annotate(val notes: List<String>)
