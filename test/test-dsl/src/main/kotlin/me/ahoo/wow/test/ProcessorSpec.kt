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

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.read.NodeQueryApi
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.subscribe.CommandReceipt
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.api.subscribe.SubscriptionContext
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.BlockingDispatcher
import me.ahoo.wow.kernel.CallRejectedHandlingException
import me.ahoo.wow.kernel.CommandCalls
import me.ahoo.wow.kernel.CommittedBinding
import me.ahoo.wow.kernel.ContextQueries
import me.ahoo.wow.kernel.ExecutionSagaContext
import me.ahoo.wow.kernel.HandlerTimeoutException
import me.ahoo.wow.kernel.HandlingRejectedException
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.RejectionBinding
import me.ahoo.wow.kernel.RejectionCodes
import me.ahoo.wow.kernel.committedContextOf
import me.ahoo.wow.kernel.contextOf
import me.ahoo.wow.kernel.rejectionContextOf
import me.ahoo.wow.kernel.schedule.JobState
import me.ahoo.wow.spi.state.StateEventPayload
import org.junit.jupiter.api.DynamicNode
import java.util.stream.Stream
import kotlin.reflect.KClass

/**
 * Specifies a saga's behaviour: given a commit of a stream, its own context's or one it reads from another context
 * (a [StateEvent], built with [stateEvent]), or the rejection or the commit of a command it derived, the commands it
 * derives and the commands it schedules. [handlers] are the handlers of the saga under test, from the function the compiler
 * generated for it (`<processor>Handlers`, `orderSagaHandlers` for `OrderSaga`), which constructs that saga only,
 * with its own services:
 *
 * ```kotlin
 * sagaSpec(orderSagaHandlers()) {
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
 * are [SequentialIds], counted anew for each call: `id-1`… and `<aggregate>-1`….
 *
 * The synchronous calls a saga makes ([me.ahoo.wow.api.subscribe.SagaContext.call]) are answered by
 * [answerCalls], committed at version 1 by default ([committedResult]); [ProcessorExpectations.calls] lists them.
 */
fun sagaSpec(handlers: List<ProcessorBinding<*>>, block: ProcessorSpec.() -> Unit) {
    ProcessorSpec(handlers, FunctionKind.SAGA).block()
}

/**
 * The specification [sagaSpec] runs, as a tree of tests for a JUnit `@TestFactory`: each
 * [ProcessorSpec.whenEvent], [ProcessorSpec.whenRejected] and [ProcessorSpec.whenCommitted] is a test of its own, and
 * each [ProcessorSpec.fork] a container named by it; a failing check fails its own test only, as
 * [aggregateSpecTests] does:
 *
 * ```kotlin
 * @TestFactory
 * fun orderSaga() = sagaSpecTests(orderSagaHandlers()) {
 *     whenEvent(stateEvent(order, OrderState("order-1"), orderCreated)) {
 *         expectCommands(ReserveStock("order-1", 2))
 *         fork("out of stock") {
 *             whenRejected(commands.single(), code = "inventory.stock.insufficient") { expectCommandTypes(CancelOrder::class) }
 *         }
 *         fork("reserved") { whenCommitted(commands.single()) { expectCommandTypes(ConfirmStock::class) } }
 *     }
 * }
 * ```
 */
fun sagaSpecTests(handlers: List<ProcessorBinding<*>>, block: ProcessorSpec.() -> Unit): Stream<DynamicNode> =
    processorSpecTests(handlers, FunctionKind.SAGA, block)

/**
 * Specifies a projection's behaviour: given a commit (a [StateEvent], built with [stateEvent]), what its handlers
 * write. [handlers] are the projection's, from the function the compiler generated for it
 * (`<processor>Handlers`), with a fake of the port it writes to, which the expectations then read:
 *
 * ```kotlin
 * val views = InMemoryOrderViews()
 * projectionSpec(orderViewProjectionHandlers(views)) {
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

/** The specification [projectionSpec] runs, as a tree of tests for a JUnit `@TestFactory`, as [sagaSpecTests]. */
fun projectionSpecTests(handlers: List<ProcessorBinding<*>>, block: ProcessorSpec.() -> Unit): Stream<DynamicNode> =
    processorSpecTests(handlers, FunctionKind.PROJECT, block)

/** Specifies a reactor's behaviour as [projectionSpec] does a projection's: given a commit, its side effects. */
fun reactorSpec(handlers: List<ProcessorBinding<*>>, block: ProcessorSpec.() -> Unit) {
    ProcessorSpec(handlers, FunctionKind.REACT).block()
}

/** The specification [reactorSpec] runs, as a tree of tests for a JUnit `@TestFactory`, as [sagaSpecTests]. */
fun reactorSpecTests(handlers: List<ProcessorBinding<*>>, block: ProcessorSpec.() -> Unit): Stream<DynamicNode> =
    processorSpecTests(handlers, FunctionKind.REACT, block)

private fun processorSpecTests(
    handlers: List<ProcessorBinding<*>>,
    kind: FunctionKind,
    block: ProcessorSpec.() -> Unit,
): Stream<DynamicNode> {
    val tree = TestTree()
    tree.run { ProcessorSpec(handlers, kind, tree).block() }
    return tree.nodes.stream()
}

/**
 * The full type name of [command] as its receipts carry it, `<context>.<aggregate>.<name>`: the name is its class's
 * `@Name`, or its simple name.
 */
fun commandTypeOf(command: Command<*>): String {
    val type = command.body.javaClass
    return "${command.ref.type}.${type.getAnnotation(Name::class.java)?.value ?: type.simpleName}"
}

/**
 * The rejection of [command] with [code] and [message], as the runtime hands it to the `@OnRejected` handler of the
 * saga whose execution [executionId] derived the command: for [ProcessorSpec.whenRejected].
 */
fun rejectedReceipt(
    command: Command<*>,
    executionId: ExecutionId,
    code: String,
    message: String = code,
    commandType: String = commandTypeOf(command),
): CommandReceipt.Rejected<Any> = CommandReceipt.Rejected(
    commandId = command.id,
    commandType = commandType,
    ref = command.ref,
    error = ErrorInfo(code, message),
    causation = command.causation ?: Causation(command.id, command.id),
    executionId = executionId,
    operator = command.operator,
    headers = RequestFacts.propagatedOf(command.headers),
)

/**
 * The success receipt of [command], committed on the aggregate [aggregateId] at [version] ([duplicate] when its
 * target had already committed the request), as the runtime hands it to the `@OnCommitted` handler of the saga whose
 * execution [executionId] derived the command: for [ProcessorSpec.whenCommitted].
 */
@Suppress("LongParameterList")
fun committedReceipt(
    command: Command<*>,
    executionId: ExecutionId,
    version: Int = 1,
    aggregateId: String = command.ref.id,
    duplicate: Boolean = false,
    commandType: String = commandTypeOf(command),
): CommandReceipt.Committed<Any> {
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
    return CommandReceipt.Committed(
        commandId = command.id,
        commandType = commandType,
        ref = ref,
        version = version,
        result = result,
        causation = causation,
        executionId = executionId,
        operator = command.operator,
        scope = command.scope,
        headers = RequestFacts.propagatedOf(command.headers),
    )
}

/**
 * Calls the [handlers] of one processor of [kind] as the runtime would, one commit or receipt at a time.
 *
 * Scenarios that share a prefix [fork] from it, at the current point or after a handling from inside its checks
 * ([ProcessorExpectations.fork]): the fork starts on a copy of what the specification holds, the
 * executions that derived each command, so a rejection or receipt in it goes back to the right handler, and how
 * calls are answered. A processor keeps no state between handlings, so that is all a fork copies: the fakes a
 * projection or reactor writes to are the test's own, shared by the forks.
 */
@WowTestDsl
class ProcessorSpec private constructor(
    val handlers: List<ProcessorBinding<*>>,
    /** The kind of the processor's handlers: a saga's, a projection's or a reactor's. */
    internal val kind: FunctionKind,
    origin: ProcessorSpec?,
    /** Where the steps report when the specification is a tree of tests ([sagaSpecTests]); null otherwise. */
    private val tree: TestTree?,
) {
    constructor(handlers: List<ProcessorBinding<*>>, kind: FunctionKind) : this(handlers, kind, null, null)

    /** A specification that reports to [tree] ([sagaSpecTests]), when there is one. */
    internal constructor(handlers: List<ProcessorBinding<*>>, kind: FunctionKind, tree: TestTree?) :
        this(handlers, kind, null, tree)

    /**
     * Assigns a command whose ref a handler leaves out its aggregate type, as the service would:
     * from the Manifest fragments and the peer contracts on the test's class path.
     */
    private val commandTypes: ClassPathCommandTypes = origin?.commandTypes ?: ClassPathCommandTypes.of()

    /** The executions that derived each command, by command id: a rejection of one goes back to its saga. */
    private val derivedBy: MutableMap<String, ExecutionId> = HashMap(origin?.derivedBy.orEmpty())

    /** How the targets answer the saga's synchronous calls. */
    private var answer: (Command<*>) -> CommandResult = origin?.answer ?: { committedResult(it) }

    /** What the handlers read through their context's queries; none until [givenQueries]. */
    private var queries: ContextQueries = origin?.queries ?: ContextQueries.NONE

    /** The aggregates [givenAggregate] gave, which [whenEvent] of events commits on: a fork advances copies of its own. */
    private val aggregates: MutableList<GivenAggregate<*>> =
        origin?.aggregates?.mapTo(ArrayList()) { it.copy() } ?: ArrayList()

    private var forkName: String? = null
    private var forks = 0

    /**
     * Answers each synchronous call a saga's handler makes with what [answer] returns for its command,
     * as the target's command gateway would: [committedResult] (the default, at version 1), [rejectedResult], which
     * the handler gets as a [me.ahoo.wow.api.client.CommandRejectedException], or [failedResult], a
     * [me.ahoo.wow.api.problem.WowProblemException]; [answer] may also throw one, as a target that is down does.
     */
    fun answerCalls(answer: (Command<*>) -> CommandResult) {
        this.answer = answer
    }

    /**
     * Serves the handlers' reads (`context.queries`) from [queries], the read side of the test, as a node serves them
     * ([ContextQueries.of]): a node's API (an engine over states the test gives) pins the tenant of the commit a handler
     * handles as trusted code, reading the whole tenant; a fake (a stub, a mock) serves the reads [QueryApi.of] a
     * caller of that tenant, the trusted ones [NodeQueryApi.trusted]. Without it, a handler that reads fails.
     */
    fun givenQueries(queries: NodeQueryApi) {
        this.queries = ContextQueries.of(queries)
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
     * Runs [block] on a copy of the specification as it is now, so that several scenarios share a prefix:
     * what [block] does stays in the fork, and the specification goes on from where it was. A failure in the
     * fork names it ([name]); in a tree of tests ([sagaSpecTests]) the fork is a container named by it. Nothing before
     * the fork runs again.
     */
    fun fork(name: String = "", block: ProcessorSpec.() -> Unit) {
        val label = name.ifBlank { "fork ${++forks}" }
        val path = forkName?.let { "$it > $label" } ?: label
        val fork: (TestTree?) -> ProcessorSpec = { branch ->
            ProcessorSpec(handlers, kind, this, branch).also {
                it.forkName = path
            }
        }
        runFork(tree, label, path, fork, block)
    }

    /**
     * The aggregate [id] of [model], in [tenantId], at the state and version its [history] leaves it in, one commit
     * per event, in [scope]: the aggregate whose next commit [whenEvent] of events hands over. The model replays the
     * history, so the state is the one the aggregate's own sourcing builds; a peer's view state the processor reads
     * without its model is built by hand with [stateEvent].
     */
    fun <S : Any> givenAggregate(
        model: AggregateModel<S>,
        id: String,
        vararg history: Any,
        tenantId: String = DEFAULT_TENANT,
        scope: Scope = Scope.NONE,
    ): GivenAggregate<S> = givenAggregate(model, model.type.ref(tenantId, id), *history, scope = scope)

    /** The aggregate [ref] of [model] at the state and version its [history] leaves it in, as [givenAggregate] does. */
    fun <S : Any> givenAggregate(
        model: AggregateModel<S>,
        ref: AggregateRef,
        vararg history: Any,
        scope: Scope = Scope.NONE,
    ): GivenAggregate<S> = GivenAggregate(model, ref, history.toList(), scope).also { aggregates += it }

    /**
     * Hands the commit of [events] on [on] after its history, the next version in the state they evolve it to, to
     * each handler that reads it, as [whenEvent] of a [StateEvent] does. [on] is the aggregate [givenAggregate] gave,
     * by default the only one; [by] is the class of the command that committed the events, which names the commit's
     * command type as the model does (`eshop-ordering.order.SubmitOrder`), [headers] its request facts and [operator]
     * its operator, as an aggregate's [AggregateSpec.whenCommand] names them.
     */
    @Suppress("LongParameterList")
    fun whenEvent(
        vararg events: Any,
        on: GivenAggregate<*>? = null,
        by: KClass<*>? = null,
        headers: Map<String, String> = emptyMap(),
        operator: Operator? = null,
        handler: String? = null,
        expect: ProcessorExpectations.() -> Unit = {},
    ) {
        val commit = given(on).commit(events.toList(), by, headers, operator)
        deliver("whenEvent(${commit.describe()})", commit, handler, expect)
    }

    /**
     * Hands the latest commit of [on] (by default the only aggregate given) that [whenEvent] handed over to its
     * handlers again, as a subscription does that hands each commit at least once: after a retry or a rebalance, say.
     * A handler that is idempotent does the same again, or nothing.
     */
    fun whenRedelivered(
        on: GivenAggregate<*>? = null,
        handler: String? = null,
        expect: ProcessorExpectations.() -> Unit = {},
    ) {
        val commit = given(on).handed(null)
        deliver("whenRedelivered(${commit.describe()} @${commit.version})", commit, handler, expect)
    }

    /**
     * Hands the commit of [on] (by default the only aggregate given) at [version], one [whenEvent] handed over before
     * later ones, to its handlers again, out of order: an older commit arriving after newer ones, as another
     * partition's lag or a replay delivers it. A handler that keeps the newest version leaves what the later commits
     * wrote.
     */
    fun whenDeliveredLate(
        version: Int,
        on: GivenAggregate<*>? = null,
        handler: String? = null,
        expect: ProcessorExpectations.() -> Unit = {},
    ) {
        val aggregate = given(on)
        require(version < aggregate.version) {
            "${aggregate.ref} is at version ${aggregate.version}: a commit delivered late is an older one."
        }
        val commit = aggregate.handed(version)
        deliver("whenDeliveredLate(${commit.describe()} @$version)", commit, handler, expect)
    }

    /** This specification's own copy of the aggregate [on] names, or the only one given when [on] is null. */
    private fun given(on: GivenAggregate<*>?): GivenAggregate<*> {
        if (on != null) {
            return aggregates.firstOrNull { it.ref == on.ref } ?: throw IllegalArgumentException(
                "${on.ref} is not an aggregate this specification gave: givenAggregate gives it.",
            )
        }
        return aggregates.singleOrNull() ?: throw IllegalArgumentException(
            if (aggregates.isEmpty()) {
                "No aggregate is given: givenAggregate gives the aggregate whose commit whenEvent hands over."
            } else {
                "Several aggregates are given (${aggregates.map { it.ref }}): name the one committing with `on`."
            },
        )
    }

    /**
     * Hands [event] to each handler that reads it, as its subscription would: those that name its aggregate type in
     * `aggregates`, or that name none and take its state type (or `StateEvent<*>`), but a handler of one event type
     * skips a commit without an event of it ([ProcessorBinding.skips]), as the runtime's does. A commit each of them
     * skips is handled by none ([ProcessorExpectations.expectHandledBy] of no handler); one no handler reads fails, as a
     * mistake of the test. [handler] names the only one to call instead, by its function name (`onOrderCreated`) or
     * its subscription name (`OrderSaga.onOrderCreated`). [expect] checks what they did. An exception a handler throws
     * fails the specification, unless [expect] checks it ([ProcessorExpectations.expectError],
     * [ProcessorExpectations.expectRetried], [ProcessorExpectations.expectParked]).
     */
    fun whenEvent(
        event: StateEvent<*>,
        handler: String? = null,
        expect: ProcessorExpectations.() -> Unit = {},
    ) = deliver("whenEvent(${event.describe()})", event, handler, expect)

    /** Hands [event] to its handlers, or to [handler], as [whenEvent] does: the step [name]. */
    private fun deliver(
        name: String,
        event: StateEvent<*>,
        handler: String?,
        expect: ProcessorExpectations.() -> Unit,
    ) = step(tree, name) {
        val called = if (handler == null) {
            // A stream no handler subscribes to is a mistake of the test; one each subscriber skips, a handling of none.
            handlers.filter { subscribes(it, event) }.ifEmpty {
                throw AssertionError(
                    "No handler of $processor reads ${event.ref.type} with the state ${event.state?.javaClass?.name}: " +
                        "the handlers are ${handlers.map { it.function }}.",
                )
            }.filterNot { it.skips(event.events) }
        } else {
            listOf(find(handler))
        }
        val call = { handle(called, event) }
        val result = call()
        verify(ProcessorExpectations(called.map { it.name }, result, call, this, failing?.retry), expect)
    }

    /**
     * Hands the rejection of [command] with [code] and [message] to the saga's `@OnRejected` handler of it, as the
     * runtime does for a command a handler derived and its target refused. [command] is one
     * this specification derived ([ProcessorExpectations.commands]), so the rejection goes back to the execution that
     * derived it; [commandType] is its full type name ([commandTypeOf] by default). [expect] checks the compensating
     * commands. When the saga has no handler of that rejection, or [command] is one a receipt handler derived (a
     * compensation, which does not compensate itself), the runtime parks the aggregate at once, so [expect] checks
     * [ProcessorExpectations.expectParked].
     *
     * [code] is a code the target may answer: one its context's contract declares, the generated
     * `<Context>Contract.<Aggregate>.Errors` constant, or its local name, which the target's type qualifies; or a
     * framework code ([me.ahoo.wow.api.result.FrameworkCodes]). Another code fails, listing the target's; when the
     * target's contract is not on the test's class path, the code is not checked and a warning says so.
     */
    fun whenRejected(
        command: Command<*>,
        code: String,
        message: String = code,
        commandType: String = commandTypeOf(command),
        expect: ProcessorExpectations.() -> Unit = {},
    ) = step(tree, "whenRejected(${command.body.javaClass.simpleName}, $code)") {
        val execution = derivedBy[command.id] ?: throw IllegalArgumentException(
            "${command.id} is not a command this specification derived: hand over rejectedReceipt(command, " +
                "executionId, code) instead, with the execution that derived it.",
        )
        val qualified = checkedCode(code, command.ref.type)
        val rejected = rejectedReceipt(command, execution, qualified, message, commandType)
        reject(rejected, command.body.javaClass.kotlin, expect)
    }

    /** [code], qualified with [target] when it is a local name, once [target] or the framework may answer it. */
    private fun checkedCode(code: String, target: AggregateType): String {
        val qualified = RejectionCodes.qualify(code, target)
        if (qualified in FrameworkCodes.ALL) return qualified
        val declared = commandTypes.errorsOf(target)
        if (declared == null) {
            logger.warn { "No contract of ${target.context} is on the class path: the code $qualified is not checked." }
            return qualified
        }
        require(qualified in declared) {
            "${target.context} declares no rejection code $qualified: its codes are ${declared.sorted()}, beside the " +
                "framework's (FrameworkCodes)."
        }
        return qualified
    }

    /**
     * Hands [rejected], the rejection of a command of [command] that the saga's handler named by its
     * [CommandReceipt.Rejected.executionId] derived, to the saga's `@OnRejected` handler of it, as the runtime does: build it
     * with [rejectedReceipt]. [expect] checks the compensating commands, or, when the saga has no handler of that
     * rejection, the park ([ProcessorExpectations.expectParked]).
     */
    fun whenRejected(
        rejected: CommandReceipt.Rejected<*>,
        command: KClass<*>,
        expect: ProcessorExpectations.() -> Unit = {},
    ) = step(tree, "whenRejected(${command.simpleName}, ${rejected.error.code})") { reject(rejected, command, expect) }

    private fun reject(rejected: CommandReceipt.Rejected<*>, command: KClass<*>, expect: ProcessorExpectations.() -> Unit) {
        require(kind == FunctionKind.SAGA) { "Only a saga takes the rejections of the commands it derived." }
        val subscription = rejected.executionId.subscription
        val saga = handlers.firstOrNull { it.name == subscription }
        if (saga == null) {
            // A command a receipt handler derived: as the runtime does, its rejection goes to no handler (a compensation
            // does not compensate itself), and parks the aggregate at once.
            val receipting = handlers.firstOrNull { handler ->
                (handler.rejections.map { it.name } + handler.commits.map { it.name }).contains(subscription)
            } ?: throw IllegalArgumentException(
                "$subscription, which derived ${rejected.commandId}, is not a handler of $processor.",
            )
            parked(rejected, receipting, expect)
            return
        }
        // As the runtime does: a rejection no @OnRejected handler takes parks the aggregate at once.
        val onRejected = saga.rejectionHandlerOf(command.java) ?: return parked(rejected, saga, expect)
        val call = { reject(onRejected, rejected) }
        verify(ProcessorExpectations(listOf(onRejected.name), call(), call, this, saga.retry), expect)
    }

    /** The handling [rejected] parks: no handler takes it, so the aggregate parks at once, as [saga]'s. */
    private fun parked(
        rejected: CommandReceipt.Rejected<*>,
        saga: ProcessorBinding<*>,
        expect: ProcessorExpectations.() -> Unit
    ) {
        val parked = Result.failure<Handled>(
            HandlingRejectedException(
                rejected.error,
                "The derived command ${rejected.commandId} on ${rejected.ref} was rejected: " +
                    "${rejected.error.code} ${rejected.error.message}",
            ),
        )
        verify(ProcessorExpectations(emptyList(), parked, { parked }, this, saga.retry), expect)
    }

    /**
     * Hands the success receipt of [command] to the saga's `@OnCommitted` handler of it, as the runtime does once a
     * command a handler derived is committed, in the saga's context or another. [command] is one this
     * specification derived ([ProcessorExpectations.commands]), so the receipt goes back to the execution that derived
     * it. It was committed on the aggregate [aggregateId] (the target's, for a creation that named none)
     * at [version], [duplicate] when the target had already committed its request; [commandType] is its full
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
        expect: ProcessorExpectations.() -> Unit = {},
    ) = step(tree, "whenCommitted(${command.body.javaClass.simpleName})") {
        val execution = derivedBy[command.id] ?: throw IllegalArgumentException(
            "${command.id} is not a command this specification derived: hand over committedReceipt(command, " +
                "executionId) instead, with the execution that derived it.",
        )
        val committed = committedReceipt(command, execution, version, aggregateId, duplicate, commandType)
        commit(committed, command.body.javaClass.kotlin, expect)
    }

    /**
     * Hands [committed], the success receipt of a command of [command] that the saga's handler named by its
     * [CommandReceipt.Committed.executionId] derived, to the saga's `@OnCommitted` handler of it, as the runtime does: build
     * it with [committedReceipt]. [expect] checks the commands the handler derives; it fails when the saga has no
     * handler of that receipt.
     */
    fun whenCommitted(
        committed: CommandReceipt.Committed<*>,
        command: KClass<*>,
        expect: ProcessorExpectations.() -> Unit = {},
    ) = step(tree, "whenCommitted(${command.simpleName})") { commit(committed, command, expect) }

    private fun commit(committed: CommandReceipt.Committed<*>, command: KClass<*>, expect: ProcessorExpectations.() -> Unit) {
        require(kind == FunctionKind.SAGA) { "Only a saga takes the receipts of the commands it derived." }
        val subscription = committed.executionId.subscription
        val saga = handlers.firstOrNull { it.name == subscription } ?: throw IllegalArgumentException(
            "$subscription, which derived ${committed.commandId}, is not a handler of $processor.",
        )
        val onCommitted = saga.committedHandlerOf(command.java) ?: throw AssertionError(
            "$processor has no @OnCommitted handler of the receipts of ${command.qualifiedName}.",
        )
        val call = { commit(onCommitted, committed) }
        verify(ProcessorExpectations(listOf(onCommitted.name), call(), call, this, saga.retry), expect)
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
        // The budget of the job's handler that threw, its @Retry, as the runtime retries it.
        verify(ProcessorExpectations(names, call(), call, this, failing?.retry), expect)
    }

    private fun verify(expectations: ProcessorExpectations, expect: ProcessorExpectations.() -> Unit) {
        expectations.expect()
        expectations.verify()
    }

    private fun find(handler: String): ProcessorBinding<*> =
        handlers.firstOrNull { it.function == handler || it.name == handler } ?: throw IllegalArgumentException(
            "$processor has no handler $handler: its handlers are ${handlers.map { it.function }}.",
        )

    /** The handler whose handling threw last, whose retry budget [ProcessorExpectations.expectParked] reads. */
    private var failing: ProcessorBinding<*>? = null

    private fun handle(called: List<ProcessorBinding<*>>, event: StateEvent<*>): Result<Handled> = runCatchingAll {
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

    private fun reject(handler: RejectionBinding<*>, rejected: CommandReceipt.Rejected<*>): Result<Handled> =
        runCatchingAll {
            val calls = ArrayList<Command<*>>()
            val context =
                rejectionContextOf(handler, rejected, SequentialIds(), commandTypes, queries, answering(calls))
            val derived = receiptHandling { handler.invoke(rejected, context) }
            derived.forEach { derivedBy[it.id] = context.executionId }
            Handled(derived, calls)
        }

    private fun commit(handler: CommittedBinding<*>, committed: CommandReceipt.Committed<*>): Result<Handled> =
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

    /** The commands of one handling, the schedules it set or cancelled among them, and the commands it [calls]. */
    internal data class Handled(val commands: List<Command<*>>, val calls: List<Command<*>> = emptyList())

    private companion object {
        val logger = KotlinLogging.logger {}

        /** The events of [this], by name, for a step's test. */
        fun StateEvent<*>.describe(): String =
            events.joinToString { (it as? StateEventPayload)?.name ?: it.javaClass.simpleName }

        /**
         * Whether [handler]'s subscription hands it [event], as the runtime's does: one it [subscribes] to, and not a
         * stream without an event of the handler's type ([ProcessorBinding.skips]).
         */
        fun reads(handler: ProcessorBinding<*>, event: StateEvent<*>): Boolean =
            subscribes(handler, event) && !handler.skips(event.events)

        /** Whether [handler] subscribes to [event]'s stream: its aggregate or its state; a job's handler its job's ticks. */
        fun subscribes(handler: ProcessorBinding<*>, event: StateEvent<*>): Boolean {
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
