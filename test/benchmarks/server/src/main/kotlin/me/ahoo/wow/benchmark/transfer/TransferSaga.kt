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
import me.ahoo.wow.api.subscribe.StateTypedEvent
import me.ahoo.wow.benchmark.transfer.TransferContext.ACCOUNT_TYPE

/**
 * The transfer between two accounts: the source has locked the amount ([Prepare]); the saga enters it on the target,
 * then confirms it on the source, or unlocks it on the source when the target refused it.
 *
 * V10: each handler names the one event it acts on, so a commit of the account without it is passed over before its
 * events are decoded and its state read; it is called once per such event and returns the command it derives,
 * addressed by its aggregate's reference.
 */
@Saga
class TransferSaga {
    @OnEvent
    fun onPrepared(event: StateTypedEvent<AccountState, Prepared>): CommandRequest<Entry> =
        CommandRequest.to(
            ACCOUNT_TYPE.ref(event.ref.tenantId, event.event.to),
            Entry(event.ref.id, event.event.amount),
        )

    @OnEvent
    fun onAmountEntered(event: StateTypedEvent<AccountState, AmountEntered>): CommandRequest<Confirm> =
        CommandRequest.to(ACCOUNT_TYPE.ref(event.ref.tenantId, event.event.sourceId), Confirm(event.event.amount))

    @OnEvent
    fun onEntryFailed(event: StateTypedEvent<AccountState, EntryFailed>): CommandRequest<UnlockAmount> =
        CommandRequest.to(ACCOUNT_TYPE.ref(event.ref.tenantId, event.event.sourceId), UnlockAmount(event.event.amount))
}
