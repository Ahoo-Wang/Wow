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

package me.ahoo.wow.runtime.binding

import kotlinx.coroutines.withContext
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.RejectionBinding
import me.ahoo.wow.kernel.rejectionContextOf
import me.ahoo.wow.runtime.command.RejectionHandler

/**
 * Hands the rejections of the commands a context's sagas derive to their `@OnRejected` handlers (decisions D136,
 * D153), and executes the commands those derive to compensate through [derived], with no wait context: the wait at
 * the saga's hop has already ended with the rejection.
 *
 * [handle] is the one entry point, whatever brought the rejection: the saga's own execution of a command of its context
 * that the kernel rejected ([SubscriptionBindings]), or, from roadmap 6.3d, the receipt another context wrote for a
 * command it rejected (decision D136), which carries the same [CommandRejected]. The saga is found by the subscription
 * of the rejection's [CommandRejected.executionId], the saga handler that derived the command: its
 * [ProcessorBinding.rejections] are the candidates, chosen by the command's class
 * ([ProcessorBinding.rejectionHandlerOf]).
 *
 * Each rejection is handled once per command id in effect: the handler's context derives deterministic requests from
 * the rejected command's id ([me.ahoo.wow.kernel.RejectionSagaContext]), so handling it again, after a retry or a
 * second delivery of its receipt, derives the same requests, which the kernel does not decide twice.
 *
 * A command the handler derives that is rejected in turn is not handed to a handler again (a compensation does not
 * compensate itself): it fails the handling with [DerivedCommandRejectedException], which parks the aggregate. A
 * technical failure throws, for the caller to retry.
 */
class SagaRejections(sagas: Collection<ProcessorBinding<*>>, private val derived: DerivedCommands) : RejectionHandler {
    private val sagas: Map<String, ProcessorBinding<*>> =
        sagas.filter { it.kind == FunctionKind.SAGA }.associateBy { it.name }

    /** Whether a saga of this context has an `@OnRejected` handler: only then do its commands ask for receipts. */
    val isEmpty: Boolean get() = sagas.values.all { it.rejections.isEmpty() }

    /**
     * Hands [rejected], the rejection of a command of the class [command], to the `@OnRejected` handler of the saga
     * whose handler derived it, executes what that derives and returns their ids; null when the saga has no handler
     * for it, or [rejected] names no saga handler of this context.
     */
    override suspend fun handle(rejected: CommandRejected<*>, command: Class<*>): List<String>? {
        val handler = sagas[rejected.executionId.subscription]?.rejectionHandlerOf(command) ?: return null
        val compensations = call(handler, rejected)
        val executed = derived.execute(compensations, wait = null)
        executed.rejected?.let { throw DerivedCommandRejectedException(it.command, it.rejection) }
        return executed.executed
    }

    /** Calls [handler] with [rejected] and its context: on a virtual thread when it is blocking. */
    private suspend fun call(handler: RejectionBinding<*>, rejected: CommandRejected<*>) =
        if (handler.blocking) {
            withContext(
                SubscriptionBindings.BLOCKING
            ) { handler.invoke(rejected, rejectionContextOf(handler, rejected)) }
        } else {
            handler.invoke(rejected, rejectionContextOf(handler, rejected))
        }
}
