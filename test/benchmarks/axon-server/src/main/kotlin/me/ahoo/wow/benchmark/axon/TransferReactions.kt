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
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.Executors

/**
 * The transfer's reactions, the Wow benchmark's `TransferSaga` (test/benchmarks/server): `Prepared` → `Entry` on the
 * target, `AmountEntered` → `Confirm` on the source, `EntryFailed` → `UnlockAmount` on the source. Like Wow's, it keeps
 * no state of its own, so it is an event handler of a tracking processor (group [GROUP], its tokens in PostgreSQL,
 * `application.yaml`) rather than an Axon saga, which would store an instance per transfer.
 *
 * A command it sends is handled in the processor's unit of work (`benchmark.axon.reactions` `nested`, the default): its
 * events are committed with the processor's token, and the target's lock is held until then. With `separate` each
 * reaction is a unit of work of its own, on a virtual thread, and the processor goes on without waiting for it: the
 * diagnostic that tells the nested units of work apart (axon-pg-e3-reactions). Either way the transfer's waiter learns
 * of the entry once it is committed.
 */
@Component
@ProcessingGroup(TransferReactions.GROUP)
class TransferReactions(
    private val commands: CommandGateway,
    private val waits: TransferWaits,
    @Value("\${benchmark.axon.reactions:nested}") reactions: String,
) {
    private val separate = reactions == "separate"
    private val senders = Executors.newVirtualThreadPerTaskExecutor()

    @EventHandler
    fun on(event: Prepared) = react {
        val entered = commands.sendAndWait<Long?>(Entry(event.to, event.sourceId, event.amount, event.transferId))
        afterCommit { waits.entered(event.transferId, event.to, entered) }
    }

    @EventHandler
    fun on(event: AmountEntered) = react {
        commands.sendAndWait<Long?>(Confirm(event.sourceId, event.amount))
    }

    @EventHandler
    fun on(event: EntryFailed) = react {
        commands.sendAndWait<Long?>(UnlockAmount(event.sourceId, event.amount))
    }

    /** Runs [reaction] here, in the processor's unit of work, or with `separate` on a virtual thread of its own. */
    private fun react(reaction: () -> Unit) {
        if (!separate) return reaction()
        senders.execute {
            try {
                reaction()
            } catch (failure: RuntimeException) {
                log.warn("A transfer reaction failed", failure)
            }
        }
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
        private val log = LoggerFactory.getLogger(TransferReactions::class.java)
    }
}
