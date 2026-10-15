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
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.id.Ids
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.subscribe.CommandCommitted
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.committedContextOf
import me.ahoo.wow.kernel.rejectionContextOf
import me.ahoo.wow.runtime.command.ReceiptHandler
import me.ahoo.wow.runtime.wait.subscriptionFunction
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.wait.WaitContext

/**
 * Hands the receipts of the commands a context's sagas derive to their handlers, and executes the commands those
 * derive through [derived].
 *
 * - **Rejections** (decisions D136, D153) go to the saga's `@OnRejected` handler of the command ([rejected]); the wait
 *   at the saga's hop has already ended with the rejection.
 * - **Commits** (decision D192) go to its `@OnCommitted` handler ([committed]). The handler is a saga's hop of a
 *   wait chain: when the wait the receipt carries waits for it next, its commands are ordinary derived commands that
 *   carry the rest of the chain on ([DerivedCommands.waitOf]), and the hop is signalled through [signal] with their
 *   ids, as a saga's hop is, REJECTED when one of them is rejected. Its compensations, after a rejection, carry no
 *   wait: the wait at the saga's hop has already ended with the rejection.
 *
 * Either is the one entry point, whatever brought the receipt: the saga's own execution of a command of its context
 * ([SubscriptionBindings]), or the receipt another context wrote ([me.ahoo.wow.runtime.command.Receipts]), which
 * carries the same [CommandRejected] or [CommandCommitted]. The saga is found by the subscription of the receipt's
 * execution id, the saga handler that derived the command: its [ProcessorBinding.rejections] or
 * [ProcessorBinding.commits] are the candidates, chosen by the command's class.
 *
 * Each receipt is handled once per command id in effect: the handler's context derives deterministic requests from the
 * command's id ([me.ahoo.wow.kernel.ReceiptSagaContext]), so handling it again, after a retry or a second delivery of
 * its receipt, derives the same requests, which the kernel does not decide twice.
 *
 * A command a handler derives that is rejected in turn is not handed to a handler again (a compensation does not
 * compensate itself): it fails the handling with [DerivedCommandRejectedException], which parks the aggregate. A
 * technical failure throws, for the caller to retry.
 */
internal class SagaReceipts(
    sagas: Collection<ProcessorBinding<*>>,
    private val derived: DerivedCommands,
    /** The ids the handlers take (decision D171): the node's generators. */
    private val ids: Ids = IdGenerators.DEFAULT,
    /** The sagas' context, whose hops [signal] signals. */
    private val context: String = "",
    /** Signals a hop to the node that waits for it ([me.ahoo.wow.runtime.wait.WaitCoordinator.signal]). */
    private val signal: suspend (WaitContext, CommandResult) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
) : ReceiptHandler {
    private val sagas: Map<String, ProcessorBinding<*>> =
        sagas.filter { it.kind == FunctionKind.SAGA }.associateBy { it.name }

    /**
     * Whether no saga of this context has an `@OnRejected` or `@OnCommitted` handler: only those ask for receipts.
     */
    val isEmpty: Boolean get() = sagas.values.all { it.rejections.isEmpty() && it.commits.isEmpty() }

    /**
     * Hands [rejected], the rejection of a command of the class [command], to the `@OnRejected` handler of the saga
     * whose handler derived it, executes what that derives and returns their ids; null when the saga has no handler
     * for it, or [rejected] names no saga handler of this context.
     */
    override suspend fun rejected(rejected: CommandRejected<*>, command: Class<*>): List<String>? {
        val handler = sagas[rejected.executionId.subscription]?.rejectionHandlerOf(command) ?: return null
        val context = rejectionContextOf(handler, rejected, ids)
        val executed = derived.execute(call(handler.blocking) { handler.invoke(rejected, context) }, wait = null)
        executed.rejected?.let { throw DerivedCommandRejectedException(it.command, it.rejection) }
        return executed.executed
    }

    /**
     * Hands [committed], the success receipt of a command of the class [command], to the `@OnCommitted` handler of
     * the saga whose handler derived it, executes what that derives and returns their ids; null when the saga has no
     * handler for it, or [committed] names no saga handler of this context. When [wait] waits for the handler next,
     * its commands carry the rest of the chain and the hop is signalled (decision D192).
     */
    override suspend fun committed(
        committed: CommandCommitted<*>,
        command: Class<*>,
        wait: WaitContext?,
    ): List<String>? {
        val handler = sagas[committed.executionId.subscription]?.committedHandlerOf(command) ?: return null
        val sagaContext = committedContextOf(handler, committed, ids)
        val commands = call(handler.blocking) { handler.invoke(committed, sagaContext) }
        val executed = derived.execute(commands, DerivedCommands.waitOf(wait, context, handler.name))
        val rejected = executed.rejected
        signalHop(wait, handler.name, committed, executed.executed, rejected?.error)
        rejected?.let { throw DerivedCommandRejectedException(it.command, it.rejection) }
        return executed.executed
    }

    /**
     * Signals the hop of the `@OnCommitted` handler [name] when [wait] waits for it next and is not over: the
     * receipt's command reached it, SUCCEEDED with the commands it [derived], or REJECTED with [error].
     */
    private suspend fun signalHop(
        wait: WaitContext?,
        name: String,
        committed: CommandCommitted<*>,
        derived: List<String>,
        error: ErrorInfo?,
    ) {
        val hop = wait?.nextHandled ?: return
        val now = clock()
        if (hop.subscription != name || (hop.context ?: context) != context || wait.expiredAt(now)) {
            return
        }
        val result = CommandResult(
            commandId = committed.commandId,
            requestId = committed.result.requestId,
            correlationId = committed.causation.correlationId,
            ref = committed.ref,
            hop = hop,
            function = subscriptionFunction(FunctionKind.SAGA, context, name),
            status = if (error == null) CommandResult.Status.SUCCEEDED else CommandResult.Status.REJECTED,
            version = committed.version,
            error = error,
            derived = derived,
            signalTime = now,
        )
        signal(wait, result)
    }

    /** Calls [handle]: on a virtual thread when it is [blocking]. */
    private suspend fun call(blocking: Boolean, handle: suspend () -> List<Command<*>>): List<Command<*>> =
        if (blocking) withContext(SubscriptionBindings.BLOCKING) { handle() } else handle()
}
