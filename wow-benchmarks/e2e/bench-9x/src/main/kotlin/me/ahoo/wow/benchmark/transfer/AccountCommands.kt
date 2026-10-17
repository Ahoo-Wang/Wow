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

import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.CreateAggregate

// 9.x names each command's route here; V10 derives `POST /transfer/account/{id}/<kebab name>` from the Manifest. The
// aggregate id is the path's on both.

@CreateAggregate
@CommandRoute(action = "open-account", method = CommandRoute.Method.POST, appendIdPath = CommandRoute.AppendPath.ALWAYS)
data class OpenAccount(val owner: String, val balance: Long)

@CommandRoute(action = "deposit", method = CommandRoute.Method.POST, appendIdPath = CommandRoute.AppendPath.ALWAYS)
data class Deposit(val amount: Long)

@CommandRoute(action = "withdraw", method = CommandRoute.Method.POST, appendIdPath = CommandRoute.AppendPath.ALWAYS)
data class Withdraw(val amount: Long)

/** Starts a transfer from this account to [to]: locks [amount] here; the [TransferSaga] does the rest. */
@CommandRoute(action = "prepare", method = CommandRoute.Method.POST, appendIdPath = CommandRoute.AppendPath.ALWAYS)
data class Prepare(val to: String, val amount: Long)

/** The transfer saga's entry of [amount] from [sourceId] on the target account. */
@CommandRoute(action = "entry", method = CommandRoute.Method.POST, appendIdPath = CommandRoute.AppendPath.ALWAYS)
data class Entry(val sourceId: String, val amount: Long)

/** The transfer saga's confirmation on the source account: the locked [amount] has left. */
@CommandRoute(action = "confirm", method = CommandRoute.Method.POST, appendIdPath = CommandRoute.AppendPath.ALWAYS)
data class Confirm(val amount: Long)

/** The transfer saga's compensation on the source account: the target refused, the locked [amount] comes back. */
@CommandRoute(
    action = "unlock-amount",
    method = CommandRoute.Method.POST,
    appendIdPath = CommandRoute.AppendPath.ALWAYS
)
data class UnlockAmount(val amount: Long)

/** Freezes the account: it refuses transfers out and entries in, which drives the saga's compensation. */
@CommandRoute(
    action = "freeze-account",
    method = CommandRoute.Method.POST,
    appendIdPath = CommandRoute.AppendPath.ALWAYS
)
data class FreezeAccount(val reason: String)
