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

package me.ahoo.wow.test

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.subscribe.CommandReceipt
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.api.subscribe.SubscriptionContext
import me.ahoo.wow.kernel.BlockingDispatcher
import me.ahoo.wow.kernel.CallRejectedHandlingException
import me.ahoo.wow.kernel.CommandCalls
import me.ahoo.wow.kernel.CommittedBinding
import me.ahoo.wow.kernel.ContextQueries
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.kernel.HandlerTimeoutException
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.RejectionBinding
import me.ahoo.wow.kernel.committedContextOf
import me.ahoo.wow.kernel.contextOf
import me.ahoo.wow.kernel.rejectionContextOf
import me.ahoo.wow.test.ProcessorSpec.Handled

/**
 * Calls the handlers of one processor of [kind] for a [ProcessorSpec] as the runtime would, one commit or receipt
 * at a time: each with a fresh context, its calls answered and its reads served as the specification says, and
 * each command it derives kept with the execution that derived it, so that a rejection or receipt of one goes back
 * to it. A fork's handling starts on a copy of [origin]'s.
 */
internal class ProcessorHandling(
    private val kind: FunctionKind,
    private val commandTypes: ClassPathCommandTypes,
    origin: ProcessorHandling?,
) {
    /** The executions that derived each command, by command id: a rejection of one goes back to its saga. */
    private val derivedBy: MutableMap<String, ExecutionId> = HashMap(origin?.derivedBy.orEmpty())

    /** How the targets answer the saga's synchronous calls. */
    private var answer: (Command<*>) -> CommandResult = origin?.answer ?: { committedResult(it) }

    /** What the handlers read through their context's queries; none until [ProcessorSpec.givenQueries]. */
    private var queries: ContextQueries = origin?.queries ?: ContextQueries.NONE

    /** Answers the saga's synchronous calls with [answer] from now on ([ProcessorSpec.answerCalls]). */
    fun answerWith(answer: (Command<*>) -> CommandResult) {
        this.answer = answer
    }

    /** Serves the handlers' reads from [queries] from now on ([ProcessorSpec.givenQueries]). */
    fun serve(queries: ContextQueries) {
        this.queries = queries
    }

    /** The execution that derived the command [commandId]; null when no handling of this specification did. */
    fun executionOf(commandId: String): ExecutionId? = derivedBy[commandId]

    /** The retry budget of the handler whose handling threw last; null when none threw or it gives none. */
    val failingRetry: HandlerRetry? get() = failing?.retry

    /** The handler whose handling threw last, whose retry budget [ProcessorExpectations.expectParked] reads. */
    private var failing: ProcessorBinding<*>? = null

    fun handle(called: List<ProcessorBinding<*>>, event: StateEvent<*>): Result<Handled> = runCatchingAll {
        val commands = ArrayList<Command<*>>()
        val calls = ArrayList<Command<*>>()
        failing = null
        called.forEach { handler ->
            failing = handler
            val rejectedCalls = LinkedHashMap<String, Command<*>>()
            val answering = CommandCalls { command ->
                calls += command
                answer(command).also { result ->
                    if (result.status == CommandResult.Status.REJECTED) {
                        rejectedCalls[result.commandId] = command
                    }
                }
            }
            // As the runtime does: the handler sends only what it declares (@Sends).
            val context = contextOf(
                handler.kind,
                handler.name,
                event,
                SequentialIds(),
                commandTypes,
                answering,
                queries,
                handler.sends,
            )
            val derived = try {
                runBlocking { attempt(handler, event, context) }
            } catch (rejected: CommandRejectedException) {
                if (kind != FunctionKind.SAGA) throw rejected
                callRejected(handler, rejected, rejectedCalls[rejected.result.commandId], context.executionId)
            }
            derived.forEach { derivedBy[it.id] = context.executionId }
            commands += derived
        }
        failing = null
        Handled(commands, calls)
    }

    /**
     * One attempt of [handler] with [event], as the runtime's subscription makes it: on a virtual thread when it is
     * `@Blocking`, and cancelled once it runs longer than its `@Retry(timeout)`, a failed attempt
     * ([HandlerTimeoutException]) the runtime retries, then parks. The time is real: a timeout on virtual time would
     * fire while a blocking handler's thread still runs.
     */
    private suspend fun attempt(
        handler: ProcessorBinding<*>,
        event: StateEvent<*>,
        context: SubscriptionContext,
    ): List<Command<*>> {
        val call: suspend () -> List<Command<*>> = if (handler.blocking) {
            { withContext(BlockingDispatcher.dispatcher) { handler.invoke(event, context) } }
        } else {
            { handler.invoke(event, context) }
        }
        val timeout = handler.retry?.timeout ?: return call()
        return withTimeoutOrNull(timeout) { call() }
            ?: throw HandlerTimeoutException(handler.name, event.ref, event.version, timeout)
    }

    /**
     * As the runtime does: the rejection of the call [command] a saga's [handler] let through goes to the saga's
     * `@OnRejected` handler of the command, whose commands the aborted handling is left with; without one, or for a
     * rejection of no call of the handling, the aggregate parks at once ([CallRejectedHandlingException]).
     */
    private fun callRejected(
        handler: ProcessorBinding<*>,
        rejected: CommandRejectedException,
        command: Command<*>?,
        execution: ExecutionId,
    ): List<Command<*>> {
        val type = command?.body?.javaClass ?: throw CallRejectedHandlingException(rejected)
        val onRejected = handler.rejectionHandlerOf(type) ?: throw CallRejectedHandlingException(rejected)
        val receipt = CommandReceipt.Rejected<Any>(
            commandId = command.id,
            commandType = commandTypes.nameOf(command.ref.type, type) ?: type.name,
            ref = command.ref,
            error = checkNotNull(rejected.result.error) { "A rejected result carries its error." },
            causation = checkNotNull(command.causation) { "A called command carries its causation." },
            executionId = execution,
            operator = command.operator,
            scope = command.scope,
            headers = RequestFacts.propagatedOf(command.headers),
            version = rejected.result.version,
        )
        return reject(onRejected, receipt).getOrThrow().commands
    }

    fun reject(handler: RejectionBinding<*>, rejected: CommandReceipt.Rejected<*>): Result<Handled> =
        runCatchingAll {
            val calls = ArrayList<Command<*>>()
            val context =
                rejectionContextOf(handler, rejected, SequentialIds(), commandTypes, queries, answering(calls))
            val derived = receiptHandling { handler.invoke(rejected, context) }
            derived.forEach { derivedBy[it.id] = context.executionId }
            Handled(derived, calls)
        }

    fun commit(handler: CommittedBinding<*>, committed: CommandReceipt.Committed<*>): Result<Handled> =
        runCatchingAll {
            val calls = ArrayList<Command<*>>()
            val context =
                committedContextOf(handler, committed, SequentialIds(), commandTypes, queries, answering(calls))
            val derived = receiptHandling { handler.invoke(committed, context) }
            derived.forEach { derivedBy[it.id] = context.executionId }
            Handled(derived, calls)
        }

    /**
     * A receipt handler's commands, as the runtime takes them: a call's rejection it lets through goes to no handler (a
     * compensation does not compensate itself) but parks the aggregate at once ([CallRejectedHandlingException]).
     */
    private fun receiptHandling(handle: suspend () -> List<Command<*>>): List<Command<*>> = try {
        runBlocking { handle() }
    } catch (rejected: CommandRejectedException) {
        throw CallRejectedHandlingException(rejected)
    }

    /** The calls of a receipt's handler: answered by [answer], each recorded into [calls]. */
    private fun answering(calls: MutableList<Command<*>>): CommandCalls = CommandCalls { command ->
        calls += command
        answer(command)
    }
}

internal inline fun <T> runCatchingAll(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (
    @Suppress("TooGenericExceptionCaught") // Whatever the handler throws, expectError checks it.
    failure: Exception,
) {
    Result.failure(failure)
}
