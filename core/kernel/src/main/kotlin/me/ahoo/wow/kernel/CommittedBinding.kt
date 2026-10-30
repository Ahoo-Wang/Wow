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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.id.Ids
import me.ahoo.wow.api.subscribe.CommandReceipt
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.spi.id.IdGenerators

/**
 * One `@OnCommitted` handler of a saga, bound to its processor instance, the counterpart of
 * [RejectionBinding]: what the compiler generates for each `@OnCommitted` function of a `@Saga`. It belongs to the
 * saga's [ProcessorBinding]s ([ProcessorBinding.commits]), since it takes the success receipts of the commands the
 * saga's own handlers derive; like them, it knows no runtime.
 *
 * - [commandType] is the `C` of the `CommandReceipt.Committed<C>` it takes: it handles the receipts of the commands of that
 *   class (or a subclass); null for `CommandReceipt.Committed<*>`, every command the saga derives ([handles]).
 * - [blocking] (`@Blocking`) asks the runtime to run the call on virtual threads instead of inline.
 * - [invoke] calls the handler directly and returns the commands of the requests it returns
 *   ([requestsOf]), as a saga's handler does.
 *
 * Built with [of].
 */
class CommittedBinding<C : Any> private constructor(
    /** The saga's name: its `@Name`, or its class's simple name. */
    val processor: String,
    /** The handler function's name. */
    val function: String,
    val commandType: Class<C>?,
    val blocking: Boolean,
    private val call: suspend (CommandReceipt.Committed<C>, SagaContext) -> List<CommandRequest<*>>,
) {
    init {
        require(processor.isNotBlank()) { "A handler names its processor." }
        require(function.isNotBlank()) { "A handler names its function." }
    }

    /** The handler's name, `processor.function`, as the Manifest lists it. */
    val name: String = "$processor.$function"

    /** Whether it takes the receipts of the commands of [command]: those its [commandType] names, or every one. */
    fun handles(command: Class<*>): Boolean = commandType?.isAssignableFrom(command) ?: true

    /**
     * Calls the handler with [committed], of a command it [handles], and the [context] the receipt is handled in
     * ([committedContextOf]), in the caller's coroutine: running a [blocking] one elsewhere is the runtime's.
     */
    @Suppress("UNCHECKED_CAST") // The runtime hands a handler only the receipts it handles.
    suspend fun invoke(committed: CommandReceipt.Committed<*>, context: SagaContext): List<Command<*>> =
        derivationOf(context).commandsOf(call(committed as CommandReceipt.Committed<C>, context))

    override fun toString(): String = "CommittedBinding($name, ${commandType?.name ?: "*"})"

    companion object {
        /** A saga's `@OnCommitted` handler [function] of [processor], of the receipts of [commandType] (null: all). */
        @JvmStatic
        fun <C : Any> of(
            processor: String,
            function: String,
            commandType: Class<C>?,
            blocking: Boolean = false,
            handle: suspend (CommandReceipt.Committed<C>, SagaContext) -> List<CommandRequest<*>>,
        ): CommittedBinding<C> = CommittedBinding(processor, function, commandType, blocking, handle)

        /**
         * The handler among [handlers] of the receipt of a command of [command], chosen as
         * [RejectionBinding.handlerOf] chooses one; null when none [handles] it.
         */
        @JvmStatic
        fun handlerOf(handlers: List<CommittedBinding<*>>, command: Class<*>): CommittedBinding<*>? =
            nearestHandlerOf(handlers, command) { it.commandType }
    }
}

/**
 * The context an `@OnCommitted` handler gets for [committed]: its execution is (handler, aggregate,
 * version) of the commit whose saga execution derived the command, and the commands it derives are
 * [ExecutionReceiptContext]'s, deterministic per committed command; it takes its ids from [ids].
 */
fun committedContextOf(
    handler: CommittedBinding<*>,
    committed: CommandReceipt.Committed<*>,
    ids: Ids = IdGenerators.DEFAULT,
): SagaContext = committedContextOf(handler, committed, ids, CommandTypes.NONE)

/**
 * The context an `@OnCommitted` handler gets for [committed], as [committedContextOf] gives it, whose commands without
 * a ref go to the aggregate type [commandTypes] assigns their class, and whose calls go through [calls].
 */
@InternalWowApi
fun committedContextOf(
    handler: CommittedBinding<*>,
    committed: CommandReceipt.Committed<*>,
    ids: Ids,
    commandTypes: CommandTypes,
    queries: ContextQueries = ContextQueries.NONE,
    calls: CommandCalls = CommandCalls.NONE,
): SagaContext = ExecutionReceiptContext(
    ExecutionId(handler.name, committed.executionId.ref, committed.executionId.version),
    committed.commandId,
    committed.causation,
    committed.operator,
    committed.scope,
    ids,
    committed.headers,
    commandTypes,
    queries,
    calls = calls,
)
