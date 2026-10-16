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
import me.ahoo.wow.api.annotation.StatelessSaga
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.command.factory.CommandBuilder
import me.ahoo.wow.command.factory.CommandBuilder.Companion.commandBuilder

/**
 * The transfer between two accounts: the source has locked the amount ([Prepare]); the saga enters it on the target,
 * then confirms it on the source, or unlocks it on the source when the target refused it.
 *
 * 9.x: a handler takes one event and returns the command it derives, addressed with a command builder.
 */
@StatelessSaga
class TransferSaga {
    @OnEvent
    fun onPrepared(event: DomainEvent<Prepared>): CommandBuilder =
        Entry(event.aggregateId.id, event.body.amount).commandBuilder().aggregateId(event.body.to)

    @OnEvent
    fun onAmountEntered(event: DomainEvent<AmountEntered>): CommandBuilder =
        Confirm(event.body.amount).commandBuilder().aggregateId(event.body.sourceId)

    @OnEvent
    fun onEntryFailed(event: DomainEvent<EntryFailed>): CommandBuilder =
        UnlockAmount(event.body.amount).commandBuilder().aggregateId(event.body.sourceId)
}
