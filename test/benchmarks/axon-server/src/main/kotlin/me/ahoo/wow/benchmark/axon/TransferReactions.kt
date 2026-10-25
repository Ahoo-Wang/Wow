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

import org.axonframework.commandhandling.gateway.CommandGateway
import org.axonframework.config.ProcessingGroup
import org.axonframework.eventhandling.EventHandler
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork
import org.springframework.stereotype.Component

/**
 * The transfer's reactions, the Wow benchmark's `TransferSaga` (test/benchmarks/server): `Prepared` → `Entry` on the
 * target, `AmountEntered` → `Confirm` on the source, `EntryFailed` → `UnlockAmount` on the source. Like Wow's, it keeps
 * no state of its own, so it is an event handler of a tracking processor (group [GROUP], its tokens in PostgreSQL,
 * `application.yaml`) rather than an Axon saga, which would store an instance per transfer.
 *
 * A command it sends is handled in the processor's unit of work, its events committed with the processor's token; the
 * transfer's waiter learns of the entry once that commits.
 */
@Component
@ProcessingGroup(TransferReactions.GROUP)
class TransferReactions(private val commands: CommandGateway, private val waits: TransferWaits) {
    @EventHandler
    fun on(event: Prepared) {
        val entered = commands.sendAndWait<Long?>(Entry(event.to, event.sourceId, event.amount, event.transferId))
        afterCommit { waits.entered(event.transferId, event.to, entered) }
    }

    @EventHandler
    fun on(event: AmountEntered) {
        commands.sendAndWait<Long?>(Confirm(event.sourceId, event.amount))
    }

    @EventHandler
    fun on(event: EntryFailed) {
        commands.sendAndWait<Long?>(UnlockAmount(event.sourceId, event.amount))
    }

    private fun afterCommit(action: () -> Unit) {
        if (CurrentUnitOfWork.isStarted()) {
            CurrentUnitOfWork.get().afterCommit { action() }
        } else {
            action()
        }
    }

    companion object {
        const val GROUP = "transfer"
    }
}
