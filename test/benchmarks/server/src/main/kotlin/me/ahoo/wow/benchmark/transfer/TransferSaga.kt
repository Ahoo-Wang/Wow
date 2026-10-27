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

import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.Saga
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.benchmark.transfer.TransferContext.ACCOUNT_TYPE

/**
 * The transfer between two accounts: the source has locked the amount ([Prepare]); the saga enters it on the target,
 * then confirms it on the source, or unlocks it on the source when the target refused it.
 *
 * V10: a handler takes each commit of the account with its events, and returns the commands it derives, addressed by
 * their aggregate's reference.
 */
@Saga
class TransferSaga {
    @OnEvent
    fun onPrepared(event: StateEvent<AccountState>): List<CommandRequest<Entry>> =
        event.events.filterIsInstance<Prepared>().map {
            CommandRequest.to(ACCOUNT_TYPE.ref(event.ref.tenantId, it.to), Entry(event.ref.id, it.amount))
        }

    @OnEvent
    fun onAmountEntered(event: StateEvent<AccountState>): List<CommandRequest<Confirm>> =
        event.events.filterIsInstance<AmountEntered>().map {
            CommandRequest.to(ACCOUNT_TYPE.ref(event.ref.tenantId, it.sourceId), Confirm(it.amount))
        }

    @OnEvent
    fun onEntryFailed(event: StateEvent<AccountState>): List<CommandRequest<UnlockAmount>> =
        event.events.filterIsInstance<EntryFailed>().map {
            CommandRequest.to(ACCOUNT_TYPE.ref(event.ref.tenantId, it.sourceId), UnlockAmount(it.amount))
        }
}
