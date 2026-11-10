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

package me.ahoo.wow.runtime.wait

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.cluster.CommandRouter
import me.ahoo.wow.runtime.cluster.TopicRouter
import me.ahoo.wow.spi.id.GlobalIdGenerator
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.observation.traceContext
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import java.time.Clock
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Runs commands and waits for their chains (–).
 *
 * On the node a command arrives at, [execute] and [stream] check its wait plan, register the wait in [registry],
 * execute the command with a wait context naming this node, and answer once the chain is complete, has failed, or the
 * timeout runs out:
 *
 * - ACCEPTED and COMMITTED are reached here, from the kernel's outcome; a HANDLED hop by a signal from the
 *   subscription it names, which finds the wait context on the log entry.
 * - The results the decide function put come with the command's commit, at COMMITTED, from this node's
 *   execution or the owner's forward reply; each later hop's result of that command carries them, whichever node's
 *   subscription signalled it, so the default wait ends with them at the snapshot (see [WaitState]).
 * - A rejection or failure ends the wait at once. A command that changed nothing reaches its later hops at once:
 *   there is no entry for a subscription to handle.
 * - A duplicate request cannot be signalled again: its later hops happened, or will, under the first
 *   request's wait. A HANDLED hop of this context that ends the chain is checked from the subscription's checkpoint
 *   and the failure ledger ([SubscriptionDirectory.hasHandled]); otherwise the wait stops at COMMITTED, `duplicate`.
 * - On timeout the answer is FAILED with [ResultCodes.WAIT_TIMEOUT] at the last hop reached. The deadline covers the
 *   whole wait, the command's execution included: the command is routed and executed in a supervised
 *   scope this coordinator owns, so a wait that times out before the command's outcome is known answers at ACCEPTED
 *   while the execution goes on, not cancelled half-way through a commit. Its outcome is then only logged; a retry
 *   with the same request id finds it, as the kernel deduplicates by request id. A command withdrawn because the
 *   deadline passed while it waited in its lane or before it was forwarded ends the
 *   wait the same way, whichever noticed the deadline first.
 * - The deadline is the wait's, not its caller's: the runtime's own deadline thread ([deadlines]) ends the wait when
 *   it passes, whatever the caller's coroutine context. A caller's own timers, such as a WebFlux handler's, run in
 *   `Dispatchers.Unconfined` on kotlinx's one `DefaultExecutor` thread, which also runs inline whatever such a
 *   timer resumes, so one held-up continuation there would hold every such deadline up. The wait itself runs in
 *   this coordinator's execution context, so neither the signals nor the deadline run its caller's code.
 * - A plan the runtime cannot follow is rejected before the command runs ([ResultCodes.WAIT_INVALID]), including an
 *   explicit snapshot hop for an aggregate without a snapshot subscription; only the default plan
 *   ([me.ahoo.wow.api.result.WaitPlan.DEFAULT]) waits for COMMITTED there instead.
 *
 * [forward] runs a command derived on the way, such as by a saga, with the wait context it inherited: it sends the
 * command's own results to the waiting node through [signals] instead of answering them.
 *
 * Which subscriptions a chain may name, and their kinds, come from [directory], until the Manifest gives them.
 *
 * Where the command runs is [router]'s choice: here, through [executeHere], or on its partition's
 * owner, which executes it with the same wait context and replies with its [Settlement]; either way the wait goes
 * on here, and the later hops are signalled here.
 *
 * The commands of the types [topicRouter] routes take the topic router instead: the
 * command is written to its context's command topic, with the wait context, and the wait reaches ACCEPTED once the
 * broker acknowledged it, durable from then on; the owner of its log partition executes it from the topic
 * ([me.ahoo.wow.runtime.command.CommandIntake]) and signals its COMMITTED result and the later hops here, as for a
 * saga's command. A broker that does not acknowledge it before the deadline ends the wait at ACCEPTED, FAILED. Only
 * the commands admitted here take it: [forward] routes a saga's, an intake's and a timer's command as before, since
 * the intake that reads the topic forwards what it reads.
 *
 * The executions outlive their waits, so a node that stops [join]s them before it closes its executor, then [close]s
 * the coordinator.
 */
@InternalWowApi
class WaitCoordinator(
    private val executor: Executor,
    private val registry: WaitRegistry,
    private val directory: SubscriptionDirectory,
    private val signals: SignalSender,
    origin: Origin,
    /** The tuning of the waits started here. */
    val options: WaitOptions = WaitOptions(),
    internal val clock: Clock = Clock.systemUTC(),
    private val waitIds: () -> String = GlobalIdGenerator.UUID_V7::next,
    private val router: CommandRouter = CommandRouter.LOCAL,
    private val executionContext: CoroutineContext = Dispatchers.Default,
    /** The topic router of the context's types configured for it; none by default. */
    private val topicRouter: TopicRouter? = null,
    /** Sees the commands refused before the kernel and the waits that time out. */
    private val observer: Observer = Observer.NONE,
    /** Ends the waits at their deadlines: the runtime's deadline threads, unless a test holds them. */
    private val deadlines: WaitDeadlines = WaitDeadlines.SHARDED,
) {
    /** Where the commands of [stream] execute, apart from their waits. */
    private val executions = CoroutineScope(SupervisorJob() + executionContext)

    /**
     * This node, as signals address it. A [me.ahoo.wow.runtime.node.ServiceNode] that learns its
     * internal address only once its internal port is bound sets the origin's address then, before it starts.
     */
    @Volatile
    var origin: Origin = origin
        internal set

    /** What this node answers for a command it runs itself. */
    private val local = LocalExecution(executor, directory, observer, clock) { origin }

    /** The commands that inherit a wait instead of starting one, run in [executions]. */
    private val inherited =
        InheritedWaits(router, local, signals, directory, deadlines, options, clock, observer, executions)

    /** The commands executing in this coordinator's scope, their waits ended or not. */
    val executing: Int get() = executions.coroutineContext.job.children.count()

    /**
     * Waits until no command executes in this coordinator's scope: those whose waits ended before their outcome was
     * known settle normally, their outcome logged. The caller bounds it; it does not stop new executions.
     */
    suspend fun join() {
        while (true) {
            val running = executions.coroutineContext.job.children.toList()
            if (running.isEmpty()) {
                return
            }
            running.joinAll()
        }
    }

    /** Cancels what still executes in this coordinator's scope; a stopping node calls it after [join]. */
    fun close() {
        executions.cancel()
    }

    /**
     * Executes [command] on [model]'s aggregate and returns the result it ends its wait with: the last one [stream]
     * would emit. It runs in this coordinator's execution context as [stream] does, without the channel and producer
     * coroutine a flow moved to another context takes: a caller that wants the final result only pays
     * for that.
     */
    suspend fun execute(model: AggregateModel<*>, command: Command<*>): CommandResult = withContext(executionContext) {
        var last: CommandResult? = null
        run(model, command) { last = it }
        checkNotNull(last) { "The wait of ${command.id} ended without a result." }
    }

    /**
     * Executes [command] on [model]'s aggregate and emits a result for each hop of its wait plan as it is reached, one
     * per command: a hop after a saga's emits one result for each command the saga derived, as each one reaches it,
     * in the order they arrive, and is complete with the last of them. The command is executed when
     * the flow is collected.
     *
     * - The flow completes after the final result, the last one emitted: the last hop's last command, a saga hop that
     *   derived nothing before the plan's last hop (marked [CommandResult.chainEnded], with the hops not reached as
     *   [CommandResult.pending]), the first failure or rejection of any command on the chain (emitted
     *   at once, with nothing after it), a duplicate stopped at its last known hop, or a timeout
     *   ([ResultCodes.WAIT_TIMEOUT], at the last hop completed). A plan rejected at admission emits only that
     *   rejection, at ACCEPTED.
     * - A plan whose only hop is ACCEPTED still runs the command before the flow completes; [accept] answers it at
     *   once instead.
     * - The timeout bounds the execution too: a command whose outcome is not known by the deadline ends
     *   the wait at ACCEPTED, timed out, and goes on executing in this coordinator's scope, as does one whose
     *   collector cancels the flow first; its outcome is logged. The deadline does not depend on the collector's
     *   context, and the flow runs in this coordinator's execution context, handing its results to the collector.
     * - Outcomes are results, never errors: the flow fails only when the execution itself throws while it is waited
     *   for, and is cancelled with its collector. Either way the wait is unregistered, and later signals for it are
     *   dropped.
     */
    fun stream(model: AggregateModel<*>, command: Command<*>): Flow<CommandResult> = flow {
        run(model, command) { emit(it) }
    }.flowOn(executionContext)

    /**
     * Sends [command], admitted here for an aggregate of another service's context (a peer's), to
     * its context's command topic through [relay], and emits a result for each hop of its wait plan as it is reached,
     * as the topic router's commands do: ACCEPTED once the broker acknowledged it, durable from then
     * on; the target's intake executes it and signals its COMMITTED result here. [function] names the command in the
     * results this node makes. The command's plan has only ACCEPTED and COMMITTED hops of its context: admission
     * refuses any other.
     */
    fun relay(command: Command<*>, function: FunctionInfo, relay: CommandRelay): Flow<CommandResult> = flow {
        run(null, command, function, relay) { emit(it) }
    }.flowOn(executionContext)

    /** Sends [command] as [relay] does and returns the result its wait ends with: the last one [relay] would emit. */
    suspend fun executeRelayed(command: Command<*>, function: FunctionInfo, relay: CommandRelay): CommandResult =
        withContext(executionContext) {
            var last: CommandResult? = null
            run(null, command, function, relay) { last = it }
            checkNotNull(last) { "The wait of ${command.id} ended without a result." }
        }

    /**
     * Sends [command], whose plan has only an ACCEPTED hop, as [relay] does, and returns its result at ACCEPTED once
     * the broker acknowledged it; FAILED at ACCEPTED when it did not within the plan's timeout ([sendThrough]).
     */
    suspend fun acceptRelayed(command: Command<*>, function: FunctionInfo, relay: CommandRelay): CommandResult {
        require(command.wait.isAcceptedOnly) { "Only a plan whose only hop is ACCEPTED is answered at once." }
        val deadline = clock.millis() + minOf(command.wait.timeout, options.maxTimeout).inWholeMilliseconds
        return sendThrough(relay, command, function, null, deadline)
            ?: reachedResult(command, function, Hop.accepted(command.ref.type.context), clock.millis())
    }

    /** [emit], counting a timeout the wait ends with. */
    private suspend fun observed(command: Command<*>, result: CommandResult, emit: suspend (CommandResult) -> Unit) {
        if (result.error?.code == ResultCodes.WAIT_TIMEOUT) {
            observer.observed(ObservedEvent.WaitTimedOut(command.ref.type, result.hop.stage))
        }
        emit(result)
    }

    /** Runs [command]'s wait as [stream] describes, handing each result to [emit]; the caller picks the context. */
    private suspend fun run(model: AggregateModel<*>, command: Command<*>, results: suspend (CommandResult) -> Unit) {
        val relay = topicRouter?.takeIf { it.routes(command.ref.type) }?.relayOf(model)
        run(model, command, commandFunction(model, command), relay, results)
    }

    /**
     * Runs [command]'s wait, handing each result to [emit]: through [relay] when there is one (the topic router's
     * command topic, or a peer's), else executed on [model]'s aggregate, routed by [router].
     */
    @Suppress("LongMethod") // One wait from admission to its end, the command topic's branch included.
    private suspend fun run(
        model: AggregateModel<*>?,
        command: Command<*>,
        function: FunctionInfo,
        relay: CommandRelay?,
        results: suspend (CommandResult) -> Unit,
    ) {
        val emit: suspend (CommandResult) -> Unit = { observed(command, it, results) }
        val context = command.ref.type.context
        val checked = WaitChain.of(command.wait, command.ref.type, directory, options.maxHops, options.unknownContexts)
        val chain = when (checked) {
            is WaitChain.Invalid -> {
                emit(admissionRejected(command, function, ResultCodes.WAIT_INVALID, checked.reason, clock.millis()))
                return
            }

            is WaitChain -> checked
        }
        val timeout = minOf(command.wait.timeout, options.maxTimeout)
        val deadline = clock.millis() + timeout.inWholeMilliseconds
        val waitId = waitIds()
        val waiting = registry.register(waitId, chain.hops, command.id, options.maxRegistered)
        if (waiting == null) {
            // Over the node's waits: refused before it runs, as a busy node refuses it.
            observer.observed(ObservedEvent.Refused(command.ref.type, ResultCodes.BUSY))
            emit(
                reachedResult(command, function, Hop.accepted(context), clock.millis()).failed(
                    ResultCodes.BUSY,
                    "The node holds the ${options.maxRegistered} waits it allows at once; the command was not run. " +
                        "Retry it later.",
                ),
            )
            return
        }
        var expiry: Future<*>? = null
        try {
            if (relay != null) {
                // A command topic: ACCEPTED once durable; the owner signals the rest.
                val remaining = chain.hops.dropWhile { it.stage == Stage.ACCEPTED }
                val wait = WaitContext(waitId, origin, remaining, deadline).takeIf { remaining.isNotEmpty() }
                sendThrough(relay, command, function, wait, deadline)?.let { failed ->
                    emit(failed)
                    return
                }
                waiting.offer(reachedResult(command, function, Hop.accepted(context), clock.millis()))
                expiry = expireAt(deadline, waiting)
                for (result in waiting.results) {
                    emit(waiting.carry(result))
                }
                return
            }
            waiting.offer(reachedResult(command, function, Hop.accepted(context), clock.millis()))
            // Once ACCEPTED is reached: a timeout answers at the last hop reached.
            expiry = expireAt(deadline, waiting)
            val wait = WaitContext(waitId, origin, chain.hops, deadline)
            settle(checkNotNull(model), command, wait, waiting)?.let { settlement ->
                if (withdrawn(settlement)) {
                    // Withdrawn because the deadline passed: the wait ends as the deadline ends it,
                    // whichever of the lane's timer and the deadline thread fired first.
                    waiting.expire(clock.millis(), ::timedOut)
                    return@let
                }
                waiting.offer(settlement.committed)
                settlement.reached.forEach { waiting.offer(it) }
                if (settlement.committed.duplicate) {
                    // A duplicate is not signalled again: what it did not reach at once, it will not reach.
                    waiting.finish(settlement.committed)
                }
            }
            // Until the wait ends: with its final result, or with the timeout its deadline put there.
            for (result in waiting.results) {
                emit(waiting.carry(result))
            }
        } finally {
            expiry?.cancel(false)
            registry.remove(waitId)
        }
    }

    /**
     * Ends [waiting] with a timeout at [deadline], through [deadlines], apart from the waiter, at the last hop it
     * reached ([timedOut]). Whether or not the wait had ended, its [deadline passes][Waiting.deadlinePassed].
     */
    private fun expireAt(deadline: Long, waiting: Waiting): Future<*> =
        deadlines.schedule((deadline - clock.millis()).coerceAtLeast(0)) { waiting.expire(clock.millis(), ::timedOut) }

    /**
     * The message of a wait that timed out at [hop]: the command's outcome is known once it reached COMMITTED, however
     * the wait learned it (its own settlement, or a signal of the node that ran it); before, it is not.
     */
    private fun timedOut(hop: Hop): String = if (hop.stage >= Stage.COMMITTED) {
        "The wait timed out after reaching $hop."
    } else {
        "The wait timed out at $hop, before the command's outcome was known."
    }

    /**
     * Whether [settlement] is a command withdrawn because its wait's deadline passed before it ran
     * or before it was forwarded: FAILED with [ResultCodes.WAIT_TIMEOUT] at COMMITTED, nothing
     * committed.
     */
    private fun withdrawn(settlement: Settlement): Boolean =
        settlement.committed.status == CommandResult.Status.FAILED &&
            settlement.committed.error?.code == ResultCodes.WAIT_TIMEOUT

    /**
     * Accepts [command], whose wait plan has only an ACCEPTED hop ([isAcceptedOnly], a chain that is always valid),
     * and returns its result at ACCEPTED at once. The command is routed and executed in [scope], apart
     * from the caller, with no wait context, so nothing is signalled about it; its outcome is only logged. Not durable:
     * a node that stops before running it loses it.
     *
     * A command of a type [topicRouter] routes is durable instead: it is answered at ACCEPTED
     * once the broker acknowledged it on its context's command topic, and FAILED at ACCEPTED when the broker did not
     * within its wait's timeout; the owner of its log partition executes it from there.
     */
    suspend fun accept(model: AggregateModel<*>, command: Command<*>, scope: CoroutineScope): CommandResult {
        require(command.wait.isAcceptedOnly) { "Only a plan whose only hop is ACCEPTED is answered at once." }
        val function = commandFunction(model, command)
        val hop = Hop.accepted(command.ref.type.context)
        val topic = topicRouter?.takeIf { it.routes(command.ref.type) }
        if (topic == null) {
            // In the admission's trace, which the detached execution continues.
            scope.launch(currentCoroutineContext().traceContext) { runDetached(model, command) }
            return reachedResult(command, function, hop, clock.millis())
        }
        val deadline = clock.millis() + minOf(command.wait.timeout, options.maxTimeout).inWholeMilliseconds
        return sendThrough(topic.relayOf(model), command, function, null, deadline)
            ?: reachedResult(command, function, hop, clock.millis())
    }

    /**
     * Writes [command] to [relay]'s command topic with [wait] (the topic router, or a peer's topic);
     * null once the broker acknowledged it, else its result at ACCEPTED, FAILED: [ResultCodes.UNKNOWN] when the
     * send failed, the command perhaps written all the same, or [ResultCodes.WAIT_TIMEOUT] at [deadline]. Either way a
     * retry with the same request id is safe.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever the producer throws, the command may or may not be written.
    private suspend fun sendThrough(
        relay: CommandRelay,
        command: Command<*>,
        function: FunctionInfo,
        wait: WaitContext?,
        deadline: Long,
    ): CommandResult? {
        val accepted = { reachedResult(command, function, Hop.accepted(command.ref.type.context), clock.millis()) }
        val sent = try {
            withTimeoutOrNull((deadline - clock.millis()).coerceAtLeast(0).milliseconds) { relay.send(command, wait) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn(failure) { "Command ${command.id} on ${command.ref} was not acknowledged on ${relay.topic}." }
            return accepted().failed(
                ResultCodes.UNKNOWN,
                "The command topic did not acknowledge the command; whether it was written is unknown. Retry it " +
                    "with the same request id.",
            )
        }
        return if (sent == null) {
            accepted().failed(
                ResultCodes.WAIT_TIMEOUT,
                "The wait timed out at ACCEPTED, before the command topic ${relay.topic} acknowledged the command.",
            )
        } else {
            null
        }
    }

    @Suppress("TooGenericExceptionCaught") // Nobody waits for a detached command: whatever fails is logged.
    private suspend fun runDetached(model: AggregateModel<*>, command: Command<*>) {
        try {
            val settlement = router.route(model, command, null) { executeHere(model, command, null) }
            val committed = settlement.committed
            when (committed.status) {
                CommandResult.Status.SUCCEEDED -> Unit
                // A business rejection is an outcome, not an incident.
                CommandResult.Status.REJECTED ->
                    logger.debug { "Detached command ${command.id} on ${command.ref} was rejected: ${committed.error}" }

                else -> logger.info {
                    "Detached command ${command.id} on ${command.ref} ended ${committed.status}: ${committed.error}"
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn(failure) { "Detached command ${command.id} on ${command.ref} failed." }
        }
    }

    /**
     * Routes and executes [command] in [executions] and returns its settlement, or null when [waiting]'s deadline
     * passes first. The execution is cancelled neither then nor with the caller: of the waiter giving up and the
     * execution finishing, whichever comes second learns it from [AtomicBoolean.compareAndSet] failing, and when the
     * execution finishes second, it logs its outcome.
     */
    private suspend fun settle(
        model: AggregateModel<*>,
        command: Command<*>,
        wait: WaitContext,
        waiting: Waiting,
    ): Settlement? {
        val taken = AtomicBoolean()
        val execution: Deferred<Settlement> = executions.async(currentCoroutineContext().traceContext) {
            routeReporting(model, command, wait, taken)
        }
        try {
            val settlement = select {
                execution.onAwait { it }
                waiting.deadlinePassed.onAwait { null }
            }
            return settlement ?: if (taken.compareAndSet(false, true)) null else execution.await()
        } catch (cancelled: CancellationException) {
            taken.set(true)
            throw cancelled
        }
    }

    /** Routes and executes [command]; logs its outcome when its waiter has gone ([taken] already set). */
    @Suppress("TooGenericExceptionCaught") // What the execution throws is the waiter's, or logged once it has gone.
    private suspend fun routeReporting(
        model: AggregateModel<*>,
        command: Command<*>,
        wait: WaitContext,
        taken: AtomicBoolean,
    ): Settlement {
        val settlement = try {
            router.route(model, command, wait) { executeHere(model, command, wait) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!taken.compareAndSet(false, true)) {
                logger.warn(failure) { "Command ${command.id} on ${command.ref} failed after its waiter had gone." }
            }
            throw failure
        }
        if (!taken.compareAndSet(false, true)) {
            val committed = settlement.committed
            logger.info {
                "Command ${command.id} on ${command.ref} settled ${committed.status} after its waiter had gone: " +
                    (committed.error?.toString() ?: "version ${committed.version}")
            }
        }
        return settlement
    }

    /**
     * Executes [command] on [model]'s aggregate on this node, with the [wait] context it arrived with, and settles what
     * it reaches at once ([LocalExecution.execute]).
     */
    suspend fun executeHere(model: AggregateModel<*>, command: Command<*>, wait: WaitContext?): Settlement =
        local.execute(model, command, wait)

    /**
     * Executes [command], derived on the way of a wait (by a saga, or later sent from another context), or sent by a
     * timer, with the [wait] context it inherited, and signals its results to the waiting node
     * ([InheritedWaits.forward]).
     */
    suspend fun forward(model: AggregateModel<*>, command: Command<*>, wait: WaitContext?): Settlement =
        inherited.forward(model, command, wait)

    /** Executes [command], a synchronous call another context's saga made, and answers it ([InheritedWaits.call]). */
    suspend fun call(model: AggregateModel<*>, command: Command<*>, wait: WaitContext?): CommandResult =
        inherited.call(model, command, wait)

    /** Signals [result] to the node that waits on [wait], best effort ([InheritedWaits.signal]). */
    suspend fun signal(wait: WaitContext, result: CommandResult) = inherited.signal(wait, result)

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}

/** This router's relay of [model]'s commands. */
private fun TopicRouter.relayOf(model: AggregateModel<*>): CommandRelay = object : CommandRelay {
    override val topic: String get() = this@relayOf.topic

    override suspend fun send(command: Command<*>, wait: WaitContext?) = this@relayOf.send(model, command, wait)
}

/** Whether this plan's only hop is ACCEPTED: the caller is answered at admission. */
internal val WaitPlan.isAcceptedOnly: Boolean get() = hops.size == 1 && hops.single().stage == Stage.ACCEPTED
