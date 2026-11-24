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
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.id.Ids
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.subscribe.CommandReceipt
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.kernel.CallRejectedHandlingException
import me.ahoo.wow.kernel.CommandTypes
import me.ahoo.wow.kernel.ContextQueries
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.HandlingRejectedException
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.RejectionLog
import me.ahoo.wow.kernel.committedContextOf
import me.ahoo.wow.kernel.rejectionContextOf
import me.ahoo.wow.runtime.call.SagaCalls
import me.ahoo.wow.runtime.cluster.ReplyTo
import me.ahoo.wow.runtime.command.ReceiptHandler
import me.ahoo.wow.runtime.wait.subscriptionFunction
import me.ahoo.wow.spi.call.CommandCaller
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.wait.WaitContext
import java.time.Clock

/**
 * Hands the receipts of the commands a context's sagas derive to their handlers, and executes the commands those
 * derive through [derived].
 *
 * - **Rejections** go to the saga's `@OnRejected` handler of the command ([rejected]); the wait
 *   at the saga's hop has already ended with the rejection.
 * - **Commits** go to its `@OnCommitted` handler ([committed]). The handler is a saga's hop of a
 *   wait chain: when the wait the receipt carries waits for it next, its commands are ordinary derived commands that
 *   carry the rest of the chain on ([DerivedCommands.waitOf]), and the hop is signalled through [signal] with their
 *   ids, as a saga's hop is, REJECTED when one of them is rejected. Its compensations, after a rejection, carry no
 *   wait: the wait at the saga's hop has already ended with the rejection.
 *
 * Either is the one entry point, whatever brought the receipt: the saga's own execution of a command of its context
 * ([SubscriptionBindings]), or the receipt another context wrote ([me.ahoo.wow.runtime.command.Receipts]), which
 * carries the same [CommandReceipt.Rejected] or [CommandReceipt.Committed]. The saga is found by the subscription of the receipt's
 * execution id, the saga handler that derived the command: its [ProcessorBinding.rejections] or
 * [ProcessorBinding.commits] are the candidates, chosen by the command's class.
 *
 * Each receipt is handled once per command id in effect: the handler's context derives deterministic requests from the
 * command's id ([me.ahoo.wow.kernel.ExecutionReceiptContext]), so handling it again, after a retry or a second delivery of
 * its receipt, derives the same requests, which the kernel does not decide twice.
 *
 * A command a handler derives that is rejected in turn is not handed to a handler again (a compensation does not
 * compensate itself): every such command of the context is still executed, and their rejections fail the handling
 * together with [DerivedCommandRejectedException], which parks the aggregate once, under the subscription that ran the
 * handler. One sent to another context asks for its rejection's receipt under the handler's own execution,
 * never for its commit's; that receipt comes back on the receipts subscription, names no saga's `@OnEvent` handler, and
 * parks the aggregate there at once ([CompensationRejectedException]). A synchronous call's rejection the handler lets
 * through is not handed to a handler either: it parks the aggregate at once ([CallRejectedHandlingException]), as the
 * rejection of a call a saga's `@OnEvent` handler made does when no `@OnRejected` handler takes it, and an
 * `@OnCommitted` handler's hop ends REJECTED with it. A technical failure throws, for the caller to retry.
 */
internal class SagaReceipts(
    sagas: Collection<ProcessorBinding<*>>,
    private val derived: DerivedCommands,
    /** The ids the handlers take: the node's generators. */
    private val ids: Ids = IdGenerators.DEFAULT,
    /** The sagas' context, whose hops [signal] signals. */
    private val context: String = "",
    /** Signals a hop to the node that waits for it ([me.ahoo.wow.runtime.wait.WaitCoordinator.signal]). */
    private val signal: suspend (WaitContext, CommandResult) -> Unit = { _, _ -> },
    private val clock: Clock = Clock.systemUTC(),
    /** Assigns the commands a handler leaves the ref out of their aggregate type. */
    private val commandTypes: CommandTypes = CommandTypes.NONE,
    /** The handlers' queries, by the commit's tenant. */
    private val queries: ContextQueries = ContextQueries.NONE,
    /** The client of the other contexts' command gateways the handlers' synchronous calls go through. */
    private val caller: CommandCaller? = null,
    /** The contexts of the node, which a handler derives its commands to rather than call. */
    private val served: Set<String> = setOfNotNull(context.takeIf { it.isNotEmpty() }),
    /** The node's codec, which writes the commands the handlers schedule. */
    private val codec: EventCodec = EventCodec(),
    /**
     * Whether the context reads the receipts of the commands its sagas send to other contexts ([ReceiptsAsked.of]):
     * only then does it have its receipts subscription and topic.
     */
    val reads: Boolean = true,
) : ReceiptHandler {
    private val sagas: Map<String, ProcessorBinding<*>> =
        sagas.filter { it.kind == FunctionKind.SAGA }.associateBy { it.name }

    /** The names of the sagas' receipt handlers, whose executions the receipts of their own commands name. */
    private val receiptHandlers: Set<String> = this.sagas.values.flatMapTo(HashSet()) { saga ->
        saga.rejections.map { it.name } + saga.commits.map { it.name }
    }

    /**
     * Refuses the [commands] the saga handler [handler] derived when one goes to another context while the context
     * reads no receipts ([reads] false): its rejection's receipt would go to a topic the context does not have, and
     * wait at the target, or be dropped ([UnreadReceiptsException]). It allocates nothing: the context's own aggregate
     * types are [derived]'s.
     */
    fun checkReads(handler: String, commands: List<Command<*>>) {
        if (reads) {
            return
        }
        val elsewhere = commands.firstOrNull { !derived.decides(it.ref.type) } ?: return
        throw UnreadReceiptsException(handler, context, elsewhere)
    }

    /**
     * Where the receipt of the rejection of a command the receipt handler [handler] derived goes, when it goes to
     * another context: back to this context, under the handler's own [execution], which names no saga's `@OnEvent`
     * handler, so the receipt is handed to no handler ([rejected]). Its commit asks for no receipt: a receipt handler's
     * command of the context hands its commit to no `@OnCommitted` handler either.
     */
    private fun replyTo(execution: ExecutionId): (Command<*>) -> ReplyTo? = { ReplyTo.of(execution.context, execution) }

    /** The execution of the receipt handler [handler] for a receipt of [execution]'s command: as its context names it. */
    private fun executionOf(handler: String, execution: ExecutionId): ExecutionId =
        ExecutionId(execution.context, handler, execution.ref, execution.version)

    /**
     * Hands [rejected], the rejection of a command of the class [command], to the `@OnRejected` handler of the saga
     * whose handler derived it, executes what that derives and returns their ids; null when the saga has no handler
     * for it, or [rejected] names no saga handler of this context.
     */
    override suspend fun rejected(rejected: CommandReceipt.Rejected<*>, command: Class<*>): List<String>? {
        val subscription = rejected.executionId.subscription
        val saga = sagas[subscription]
        if (saga == null) {
            // The rejection of a command a receipt handler derived: a compensation is not compensated.
            if (subscription in receiptHandlers) throw CompensationRejectedException(this.context, rejected)
            return null
        }
        val handler = saga.rejectionHandlerOf(command) ?: return null
        val calls = SagaCalls(caller, served, commandTypes, wait = null, rejected.executionId.ref.tenantId)
        val context = rejectionContextOf(handler, rejected, ids, commandTypes, queries, calls, codec)
        val commands = handle(handler.blocking) { handler.invoke(rejected, context) }
        checkReads(handler.name, commands)
        val executed = derived.execute(
            commands,
            rejected.executionId.ref.tenantId,
            wait = null,
            replyTo(executionOf(handler.name, rejected.executionId)),
        )
        executed.rejected.parking()?.let { throw it }
        val sent = calls.called + executed.executed
        if (sent.isEmpty()) {
            RejectionLog.accepted(
                this.context,
                handler.name,
                rejected.ref,
                rejected.commandType,
                rejected.commandId,
                rejected.error.code,
            )
        }
        return sent
    }

    /**
     * Hands [committed], the success receipt of a command of the class [command], to the `@OnCommitted` handler of
     * the saga whose handler derived it, executes what that derives and returns their ids; null when the saga has no
     * handler for it, or [committed] names no saga handler of this context. When [wait] waits for the handler next,
     * its commands carry the rest of the chain and the hop is signalled.
     */
    override suspend fun committed(
        committed: CommandReceipt.Committed<*>,
        command: Class<*>,
        wait: WaitContext?,
    ): List<String>? {
        val handler = sagas[committed.executionId.subscription]?.committedHandlerOf(command) ?: return null
        val next = DerivedCommands.waitOf(wait, context, handler.name)
        val calls = SagaCalls(caller, served, commandTypes, next, committed.executionId.ref.tenantId)
        val sagaContext = committedContextOf(handler, committed, ids, commandTypes, queries, calls, codec)
        val commands = try {
            handle(handler.blocking) { handler.invoke(committed, sagaContext) }
        } catch (rejected: CallRejectedHandlingException) {
            signalHop(wait, handler.name, committed, calls.called, rejected.error)
            throw rejected
        }
        checkReads(handler.name, commands)
        val executed = derived.execute(
            commands,
            committed.executionId.ref.tenantId,
            next,
            replyTo(executionOf(handler.name, committed.executionId)),
        )
        val sent = calls.called + executed.executed
        signalHop(wait, handler.name, committed, sent, executed.rejected.firstOrNull()?.error)
        executed.rejected.parking()?.let { throw it }
        return sent
    }

    /**
     * Signals the hop of the `@OnCommitted` handler [name] when [wait] waits for it next and is not over: the
     * receipt's command reached it, SUCCEEDED with the commands it [derived], or REJECTED with [error].
     */
    private suspend fun signalHop(
        wait: WaitContext?,
        name: String,
        committed: CommandReceipt.Committed<*>,
        derived: List<String>,
        error: ErrorInfo?,
    ) {
        val hop = wait?.nextHandled ?: return
        val now = clock.millis()
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

    /**
     * Calls [handler]: on a virtual thread when it is [blocking]. A call's rejection it lets through parks the aggregate
     * at once ([CallRejectedHandlingException]): a business outcome, which no retry changes.
     */
    private suspend fun handle(blocking: Boolean, handler: suspend () -> List<Command<*>>): List<Command<*>> =
        try {
            if (blocking) withContext(SubscriptionBindings.BLOCKING) { handler() } else handler()
        } catch (rejected: CommandRejectedException) {
            throw CallRejectedHandlingException(rejected)
        }
}

/**
 * Another context rejected a command that the receipt handler of a saga of [context] derived, a compensation or the
 * next step after a commit: it is handed to no handler, since a compensation is not compensated, and the receipts
 * subscription parks the saga's aggregate at once, without a retry, its reason naming the handler, the command and
 * the rejection, as such a rejection within the context does ([DerivedCommandRejectedException]).
 */
internal class CompensationRejectedException(val context: String, val rejected: CommandReceipt.Rejected<*>) :
    HandlingRejectedException(
        rejected.error,
        "The command ${rejected.commandId} on ${rejected.ref} that ${rejected.executionId.subscription} in $context " +
            "derived was rejected, and a compensation is not compensated: ${rejected.commandType} " +
            "${rejected.error.code} ${rejected.error.message}",
    )
