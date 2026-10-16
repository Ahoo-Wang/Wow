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
import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.subscribe.CommandCommitted
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.CommandCalls
import me.ahoo.wow.kernel.CommittedBinding
import me.ahoo.wow.kernel.ExecutionSagaContext
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.RejectionBinding
import me.ahoo.wow.kernel.committedContextOf
import me.ahoo.wow.kernel.contextOf
import me.ahoo.wow.kernel.rejectionContextOf
import me.ahoo.wow.kernel.schedule.JobState
import kotlin.reflect.KClass

/**
 * Specifies a saga's behaviour: given a commit of a stream, its own context's or one it reads from another context
 * (a [StateEvent], built with [stateEvent]), or the rejection or the commit of a command it derived, the commands it
 * derives and the commands it schedules. [handlers] are the handlers of the saga under test, from the function the compiler
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
 *         whenCommitted(commands.single(), version = 3) {
 *             expectCommand(ConfirmStock("product-1"), to = order)
 *         }
 *     }
 * }
 * ```
 *
 * Each call of a handler gets a fresh [ExecutionSagaContext], as the runtime gives each handling of a commit, so the
 * ids the saga derives are the same on every call for the same commit. Its [ids][me.ahoo.wow.api.subscribe.SagaContext.ids]
 * (decision D171) are [SequentialIds], counted anew for each call: `id-1`… and `<aggregate>-1`….
 *
 * The synchronous calls a saga makes ([me.ahoo.wow.api.subscribe.SagaContext.call], decision D193) are answered by
 * [answerCalls], committed at version 1 by default ([committedResult]); [ProcessorExpectations.calls] lists them.
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

/**
 * The full type name of [command] as its receipts carry it, `<context>.<aggregate>.<name>`: the name is its class's
 * `@Name` (decision D55), or its simple name.
 */
fun commandTypeOf(command: Command<*>): String {
    val type = command.body.javaClass
    return "${command.ref.type}.${type.getAnnotation(Name::class.java)?.value ?: type.simpleName}"
}

/**
 * The rejection of [command] with [code] and [message], as the runtime hands it to the `@OnRejected` handler of the
 * saga whose execution [executionId] derived the command (decisions D136, D153): for [ProcessorSpec.whenRejected].
 */
fun commandRejected(
    command: Command<*>,
    executionId: ExecutionId,
    code: String,
    message: String = code,
    commandType: String = commandTypeOf(command),
): CommandRejected<Any> = CommandRejected(
    commandId = command.id,
    commandType = commandType,
    ref = command.ref,
    error = ErrorInfo(code, message),
    causation = command.causation ?: Causation(command.id, command.id),
    executionId = executionId,
    operator = command.operator,
)

/**
 * The success receipt of [command], committed on the aggregate [aggregateId] at [version] ([duplicate] when its
 * target had already committed the request), as the runtime hands it to the `@OnCommitted` handler of the saga whose
 * execution [executionId] derived the command (decision D192): for [ProcessorSpec.whenCommitted].
 */
@Suppress("LongParameterList")
fun commandCommitted(
    command: Command<*>,
    executionId: ExecutionId,
    version: Int = 1,
    aggregateId: String = command.ref.id,
    duplicate: Boolean = false,
    commandType: String = commandTypeOf(command),
): CommandCommitted<Any> {
    val ref = command.ref.copy(id = aggregateId)
    val causation = command.causation ?: Causation(command.id, command.id)
    val type = command.ref.type
    val result = CommandResult(
        commandId = command.id,
        requestId = command.requestId,
        correlationId = causation.correlationId,
        ref = ref,
        hop = Hop.committed(type.context),
        function = FunctionInfo(FunctionKind.COMMAND, type.context, type.name, commandType.substringAfterLast('.')),
        status = CommandResult.Status.SUCCEEDED,
        version = version,
        duplicate = duplicate,
        signalTime = 0L,
    )
    return CommandCommitted(
        commandId = command.id,
        commandType = commandType,
        ref = ref,
        version = version,
        result = result,
        causation = causation,
        executionId = executionId,
        operator = command.operator,
        scope = command.scope,
    )
}

/** Calls the [handlers] of one processor of [kind] as the runtime would, one commit or receipt at a time. */
class ProcessorSpec(val handlers: List<ProcessorBinding<*>>, private val kind: FunctionKind) {
    /** The executions that derived each command, by command id: a rejection of one goes back to its saga. */
    private val derivedBy = HashMap<String, ExecutionId>()

    /** How the targets answer the saga's synchronous calls. */
    private var answer: (Command<*>) -> CommandResult = { committedResult(it) }

    /**
     * Answers each synchronous call a saga's handler makes (decision D193) with what [answer] returns for its command,
     * as the target's command gateway would: [committedResult] (the default, at version 1), [rejectedResult], which
     * the handler gets as a [me.ahoo.wow.api.subscribe.CallRejectedException], or [failedResult], a
     * [me.ahoo.wow.api.subscribe.CallFailedException]; [answer] may also throw one, as a target that is down does.
     */
    fun answerCalls(answer: (Command<*>) -> CommandResult) {
        this.answer = answer
    }

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
        verify(ProcessorExpectations(called.map { it.name }, call(), call), expect)
    }

    /**
     * Hands the rejection of [command] with [code] and [message] to the saga's `@OnRejected` handler of it, as the
     * runtime does for a command a handler derived and its target refused (decisions D136, D153). [command] is one
     * this specification derived ([ProcessorExpectations.commands]), so the rejection goes back to the execution that
     * derived it; [commandType] is its full type name ([commandTypeOf] by default). [expect] checks the compensating
     * commands; it fails when the saga has no handler of that rejection.
     */
    fun whenRejected(
        command: Command<*>,
        code: String,
        message: String = code,
        commandType: String = commandTypeOf(command),
        expect: ProcessorExpectations.() -> Unit,
    ) {
        val execution = derivedBy[command.id] ?: throw IllegalArgumentException(
            "${command.id} is not a command this specification derived: hand over commandRejected(command, " +
                "executionId, code) instead, with the execution that derived it.",
        )
        whenRejected(
            commandRejected(command, execution, code, message, commandType),
            command.body.javaClass.kotlin,
            expect
        )
    }

    /**
     * Hands [rejected], the rejection of a command of [command] that the saga's handler named by its
     * [CommandRejected.executionId] derived, to the saga's `@OnRejected` handler of it, as the runtime does: build it
     * with [commandRejected]. [expect] checks the compensating commands; it fails when the saga has no handler of that
     * rejection.
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
        verify(ProcessorExpectations(listOf(onRejected.name), call(), call), expect)
    }

    /**
     * Hands the success receipt of [command] to the saga's `@OnCommitted` handler of it, as the runtime does once a
     * command a handler derived is committed, in the saga's context or another (decision D192). [command] is one this
     * specification derived ([ProcessorExpectations.commands]), so the receipt goes back to the execution that derived
     * it. It was committed on the aggregate [aggregateId] (the target's, for a creation that named none, decision
     * D191) at [version], [duplicate] when the target had already committed its request; [commandType] is its full
     * type name ([commandTypeOf] by default). [expect] checks the commands the handler derives; it fails when the saga
     * has no handler of that receipt.
     */
    @Suppress("LongParameterList")
    fun whenCommitted(
        command: Command<*>,
        version: Int = 1,
        aggregateId: String = command.ref.id,
        duplicate: Boolean = false,
        commandType: String = commandTypeOf(command),
        expect: ProcessorExpectations.() -> Unit,
    ) {
        val execution = derivedBy[command.id] ?: throw IllegalArgumentException(
            "${command.id} is not a command this specification derived: hand over commandCommitted(command, " +
                "executionId) instead, with the execution that derived it.",
        )
        val committed = commandCommitted(command, execution, version, aggregateId, duplicate, commandType)
        whenCommitted(committed, command.body.javaClass.kotlin, expect)
    }

    /**
     * Hands [committed], the success receipt of a command of [command] that the saga's handler named by its
     * [CommandCommitted.executionId] derived, to the saga's `@OnCommitted` handler of it, as the runtime does: build
     * it with [commandCommitted]. [expect] checks the commands the handler derives; it fails when the saga has no
     * handler of that receipt.
     */
    fun whenCommitted(committed: CommandCommitted<*>, command: KClass<*>, expect: ProcessorExpectations.() -> Unit) {
        require(kind == FunctionKind.SAGA) { "Only a saga takes the receipts of the commands it derived." }
        val subscription = committed.executionId.subscription
        val saga = handlers.firstOrNull { it.name == subscription } ?: throw IllegalArgumentException(
            "$subscription, which derived ${committed.commandId}, is not a handler of $processor.",
        )
        val onCommitted = saga.committedHandlerOf(command.java) ?: throw AssertionError(
            "$processor has no @OnCommitted handler of the receipts of ${command.qualifiedName}.",
        )
        val call = { commit(onCommitted, committed) }
        verify(ProcessorExpectations(listOf(onCommitted.name), call(), call), expect)
    }

    /**
     * Hands each of [events] to the handlers that read it, in order, as [whenEvent] does, and checks what they did
     * together in [expect]: the commands of every handling, in order. [ScheduleSpec] hands the ticks of a time step so.
     */
    internal fun whenEvents(events: List<StateEvent<*>>, expect: ProcessorExpectations.() -> Unit) {
        val called = events.map { event -> event to handlers.filter { reads(it, event) } }
        val call = {
            runCatchingAll {
                val handled = called.map { (event, readers) -> handle(readers, event).getOrThrow() }
                Handled(handled.flatMap { it.commands }, handled.flatMap { it.calls })
            }
        }
        val names = called.flatMap { (_, readers) -> readers.map { it.name } }.distinct()
        verify(ProcessorExpectations(names, call(), call), expect)
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
        val calls = ArrayList<Command<*>>()
        val answering = CommandCalls { command ->
            calls += command
            answer(command)
        }
        called.forEach { handler ->
            val context = contextOf(handler.kind, handler.name, event, SequentialIds(), calls = answering)
            val derived = runBlocking { handler.invoke(event, context) }
            derived.forEach { derivedBy[it.id] = context.executionId }
            commands += derived
        }
        Handled(commands, calls)
    }

    private fun reject(handler: RejectionBinding<*>, rejected: CommandRejected<*>): Result<Handled> =
        runCatchingAll {
            val context = rejectionContextOf(handler, rejected, SequentialIds())
            val derived = runBlocking { handler.invoke(rejected, context) }
            derived.forEach { derivedBy[it.id] = context.executionId }
            Handled(derived)
        }

    private fun commit(handler: CommittedBinding<*>, committed: CommandCommitted<*>): Result<Handled> =
        runCatchingAll {
            val context = committedContextOf(handler, committed, SequentialIds())
            val derived = runBlocking { handler.invoke(committed, context) }
            derived.forEach { derivedBy[it.id] = context.executionId }
            Handled(derived)
        }

    /** The commands of one handling, the schedules it set or cancelled among them, and the commands it [calls]. */
    internal data class Handled(val commands: List<Command<*>>, val calls: List<Command<*>> = emptyList())

    private companion object {
        /** Whether [handler]'s subscription hands it [event]; a job's handler only its own job's ticks. */
        fun reads(handler: ProcessorBinding<*>, event: StateEvent<*>): Boolean {
            val job = handler.job
            if (job != null) {
                return (event.state as? JobState)?.job == job.name
            }
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
