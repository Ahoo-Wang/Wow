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
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.id.Ids
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.spi.id.IdGenerators
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/**
 * One `@OnRejected` handler of a saga, bound to its processor instance (decisions D136, D149, D153): what the compiler
 * generates for each `@OnRejected` function of a `@Saga`. It belongs to the saga's [ProcessorBinding]s
 * ([ProcessorBinding.rejections]), since it takes the rejections of the commands the saga's own handlers derive,
 * not entries of the log; like them, it knows no runtime.
 *
 * - [commandType] is the `C` of the `CommandRejected<C>` it takes: it handles the rejections of the commands of that
 *   class (or a subclass); null for `CommandRejected<*>`, every command the saga derives ([handles]).
 * - [blocking] (`@Blocking`) asks the runtime to run the call on virtual threads instead of inline.
 * - [invoke] calls the handler directly and returns the commands that compensate, as a saga's handler returns those it
 *   derives ([derivedOf]).
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
    private val call: suspend (CommandRejected<C>, SagaContext) -> List<Command<*>>,
) {
    init {
        require(processor.isNotBlank()) { "A handler names its processor." }
        require(function.isNotBlank()) { "A handler names its function." }
    }

    /** The handler's name, `processor.function`, as the Manifest lists it (design 04 §1). */
    val name: String = "$processor.$function"

    /** Whether it takes the rejections of the commands of [command]: those its [commandType] names, or every one. */
    fun handles(command: Class<*>): Boolean = commandType?.isAssignableFrom(command) ?: true

    /**
     * Calls the handler with [rejected], of a command it [handles], and the [context] the rejection is handled in
     * ([rejectionContextOf]), in the caller's coroutine: running a [blocking] one elsewhere is the runtime's.
     */
    @Suppress("UNCHECKED_CAST") // The runtime hands a handler only the rejections it handles.
    suspend fun invoke(rejected: CommandRejected<*>, context: SagaContext): List<Command<*>> =
        call(rejected as CommandRejected<C>, context)

    override fun toString(): String = "RejectionBinding($name, ${commandType?.name ?: "*"})"

    companion object {
        /** A saga's `@OnRejected` handler [function] of [processor], of the rejections of [commandType] (null: all). */
        @JvmStatic
        fun <C : Any> of(
            processor: String,
            function: String,
            commandType: Class<C>?,
            blocking: Boolean = false,
            handle: suspend (CommandRejected<C>, SagaContext) -> List<Command<*>>,
        ): RejectionBinding<C> = RejectionBinding(processor, function, commandType, blocking, handle)

        /**
         * The handler among [handlers] of the rejection of a command of [command]: the one whose [commandType] is
         * [command] itself, else the nearest superclass or interface one names, else a `CommandRejected<*>` one; null
         * when none [handles] it.
         */
        @JvmStatic
        fun handlerOf(handlers: List<RejectionBinding<*>>, command: Class<*>): RejectionBinding<*>? {
            val typed = handlers.filter { it.commandType != null && it.handles(command) }
            return typed.firstOrNull { it.commandType == command }
                ?: typed.firstOrNull { candidate ->
                    typed.none { it !== candidate && candidate.commandType!!.isAssignableFrom(it.commandType!!) }
                }
                ?: handlers.firstOrNull { it.commandType == null }
        }
    }
}

/**
 * The context an `@OnRejected` handler gets for [rejected] (decisions D136, D153): its execution is (handler,
 * aggregate, version) of the commit whose saga execution derived the command, and the commands it derives are
 * [RejectionSagaContext]'s, deterministic per rejected command; it takes its ids from [ids].
 */
fun rejectionContextOf(
    handler: RejectionBinding<*>,
    rejected: CommandRejected<*>,
    ids: Ids = IdGenerators.DEFAULT,
): SagaContext = RejectionSagaContext(
    ExecutionId(handler.name, rejected.executionId.ref, rejected.executionId.version),
    rejected,
    ids,
)

/**
 * The context of an `@OnRejected` handler for the rejection [rejected] (decisions D136, D153). Handling the same
 * rejection again, after a retry or a second delivery of its receipt, derives the same requests, which the kernel does
 * not decide twice: the n-th [command]'s id and request id are [compensationId] of the rejected command, this
 * execution and the call's index. Each command's causation is the rejected command, in the chain of its root, and its
 * operator the rejected command's (decision D163).
 */
class RejectionSagaContext(
    override val executionId: ExecutionId,
    private val rejected: CommandRejected<*>,
    override val ids: Ids = IdGenerators.DEFAULT,
) : SagaContext {
    private val calls = AtomicInteger()

    override fun <C : Any> command(ref: AggregateRef, body: C): Command<C> {
        val id = compensationId(executionId, rejected.commandId, calls.getAndIncrement())
        return Command(
            id = id,
            requestId = id,
            ref = ref,
            body = body,
            causation = Causation(rejected.causation.correlationId, rejected.commandId),
            operator = rejected.operator,
        )
    }

    /** An `@OnRejected` handler compensates now; it does not schedule (decision D166). */
    override fun schedule(key: String, at: Instant, target: AggregateRef, command: Any) = unsupported()

    /** An `@OnRejected` handler compensates now; it does not schedule (decision D166). */
    override fun schedule(key: String, after: Duration, target: AggregateRef, command: Any) = unsupported()

    /** An `@OnRejected` handler compensates now; it does not schedule (decision D166). */
    override fun cancel(key: String, target: AggregateRef) = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("An @OnRejected handler derives commands; it does not schedule them.")

    companion object {
        /**
         * The id, and request id, of the [index]-th command (from 0) derived by [execution] for the rejection of
         * [rejectedCommandId]: a name-based UUID of `execution!rejectedCommandId#index`, stable for that rejection.
         */
        @JvmStatic
        fun compensationId(execution: ExecutionId, rejectedCommandId: String, index: Int): String {
            require(index >= 0) { "A call index is never negative." }
            return UUID.nameUUIDFromBytes("$execution!$rejectedCommandId#$index".toByteArray(Charsets.UTF_8))
                .toString()
        }
    }
}
