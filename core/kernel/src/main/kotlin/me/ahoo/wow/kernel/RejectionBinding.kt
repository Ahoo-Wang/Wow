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

package me.ahoo.wow.kernel

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.id.Ids
import me.ahoo.wow.api.read.SubscriptionQueries
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.subscribe.CommandReceipt
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.spi.id.IdGenerators

/**
 * One `@OnRejected` handler of a saga, bound to its processor instance: what the compiler
 * generates for each `@OnRejected` function of a `@Saga`. It belongs to the saga's [ProcessorBinding]s
 * ([ProcessorBinding.rejections]), since it takes the rejections of the commands the saga's own handlers derive,
 * not entries of the log; like them, it knows no runtime.
 *
 * - [commandType] is the `C` of the `CommandReceipt.Rejected<C>` it takes: it handles the rejections of the commands of that
 *   class (or a subclass); null for `CommandReceipt.Rejected<*>`, every command the saga derives ([handles]).
 * - [blocking] (`@Blocking`) asks the runtime to run the call on virtual threads instead of inline.
 * - [sends] are the commands it declares it derives, schedules and calls (`@Sends`, its return type for what it
 *   derives): its context holds it to them.
 * - [invoke] calls the handler directly and returns the commands that compensate, of the requests it returns
 *   ([requestsOf]), as a saga's handler derives its commands.
 *
 * Built with [of].
 */
class RejectionBinding<C : Any> private constructor(
    /** The saga's name: its `@Name`, or its class's simple name. */
    val processor: String,
    /** The handler function's name. */
    val function: String,
    val commandType: Class<C>?,
    val blocking: Boolean,
    /** What the handler declares it sends (`@Sends`, else its return type for what it derives). */
    @property:InternalWowApi
    val sends: SagaSends,
    private val call: suspend (CommandReceipt.Rejected<C>, SagaContext) -> List<CommandRequest<*>>,
) {
    init {
        require(processor.isNotBlank()) { "A handler names its processor." }
        require(function.isNotBlank()) { "A handler names its function." }
    }

    /** The handler's name, `processor.function`, as the Manifest lists it. */
    val name: String = "$processor.$function"

    /** Whether it takes the rejections of the commands of [command]: those its [commandType] names, or every one. */
    fun handles(command: Class<*>): Boolean = commandType?.isAssignableFrom(command) ?: true

    /**
     * Calls the handler with [rejected], of a command it [handles], and the [context] the rejection is handled in
     * ([rejectionContextOf]), in the caller's coroutine: running a [blocking] one elsewhere is the runtime's.
     */
    @Suppress("UNCHECKED_CAST") // The runtime hands a handler only the rejections it handles.
    suspend fun invoke(rejected: CommandReceipt.Rejected<*>, context: SagaContext): List<Command<*>> =
        derivationOf(context).commandsOf(call(rejected as CommandReceipt.Rejected<C>, context))

    override fun toString(): String = "RejectionBinding($name, ${commandType?.name ?: "*"})"

    companion object {
        /** A saga's `@OnRejected` handler [function] of [processor], of the rejections of [commandType] (null: all). */
        @JvmStatic
        fun <C : Any> of(
            processor: String,
            function: String,
            commandType: Class<C>?,
            blocking: Boolean = false,
            derives: List<Class<*>> = emptyList(),
            schedules: List<Class<*>> = emptyList(),
            calls: List<Class<*>> = emptyList(),
            handle: suspend (CommandReceipt.Rejected<C>, SagaContext) -> List<CommandRequest<*>>,
        ): RejectionBinding<C> =
            RejectionBinding(processor, function, commandType, blocking, SagaSends(derives, schedules, calls), handle)

        /**
         * The handler among [handlers] of the rejection of a command of [command]: the one whose [commandType] is
         * [command] itself, else the nearest superclass or interface one names, else a `CommandReceipt.Rejected<*>` one; null
         * when none [handles] it.
         */
        @JvmStatic
        fun handlerOf(handlers: List<RejectionBinding<*>>, command: Class<*>): RejectionBinding<*>? =
            nearestHandlerOf(handlers, command) { it.commandType }
    }
}

/**
 * The context an `@OnRejected` handler gets for [rejected]: its execution is (handler,
 * aggregate, version) of the commit whose saga execution derived the command, and the commands it derives are
 * [ExecutionReceiptContext]'s, deterministic per rejected command; it takes its ids from [ids].
 */
fun rejectionContextOf(
    handler: RejectionBinding<*>,
    rejected: CommandReceipt.Rejected<*>,
    ids: Ids = IdGenerators.DEFAULT,
): SagaContext = rejectionContextOf(handler, rejected, ids, CommandTypes.NONE)

/**
 * The context an `@OnRejected` handler gets for [rejected], as [rejectionContextOf] gives it, whose commands without a
 * ref go to the aggregate type [commandTypes] assigns their class, and whose calls go through [calls].
 */
@InternalWowApi
fun rejectionContextOf(
    handler: RejectionBinding<*>,
    rejected: CommandReceipt.Rejected<*>,
    ids: Ids,
    commandTypes: CommandTypes,
    queries: ContextQueries = ContextQueries.NONE,
    calls: CommandCalls = CommandCalls.NONE,
): SagaContext = ExecutionReceiptContext(
    ExecutionId(handler.name, rejected.executionId.ref, rejected.executionId.version),
    rejected.commandId,
    rejected.causation,
    rejected.operator,
    rejected.scope,
    ids,
    rejected.headers,
    commandTypes,
    queries,
    calls = calls,
    sends = handler.sends,
)

/**
 * The handler among [handlers] of a receipt of a command of [command], each taking the commands of the class its
 * [typeOf] gives (null: every command): the one of [command] itself, else the nearest superclass or interface one
 * names, else one of every command; null when none takes it.
 */
internal fun <B : Any> nearestHandlerOf(handlers: List<B>, command: Class<*>, typeOf: (B) -> Class<*>?): B? {
    val typed = handlers.filter { typeOf(it)?.isAssignableFrom(command) == true }
    return typed.firstOrNull { typeOf(it) == command }
        ?: typed.firstOrNull { candidate ->
            typed.none { it !== candidate && typeOf(candidate)!!.isAssignableFrom(typeOf(it)!!) }
        }
        ?: handlers.firstOrNull { typeOf(it) == null }
}

/**
 * The context of a saga's handler of a receipt: of the rejection of the command
 * [receiptOf], by an `@OnRejected` handler, or of its commit, by an `@OnCommitted` one. Handling the same receipt
 * again, after a retry or a second delivery, derives the same requests, which the kernel does not decide twice: each
 * command's id and request id are [compensationId] of [receiptOf], this execution, and what it asks (its target,
 * its command type and its business key or occurrence), as a saga's derived commands are keyed
 * ([ExecutionSagaContext.derivedId]). Each command's [causation][Command.causation] is the command [receiptOf], in the
 * chain of its root ([causation]), and its operator and scope are that command's, and so are its [headers], the
 * request facts it inherited ([me.ahoo.wow.api.decide.RequestFacts.PROPAGATED]). It schedules, cancels and calls as
 * every saga context does ([ExecutionSagaContext]): its schedules written by [codec], its calls through [calls].
 */
@InternalWowApi
class ExecutionReceiptContext(
    override val executionId: ExecutionId,
    /** The command whose receipt the handler takes. */
    private val receiptOf: String,
    causation: Causation,
    operator: Operator?,
    scope: Scope,
    override val ids: Ids = IdGenerators.DEFAULT,
    headers: Map<String, String> = emptyMap(),
    /** Assigns the commands whose ref the handler leaves out their aggregate type. */
    commandTypes: CommandTypes = CommandTypes.NONE,
    /** The handler's queries, of the commit's tenant. */
    private val contextQueries: ContextQueries = ContextQueries.NONE,
    codec: EventCodec = EventCodec(),
    calls: CommandCalls = CommandCalls.NONE,
    /** What the handler declares it sends; null for a context built outside its binding. */
    sends: SagaSends? = null,
) : SagaContext, Derivation {
    /**
     * The commands go to the tenant of the commit whose saga execution derived the receipt's command unless they name
     * another; their scope is that command's unless they name their target scope.
     */
    private val derivation = SagaDerivation(
        executionId,
        commandTypes,
        codec,
        calls,
        scope,
        headers,
        Causation(causation.correlationId, receiptOf),
        operator,
        sends,
    ) { ref, type, key -> compensationIdOf(executionId, receiptOf, ref, type, key) }

    override val queries: SubscriptionQueries get() = contextQueries.of(executionId.ref.tenantId)

    /** The commands of [requests], in order, then those [schedule] and [cancel] recorded, in the order of the calls. */
    override fun commandsOf(requests: List<CommandRequest<*>>): List<Command<*>> = derivation.commandsOf(requests)

    override fun schedule(key: String, rule: ScheduleRule, request: CommandRequest<*>) =
        derivation.schedule(key, rule, request)

    override fun cancel(key: String, target: AggregateRef) = derivation.cancel(key, target)

    override suspend fun call(request: CommandRequest<*>): CommandResult = derivation.call(request)

    @InternalWowApi
    companion object {
        /**
         * The id, and request id, of the [occurrence]-th command (from 0) to [ref] of [commandType] derived by
         * [execution] for the receipt of [receiptCommandId]: a name-based UUID of the execution, the receipt's command,
         * the target, the command type and the occurrence, stable for that receipt.
         */
        @JvmStatic
        @JvmOverloads
        fun compensationId(
            execution: ExecutionId,
            receiptCommandId: String,
            ref: AggregateRef,
            commandType: Class<*>,
            occurrence: Int = 0,
        ): String {
            require(occurrence >= 0) { "An occurrence is never negative." }
            return compensationIdOf(execution, receiptCommandId, ref, commandType, occurrence.toString())
        }

        /**
         * The id, and request id, of the command to [ref] of [commandType] derived by [execution] for the receipt of
         * [receiptCommandId] from a request whose business key is [requestId] ([CommandRequest.requestId]).
         */
        @JvmStatic
        fun compensationId(
            execution: ExecutionId,
            receiptCommandId: String,
            ref: AggregateRef,
            commandType: Class<*>,
            requestId: String,
        ): String = compensationIdOf(execution, receiptCommandId, ref, commandType, DerivedRequests.keyed(requestId))

        private fun compensationIdOf(
            execution: ExecutionId,
            receiptCommandId: String,
            ref: AggregateRef,
            commandType: Class<*>,
            key: String,
        ): String = DerivedRequests.idOf("$execution!$receiptCommandId#${DerivedRequests.keyOf(ref, commandType)}#$key")
    }
}
