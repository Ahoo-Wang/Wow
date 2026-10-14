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
import me.ahoo.wow.api.annotation.Saga
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.ledger.ApplyParkedRetrySpec
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.ledger.MarkParkedRecoverable
import me.ahoo.wow.api.ledger.ResumeParked
import me.ahoo.wow.api.ledger.SkipParked
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.ops.api.parked.ApplyRetrySpecRequested
import me.ahoo.wow.ops.api.parked.MarkRecoverableRequested
import me.ahoo.wow.ops.api.parked.ResumeRequested
import me.ahoo.wow.ops.api.parked.SkipRequested

/**
 * Sends an operator's request on a [Parked] entry back to the service that owns the entry (decision D141): the ledger's
 * system command of the same name (`wow.ledger.Resume`, `Skip`, `MarkRecoverable`, `ApplyRetrySpec`), to the command
 * topic of the context that holds the entry ([ParkedState.context]), addressed to the entry ([Ledger.targetOf]). The
 * owning service authorizes it as the operator who asked (the command keeps the operator of the root command, decision
 * D163), runs it on its ledger, and publishes the entry as it then stands, which comes back as a record.
 */
@Saga
class ParkedOperationSaga {
    @OnEvent
    fun onParkedChanged(event: StateEvent<ParkedState>, context: SagaContext): Command<*>? {
        val state = event.state
        val subscription = state.subscription
        val aggregate = state.aggregate
        val command = when (val requested = event.events.lastOrNull()) {
            is ResumeRequested -> ResumeParked(subscription, aggregate)
            is SkipRequested -> SkipParked(subscription, aggregate)
            is MarkRecoverableRequested -> MarkParkedRecoverable(subscription, aggregate, requested.recoverable)
            is ApplyRetrySpecRequested -> ApplyParkedRetrySpec(
                subscription,
                aggregate,
                requested.maxRetries,
                requested.minBackoff,
                requested.executionTimeout,
            )

            else -> return null
        }
        return context.command(Ledger.targetOf(state.context, event.ref.id), command)
    }
}
