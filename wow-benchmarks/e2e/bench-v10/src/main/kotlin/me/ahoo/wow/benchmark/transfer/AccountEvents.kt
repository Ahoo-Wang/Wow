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

data class AccountOpened(val owner: String, val balance: Long)

data class Deposited(val amount: Long)

data class Withdrawn(val amount: Long)

data class AmountLocked(val amount: Long)

data class Prepared(val to: String, val amount: Long)

data class AmountEntered(val sourceId: String, val amount: Long)

data class EntryFailed(val sourceId: String, val amount: Long)

data class Confirmed(val amount: Long)

data class AmountUnlocked(val amount: Long)

data class AccountFrozen(val reason: String)

/** One note of [Annotate]. */
data class Annotated(val note: String)
