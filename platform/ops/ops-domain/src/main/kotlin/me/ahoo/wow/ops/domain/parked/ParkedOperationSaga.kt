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

package me.ahoo.wow.ops.domain.parked

import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.OnRejected
import me.ahoo.wow.api.annotation.Saga
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.ledger.ApplyParkedRetrySpec
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.ledger.LedgerCommand
import me.ahoo.wow.api.ledger.MarkParkedRecoverable
import me.ahoo.wow.api.ledger.ResumeParked
import me.ahoo.wow.api.ledger.SkipParked
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.ops.api.parked.ApplyRetrySpecRequested
import me.ahoo.wow.ops.api.parked.MarkRecoverableRequested
import me.ahoo.wow.ops.api.parked.RecordActionRefused
import me.ahoo.wow.ops.api.parked.ResumeRequested
import me.ahoo.wow.ops.api.parked.SkipRequested

/**
 * Sends an operator's request on a [Parked] entry back to the service that owns the entry: the ledger's
 * system command of the same name (`wow.ledger.Resume`, `Skip`, `MarkRecoverable`, `ApplyRetrySpec`), to the command
 * topic of the context that holds the entry ([ParkedState.context]), addressed to the entry ([Ledger.targetOf]). The
 * owning service takes it because this saga of the ops context sent it (the operator was authorized
 * here, and keeps being the command's), runs it on its ledger, and publishes the entry as it then
 * stands, which comes back as a record.
 *
 * A command the owning service refuses comes back as its rejection's receipt ([onActionRejected]), which the entry
 * records as [RecordActionRefused], so the console shows why the operator's request came to nothing. Taking the
 * receipts is also what makes each command carry this context as its source.
 */
@Saga
class ParkedOperationSaga {
    @OnEvent
    fun onParkedChanged(event: StateEvent<ParkedState>): CommandRequest<LedgerCommand>? {
        val state = event.state
        val subscription = state.subscription
        val aggregate = state.aggregate
        val owner = aggregate.context
        val name = aggregate.name
        val tenantId = aggregate.tenantId
        val id = aggregate.id
        val command = when (val requested = event.events.lastOrNull()) {
            // The parking the operator acted on: the owning service refuses it when it holds another.
            is ResumeRequested -> ResumeParked.of(subscription, owner, name, tenantId, id, requested.failedAt)
            is SkipRequested -> SkipParked.of(subscription, owner, name, tenantId, id, requested.failedAt)
            is MarkRecoverableRequested ->
                MarkParkedRecoverable.of(subscription, owner, name, tenantId, id, requested.recoverable)
            is ApplyRetrySpecRequested -> ApplyParkedRetrySpec.of(
                subscription,
                owner,
                name,
                tenantId,
                id,
                requested.maxRetries,
                requested.initialBackoff,
                requested.timeout,
            )

            else -> return null
        }
        return CommandRequest.to(Ledger.targetOf(state.context, event.ref.id), command)
    }

    /** The owning service refused a command this saga sent for the entry: the entry records the refusal. */
    @OnRejected
    fun onActionRejected(rejected: CommandRejected<*>): CommandRequest<RecordActionRefused> =
        CommandRequest.to(
            rejected.source,
            RecordActionRefused(rejected.commandType, rejected.error.code, rejected.error.message),
        )
}
