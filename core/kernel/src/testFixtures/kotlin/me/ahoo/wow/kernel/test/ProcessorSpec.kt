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

package me.ahoo.wow.kernel.test

import kotlinx.coroutines.runBlocking
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.ExecutionSagaContext
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.RejectionBinding
import me.ahoo.wow.kernel.SagaSchedule
import me.ahoo.wow.kernel.contextOf
import me.ahoo.wow.kernel.rejectionContextOf
import kotlin.reflect.KClass

/**
 * Specifies a saga's behaviour: given a commit of a stream, its own context's or one it reads from another context
 * (a [StateEvent], built with [stateEvent]), or the rejection of a command it derived, the commands it derives and
 * the commands it schedules. [handlers] are the handlers of the saga under test, from the function the compiler
 * generated for it (`<processor>Handlers`, `orderSagaHandlers` for `OrderSaga`), which constructs that saga only,
 * with its own services:
 *
 * ```kotlin
 * sagaSpec(orderSagaHandlers(Dependencies.NONE)) {
 *     whenEvent(stateEvent(order, OrderState("order-1"), orderCreated)) {
 *         expectCommands(ReserveStock("order-1", 2))
 *         expectDeterministic()
 *         whenRejected(commands.single(), code = "inventory.stock.insufficient") {
 *             expectCommand(CancelOrder("..."), to = order)
 *         }
 *     }
 * }
 * ```
 *
 * Each call of a handler gets a fresh [ExecutionSagaContext], as the runtime gives each handling of a commit, so the
 * ids the saga derives are the same on every call for the same commit. Its [ids][me.ahoo.wow.api.subscribe.SagaContext.ids]
 * (decision D171) are [SequentialIds], counted anew for each call: `id-1`… and `<aggregate>-1`….
 */
fun sagaSpec(handlers: List<ProcessorBinding<*>>, block: ProcessorSpec.() -> Unit) {
    ProcessorSpec(handlers, FunctionKind.SAGA).block()
}

/**
 * Specifies a projection's behaviour: given a commit (a [StateEvent], built with [stateEvent]), what its handlers
 * write. [handlers] are the projection's, from the function the compiler generated for it
 * (`<processor>Handlers`), with a fake of the port it writes to, which the expectations then read:
 *
 * ```kotlin
 * val views = InMemoryOrderViews()
 * projectionSpec(orderViewProjectionHandlers(Dependencies.of(views))) {
 *     whenEvent(stateEvent(order, state, orderCreated)) {
 *         expectHandledBy("onOrder")
 *         views["order-1"]!!.status.assert().isEqualTo("CREATED")
 *     }
 * }
 * ```
 */
fun projectionSpec(handlers: List<ProcessorBinding<*>>, block: ProcessorSpec.() -> Unit) {
    ProcessorSpec(handlers, FunctionKind.PROJECT).block()
}

/** Specifies a reactor's behaviour as [projectionSpec] does a projection's: given a commit, its side effects. */
fun reactorSpec(handlers: List<ProcessorBinding<*>>, block: ProcessorSpec.() -> Unit) {
    ProcessorSpec(handlers, FunctionKind.REACT).block()
}

/** Calls the [handlers] of one processor of [kind] as the runtime would, one commit or rejection at a time. */
class ProcessorSpec(val handlers: List<ProcessorBinding<*>>, private val kind: FunctionKind) {
    /** The executions that derived each command, by command id: a rejection of one goes back to its saga. */
    private val derivedBy = HashMap<String, ExecutionId>()

    init {
        require(handlers.isNotEmpty()) { "A processor specification needs the processor's handlers." }
        require(handlers.map { it.processor }.distinct().size == 1) {
            "The handlers are of several processors (${handlers.map { it.processor }.distinct()}): " +
                "give the specification one processor's, from its generated <processor>Handlers function."
        }
        handlers.forEach { require(it.kind == kind) { "$it is not a $kind handler." } }
    }

    /** The processor's name. */
    val processor: String = handlers.first().processor

    /**
     * Hands [event] to each handler that reads it, as its subscription would: those that name its aggregate type in
     * `aggregates`, or that name none and take its state type (or `StateEvent<*>`). [handler] names the only one to
     * call instead, by its function name (`onOrderCreated`) or its subscription name (`OrderSaga.onOrderCreated`).
     * [expect] checks what they did. An exception a handler throws fails the specification, unless [expect] checks
     * it with [ProcessorExpectations.expectError].
     */
    fun whenEvent(event: StateEvent<*>, handler: String? = null, expect: ProcessorExpectations.() -> Unit) {
        val called = if (handler == null) {
            handlers.filter { reads(it, event) }.ifEmpty {
                throw AssertionError(
                    "No handler of $processor reads ${event.ref.type} with the state ${event.state?.javaClass?.name}: " +
                        "the handlers are ${handlers.map { it.function }}.",
                )
            }
        } else {
            listOf(find(handler))
        }
        val call = { handle(called, event) }
        verify(ProcessorExpectations(called.map { it.name }, event.commitTime, call(), call), expect)
    }

    /**
     * Hands the rejection of [command] with [code] and [message] to the saga's `@OnRejected` handler of it, as the
     * runtime does for a command a handler derived and its target refused (decisions D136, D153). [command] is one
     * this specification derived ([ProcessorExpectations.commands]), so the rejection goes back to the execution that
     * derived it; [commandType] is its full type name, `<context>.<aggregate>.<simple name>` by default. [expect]
     * checks the compensating commands; it fails when the saga has no handler of that rejection.
     */
    fun whenRejected(
        command: Command<*>,
        code: String,
        message: String = code,
        commandType: String = "${command.ref.type}.${command.body.javaClass.simpleName}",
        expect: ProcessorExpectations.() -> Unit,
    ) {
        val execution = derivedBy[command.id] ?: throw IllegalArgumentException(
            "${command.id} is not a command this specification derived: hand its CommandRejected over instead.",
        )
        val rejected = CommandRejected<Any>(
            commandId = command.id,
            commandType = commandType,
            ref = command.ref,
            error = ErrorInfo(code, message),
            causation = command.causation ?: Causation(command.id, command.id),
            executionId = execution,
            operator = command.operator,
        )
        whenRejected(rejected, command.body.javaClass.kotlin, expect)
    }

    /**
     * Hands [rejected], the rejection of a command of [command] that the saga's handler named by its
     * [CommandRejected.executionId] derived, to the saga's `@OnRejected` handler of it, as the runtime does. [expect]
     * checks the compensating commands; it fails when the saga has no handler of that rejection.
     */
    fun whenRejected(rejected: CommandRejected<*>, command: KClass<*>, expect: ProcessorExpectations.() -> Unit) {
        require(kind == FunctionKind.SAGA) { "Only a saga takes the rejections of the commands it derived." }
        val subscription = rejected.executionId.subscription
        val saga = handlers.firstOrNull { it.name == subscription } ?: throw IllegalArgumentException(
            "$subscription, which derived ${rejected.commandId}, is not a handler of $processor.",
        )
        val onRejected = saga.rejectionHandlerOf(command.java) ?: throw AssertionError(
            "$processor has no @OnRejected handler of the rejections of ${command.qualifiedName}.",
        )
        val call = { reject(onRejected, rejected) }
        verify(ProcessorExpectations(listOf(onRejected.name), null, call(), call), expect)
    }

    private fun verify(expectations: ProcessorExpectations, expect: ProcessorExpectations.() -> Unit) {
        expectations.expect()
        expectations.verify()
    }

    private fun find(handler: String): ProcessorBinding<*> =
        handlers.firstOrNull { it.function == handler || it.name == handler } ?: throw IllegalArgumentException(
            "$processor has no handler $handler: its handlers are ${handlers.map { it.function }}.",
        )

    private fun handle(called: List<ProcessorBinding<*>>, event: StateEvent<*>): Result<Handled> = runCatchingAll {
        val commands = ArrayList<Command<*>>()
        val schedules = ArrayList<SagaSchedule>()
        called.forEach { handler ->
            val context = contextOf(handler.kind, handler.name, event, SequentialIds())
            val derived = runBlocking { handler.invoke(event, context) }
            derived.forEach { derivedBy[it.id] = context.executionId }
            commands += derived
            (context as? ExecutionSagaContext)?.let { schedules += it.schedules }
        }
        Handled(commands, schedules)
    }

    private fun reject(handler: RejectionBinding<*>, rejected: CommandRejected<*>): Result<Handled> =
        runCatchingAll {
            val context = rejectionContextOf(handler, rejected, SequentialIds())
            val derived = runBlocking { handler.invoke(rejected, context) }
            derived.forEach { derivedBy[it.id] = context.executionId }
            Handled(derived, emptyList())
        }

    /** The commands and the schedules of one handling. */
    internal data class Handled(val commands: List<Command<*>>, val schedules: List<SagaSchedule>)

    private companion object {
        /** Whether [handler]'s subscription hands it [event]. */
        fun reads(handler: ProcessorBinding<*>, event: StateEvent<*>): Boolean {
            if (handler.aggregates.isNotEmpty()) {
                return event.ref.type.toString() in handler.aggregates
            }
            val type = handler.stateType ?: return true
            val state = event.state ?: return handler.nullableState
            return type.isInstance(state)
        }

        inline fun <T> runCatchingAll(block: () -> T): Result<T> = try {
            Result.success(block())
        } catch (
            @Suppress("TooGenericExceptionCaught") // Whatever the handler throws, expectError checks it.
            failure: Exception,
        ) {
            Result.failure(failure)
        }
    }
}
